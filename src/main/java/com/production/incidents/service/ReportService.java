package com.production.incidents.service;

import com.production.incidents.domain.Incident;
import com.production.incidents.domain.IncidentStatus;
import com.production.incidents.domain.LogLevel;
import com.production.incidents.domain.Severity;
import com.production.incidents.repository.IncidentRepository;
import com.production.incidents.repository.LogEntryRepository;
import com.production.incidents.web.dto.Dtos.ErrorGroupDto;
import com.production.incidents.web.dto.Dtos.IncidentSummaryDto;
import com.production.incidents.web.dto.Dtos.ServiceImpactDto;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

@Service
@Transactional(readOnly = true)
public class ReportService {

    private static final List<LogLevel> ERROR_LEVELS = List.of(LogLevel.ERROR, LogLevel.FATAL);

    private final LogEntryRepository logs;
    private final IncidentRepository incidents;
    private final ErrorGroupService errorGroups;

    public ReportService(LogEntryRepository logs, IncidentRepository incidents, ErrorGroupService errorGroups) {
        this.logs = logs;
        this.incidents = incidents;
        this.errorGroups = errorGroups;
    }

    public record Summary(
            LocalDateTime from,
            LocalDateTime to,
            long totalLogEntries,
            long totalErrors,
            long errorGroups,
            long recurringErrorGroups,
            Map<IncidentStatus, Long> incidentsByStatus,
            Map<Severity, Long> incidentsBySeverity,
            Double meanTimeToResolveMinutes,
            LocalDateTime firstErrorAt,
            List<ServiceImpactDto> serviceImpact,
            List<ErrorGroupDto> topErrorGroups,
            List<IncidentSummaryDto> activeIncidents) {
    }

    public Summary summary(LocalDateTime from, LocalDateTime to) {
        LocalDateTime[] range = resolveRange(from, to);
        LocalDateTime start = range[0];
        LocalDateTime end = range[1];

        List<ServiceImpactDto> impact = serviceImpact(start, end);

        Map<IncidentStatus, Long> byStatus = new EnumMap<>(IncidentStatus.class);
        Arrays.stream(IncidentStatus.values()).forEach(s -> byStatus.put(s, 0L));
        incidents.countByStatusBetween(start, end)
                .forEach(r -> byStatus.put((IncidentStatus) r[0], ((Number) r[1]).longValue()));

        Map<Severity, Long> bySeverity = new EnumMap<>(Severity.class);
        Arrays.stream(Severity.values()).forEach(s -> bySeverity.put(s, 0L));
        incidents.countBySeverityBetween(start, end)
                .forEach(r -> bySeverity.put((Severity) r[0], ((Number) r[1]).longValue()));

        // A group belongs to the window if any of its occurrences do.
        List<ErrorGroupDto> groupsInWindow = errorGroups.list(null, false).stream()
                .filter(g -> !g.lastSeen().isBefore(start) && !g.firstSeen().isAfter(end))
                .toList();

        List<IncidentSummaryDto> active = incidents
                .findByStatusIn(List.of(IncidentStatus.OPEN, IncidentStatus.INVESTIGATING)).stream()
                .sorted(Comparator.comparing(Incident::getSeverity).reversed()
                        .thenComparing(Incident::getStartedAt))
                .map(IncidentSummaryDto::from)
                .toList();

        return new Summary(start, end,
                logs.countByTimestampBetween(start, end),
                logs.countByLevelInAndTimestampBetween(ERROR_LEVELS, start, end),
                groupsInWindow.size(),
                groupsInWindow.stream().filter(ErrorGroupDto::recurring).count(),
                byStatus, bySeverity,
                meanTimeToResolve(incidents.findByStartedAtBetween(start, end)),
                impact.stream().map(ServiceImpactDto::firstError).min(Comparator.naturalOrder()).orElse(null),
                impact,
                groupsInWindow.stream().limit(10).toList(),
                active);
    }

    public List<ServiceImpactDto> serviceImpact(LocalDateTime from, LocalDateTime to) {
        LocalDateTime[] range = resolveRange(from, to);
        return logs.serviceImpact(ERROR_LEVELS, range[0], range[1]).stream()
                .map(r -> new ServiceImpactDto((String) r[0], ((Number) r[1]).longValue(),
                        (LocalDateTime) r[2], (LocalDateTime) r[3], ((Number) r[4]).longValue()))
                .toList();
    }

    public String markdown(LocalDateTime from, LocalDateTime to) {
        Summary s = summary(from, to);
        StringBuilder md = new StringBuilder();
        md.append("# Production Incident Summary\n\n");
        md.append("**Window:** ").append(s.from()).append(" to ").append(s.to()).append(" (UTC)\n\n");

        md.append("## Overview\n\n");
        md.append("| Metric | Value |\n|---|---|\n");
        md.append("| Log entries | ").append(s.totalLogEntries()).append(" |\n");
        md.append("| Errors | ").append(s.totalErrors()).append(" |\n");
        md.append("| Distinct error groups | ").append(s.errorGroups()).append(" |\n");
        md.append("| Recurring error groups | ").append(s.recurringErrorGroups()).append(" |\n");
        md.append("| First error | ").append(orDash(s.firstErrorAt())).append(" |\n");
        md.append("| Mean time to resolve | ")
                .append(s.meanTimeToResolveMinutes() == null ? "-" : String.format(Locale.ROOT, "%.0f min", s.meanTimeToResolveMinutes()))
                .append(" |\n\n");

        md.append("## Incidents\n\n| Status | Count |\n|---|---|\n");
        s.incidentsByStatus().forEach((k, v) -> md.append("| ").append(k).append(" | ").append(v).append(" |\n"));
        md.append("\n| Severity | Count |\n|---|---|\n");
        s.incidentsBySeverity().forEach((k, v) -> md.append("| ").append(k).append(" | ").append(v).append(" |\n"));

        md.append("\n## Affected services\n\n| Service | Errors | First error | Last error | Error groups |\n|---|---|---|---|---|\n");
        for (ServiceImpactDto i : s.serviceImpact()) {
            md.append("| ").append(i.service()).append(" | ").append(i.errorCount()).append(" | ")
                    .append(i.firstError()).append(" | ").append(i.lastError()).append(" | ")
                    .append(i.distinctErrorGroups()).append(" |\n");
        }

        md.append("\n## Top recurring failures\n\n| # | Exception | Occurrences | Services | First seen | Recurring |\n|---|---|---|---|---|---|\n");
        int rank = 1;
        for (ErrorGroupDto g : s.topErrorGroups()) {
            md.append("| ").append(rank++).append(" | ").append(orDash(g.exceptionType())).append(" | ")
                    .append(g.occurrences()).append(" | ").append(String.join(", ", new TreeSet<>(g.services())))
                    .append(" | ").append(g.firstSeen()).append(" | ").append(g.recurring() ? "yes" : "no").append(" |\n");
        }

        md.append("\n## Active incidents\n\n");
        if (s.activeIncidents().isEmpty()) {
            md.append("None.\n");
        }
        for (IncidentSummaryDto i : s.activeIncidents()) {
            md.append("- **#").append(i.id()).append(" [").append(i.severity()).append("]** ").append(i.title())
                    .append(" (").append(i.status()).append(", since ").append(i.startedAt()).append(")\n");
        }
        return md.toString();
    }

    private LocalDateTime[] resolveRange(LocalDateTime from, LocalDateTime to) {
        if (from != null && to != null) {
            return new LocalDateTime[]{from, to};
        }
        List<Object[]> rows = logs.timeRange();
        Object[] r = rows.isEmpty() ? new Object[2] : rows.get(0);
        LocalDateTime min = r[0] == null ? LocalDateTime.of(1970, 1, 1, 0, 0) : (LocalDateTime) r[0];
        LocalDateTime max = r[1] == null ? LocalDateTime.of(2999, 1, 1, 0, 0) : (LocalDateTime) r[1];
        return new LocalDateTime[]{from != null ? from : min, to != null ? to : max};
    }

    private static Double meanTimeToResolve(List<Incident> list) {
        OptionalDouble avg = list.stream()
                .filter(i -> i.getResolvedAt() != null)
                .mapToLong(i -> Duration.between(i.getStartedAt(), i.getResolvedAt()).toMinutes())
                .average();
        return avg.isPresent() ? avg.getAsDouble() : null;
    }

    private static String orDash(Object o) {
        return o == null ? "-" : o.toString();
    }
}
