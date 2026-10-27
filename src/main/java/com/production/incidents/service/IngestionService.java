package com.production.incidents.service;

import com.production.incidents.config.IncidentProperties;
import com.production.incidents.domain.*;
import com.production.incidents.parsing.ErrorFingerprinter;
import com.production.incidents.parsing.LogParser;
import com.production.incidents.parsing.ParseResult;
import com.production.incidents.parsing.ParsedLog;
import com.production.incidents.repository.ErrorGroupRepository;
import com.production.incidents.repository.IncidentRepository;
import com.production.incidents.repository.LogEntryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.Reader;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);
    private static final int MAX_MESSAGE = 4000;

    private final LogParser parser;
    private final ErrorFingerprinter fingerprinter;
    private final LogEntryRepository logs;
    private final ErrorGroupRepository groups;
    private final IncidentRepository incidents;
    private final SeverityPolicy severityPolicy;
    private final IncidentProperties properties;
    private final Clock clock;

    public IngestionService(LogParser parser, ErrorFingerprinter fingerprinter, LogEntryRepository logs,
                            ErrorGroupRepository groups, IncidentRepository incidents,
                            SeverityPolicy severityPolicy, IncidentProperties properties, Clock clock) {
        this.parser = parser;
        this.fingerprinter = fingerprinter;
        this.logs = logs;
        this.groups = groups;
        this.incidents = incidents;
        this.severityPolicy = severityPolicy;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public IngestionResult ingest(String source, String content) {
        return store(source, parser.parse(content));
    }

    @Transactional
    public IngestionResult ingest(String source, Reader content) {
        return store(source, parser.parse(content));
    }

    private IngestionResult store(String source, ParseResult parsed) {
        Map<String, ErrorGroup> touched = new LinkedHashMap<>();
        Map<String, BatchStats> batch = new HashMap<>();
        int errors = 0;
        int newGroups = 0;

        List<LogEntry> entries = new ArrayList<>(parsed.entries().size());
        for (ParsedLog p : parsed.entries()) {
            LogEntry entry = new LogEntry(p.timestamp(), p.level(), truncate(p.service(), 120), truncate(p.logger(), 255),
                    truncate(p.message(), MAX_MESSAGE), truncate(p.exceptionType(), 255), p.stackTrace(), truncate(source, 255));

            if (p.level().isError()) {
                errors++;
                String normalized = truncate(fingerprinter.normalize(p.message()), MAX_MESSAGE);
                String fp = fingerprinter.fingerprint(p.exceptionType(), normalized);

                ErrorGroup group = touched.get(fp);
                if (group == null) {
                    Optional<ErrorGroup> existing = groups.findByFingerprint(fp);
                    if (existing.isEmpty()) {
                        newGroups++;
                    }
                    group = existing.orElseGet(() -> groups.save(new ErrorGroup(fp, truncate(p.exceptionType(), 255),
                            normalized, truncate(p.message(), MAX_MESSAGE), p.level(), p.timestamp())));
                    touched.put(fp, group);
                }
                group.recordOccurrence(p.timestamp(), entry.getService(), p.level());
                batch.computeIfAbsent(fp, k -> new BatchStats()).add(p.timestamp(), entry.getService());
                entry.setErrorGroup(group);
            }
            entries.add(entry);
        }
        logs.saveAll(entries);

        List<Long> opened = new ArrayList<>();
        List<Long> reopened = new ArrayList<>();
        for (Map.Entry<String, ErrorGroup> e : touched.entrySet()) {
            evaluateIncident(e.getValue(), batch.get(e.getKey()), opened, reopened);
        }

        log.info("Ingested {}: {} entries, {} errors, {} new groups, {} incidents opened, {} reopened",
                source, entries.size(), errors, newGroups, opened.size(), reopened.size());
        return new IngestionResult(source, parsed.totalLines(), parsed.skippedLines(), entries.size(),
                errors, newGroups, opened, reopened);
    }

    private void evaluateIncident(ErrorGroup group, BatchStats stats, List<Long> opened, List<Long> reopened) {
        LocalDateTime now = LocalDateTime.now(clock);
        Optional<Incident> latest = incidents.findFirstByErrorGroupIdOrderByDetectedAtDescIdDesc(group.getId());

        if (latest.isEmpty()) {
            boolean fatal = group.getHighestLevel() == LogLevel.FATAL;
            if (fatal || group.getOccurrences() >= properties.autoOpenThreshold()) {
                Incident incident = openIncident(group, now);
                opened.add(incident.getId());
            }
            return;
        }

        Incident incident = latest.get();
        Severity assessed = severityPolicy.assess(group);
        incident.getAffectedServices().addAll(stats.services);

        if (incident.getStatus().isActive()) {
            incident.addEvent(EventType.NEW_OCCURRENCES,
                    stats.count + " new occurrence(s) between " + stats.first + " and " + stats.last
                            + " in " + String.join(", ", stats.services), now);
        } else if (incident.getResolvedAt() != null && stats.last.isAfter(incident.getResolvedAt())) {
            // The failure came back after someone marked it fixed: reopen rather than lose the history.
            incident.setStatus(IncidentStatus.OPEN);
            incident.incrementRecurrence();
            incident.addEvent(EventType.RECURRED, "Failure recurred after resolution (" + stats.count
                    + " new occurrence(s), latest at " + stats.last + "). Previous resolution: "
                    + Objects.requireNonNullElse(incident.getResolutionNotes(), "none recorded"), now);
            incident.setResolvedAt(null);
            assessed = assessed.max(Severity.HIGH);
            reopened.add(incident.getId());
        } else {
            return;
        }

        if (assessed.ordinal() > incident.getSeverity().ordinal()) {
            incident.addEvent(EventType.SEVERITY_CHANGED,
                    "Severity escalated " + incident.getSeverity() + " -> " + assessed, now);
            incident.setSeverity(assessed);
        }
    }

    private Incident openIncident(ErrorGroup group, LocalDateTime now) {
        String what = group.getExceptionType() != null ? simpleName(group.getExceptionType()) : "Error";
        String title = truncate(what + ": " + group.getSampleMessage(), 200);
        String description = "Auto-detected from " + group.getOccurrences() + " occurrence(s) of a recurring error.\n"
                + "Signature: " + group.getNormalizedMessage();

        Incident incident = new Incident(title, truncate(description, 4000), severityPolicy.assess(group),
                group.getFirstSeen(), now);
        incident.setErrorGroup(group);
        incident.getAffectedServices().addAll(group.getServices());
        incident.addEvent(EventType.CREATED, "Incident opened automatically: " + group.getOccurrences()
                + " occurrence(s) in " + String.join(", ", group.getServices())
                + " since " + group.getFirstSeen(), now);
        return incidents.save(incident);
    }

    private static String simpleName(String type) {
        int dot = type.lastIndexOf('.');
        return dot >= 0 ? type.substring(dot + 1) : type;
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    private static final class BatchStats {
        int count;
        LocalDateTime first;
        LocalDateTime last;
        final Set<String> services = new TreeSet<>();

        void add(LocalDateTime at, String service) {
            count++;
            if (first == null || at.isBefore(first)) {
                first = at;
            }
            if (last == null || at.isAfter(last)) {
                last = at;
            }
            services.add(service);
        }
    }
}
