package com.production.incidents.service;

import com.production.incidents.domain.*;
import com.production.incidents.repository.ErrorGroupRepository;
import com.production.incidents.repository.IncidentRepository;
import com.production.incidents.web.dto.Dtos.CreateIncidentRequest;
import com.production.incidents.web.dto.Dtos.IncidentSummaryDto;
import com.production.incidents.web.dto.Dtos.UpdateIncidentRequest;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

@Service
@Transactional
public class IncidentService {

    private final IncidentRepository incidents;
    private final ErrorGroupRepository groups;
    private final Clock clock;

    public IncidentService(IncidentRepository incidents, ErrorGroupRepository groups, Clock clock) {
        this.incidents = incidents;
        this.groups = groups;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Incident get(Long id) {
        Incident incident = incidents.findById(id).orElseThrow(() -> new NotFoundException("Incident", id));
        incident.getEvents().size(); // initialise history inside the transaction
        if (incident.getErrorGroup() != null) {
            incident.getErrorGroup().getOccurrences();
        }
        return incident;
    }

    public Incident create(CreateIncidentRequest req) {
        LocalDateTime now = LocalDateTime.now(clock);
        ErrorGroup group = req.errorGroupId() == null ? null : groups.findById(req.errorGroupId())
                .orElseThrow(() -> new NotFoundException("Error group", req.errorGroupId()));

        LocalDateTime startedAt = req.startedAt() != null ? req.startedAt()
                : group != null ? group.getFirstSeen() : now;
        Incident incident = new Incident(req.title(), req.description(), req.severity(), startedAt, now);
        incident.setErrorGroup(group);
        incident.setAssignee(blankToNull(req.assignee()));
        if (req.affectedServices() != null) {
            incident.getAffectedServices().addAll(req.affectedServices());
        }
        if (group != null) {
            incident.getAffectedServices().addAll(group.getServices());
        }
        incident.addEvent(EventType.CREATED, "Incident opened manually", now);
        return incidents.save(incident);
    }

    public Incident update(Long id, UpdateIncidentRequest req) {
        Incident incident = get(id);
        LocalDateTime now = LocalDateTime.now(clock);

        if (req.resolutionNotes() != null && !req.resolutionNotes().isBlank()) {
            incident.setResolutionNotes(req.resolutionNotes().trim());
        }
        if (req.severity() != null && req.severity() != incident.getSeverity()) {
            incident.addEvent(EventType.SEVERITY_CHANGED,
                    "Severity changed " + incident.getSeverity() + " -> " + req.severity(), now);
            incident.setSeverity(req.severity());
        }
        if (req.assignee() != null && !Objects.equals(blankToNull(req.assignee()), incident.getAssignee())) {
            String assignee = blankToNull(req.assignee());
            incident.addEvent(EventType.ASSIGNED, assignee == null ? "Unassigned" : "Assigned to " + assignee, now);
            incident.setAssignee(assignee);
        }
        if (req.status() != null && req.status() != incident.getStatus()) {
            changeStatus(incident, req.status(), now);
        }
        return incident;
    }

    private void changeStatus(Incident incident, IncidentStatus target, LocalDateTime now) {
        boolean closing = !target.isActive();
        if (closing && (incident.getResolutionNotes() == null || incident.getResolutionNotes().isBlank())) {
            throw new IllegalArgumentException("Resolution notes are required to mark an incident " + target);
        }
        String message = "Status changed " + incident.getStatus() + " -> " + target;
        if (closing && incident.getResolvedAt() == null) {
            incident.setResolvedAt(now);
            message += ". Resolution: " + incident.getResolutionNotes();
        } else if (!closing) {
            incident.setResolvedAt(null);
        }
        incident.setStatus(target);
        incident.addEvent(EventType.STATUS_CHANGED, message, now);
    }

    public Incident addNote(Long id, String note) {
        Incident incident = get(id);
        incident.addEvent(EventType.NOTE, note.trim(), LocalDateTime.now(clock));
        return incident;
    }

    /**
     * Free-text search covers title, description, resolution notes and every history entry,
     * so past investigations can be found by whatever someone wrote down at the time.
     */
    @Transactional(readOnly = true)
    public Page<IncidentSummaryDto> search(String q, IncidentStatus status, Severity severity, String service,
                                 LocalDateTime from, LocalDateTime to, Pageable pageable) {
        Specification<Incident> spec = (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (status != null) {
                p.add(cb.equal(root.get("status"), status));
            }
            if (severity != null) {
                p.add(cb.equal(root.get("severity"), severity));
            }
            if (from != null) {
                p.add(cb.greaterThanOrEqualTo(root.<LocalDateTime>get("startedAt"), from));
            }
            if (to != null) {
                p.add(cb.lessThanOrEqualTo(root.<LocalDateTime>get("startedAt"), to));
            }
            if (service != null && !service.isBlank()) {
                Join<Incident, String> services = root.join("affectedServices", JoinType.INNER);
                p.add(cb.equal(services, service));
                query.distinct(true);
            }
            if (q != null && !q.isBlank()) {
                String like = "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
                Subquery<Long> inHistory = query.subquery(Long.class);
                var event = inHistory.from(IncidentEvent.class);
                inHistory.select(event.get("incident").get("id"))
                        .where(cb.like(cb.lower(event.<String>get("message")), like));
                p.add(cb.or(
                        cb.like(cb.lower(root.<String>get("title")), like),
                        cb.like(cb.lower(cb.coalesce(root.<String>get("description"), "")), like),
                        cb.like(cb.lower(cb.coalesce(root.<String>get("resolutionNotes"), "")), like),
                        root.get("id").in(inHistory)));
            }
            return cb.and(p.toArray(Predicate[]::new));
        };
        // Map inside the transaction: the summary reads the lazily loaded error group.
        return incidents.findAll(spec, pageable).map(IncidentSummaryDto::from);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
