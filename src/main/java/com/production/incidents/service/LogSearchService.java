package com.production.incidents.service;

import com.production.incidents.domain.LogEntry;
import com.production.incidents.domain.LogLevel;
import com.production.incidents.repository.LogEntryRepository;
import com.production.incidents.web.dto.Dtos.LogEntryDto;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
@Transactional(readOnly = true)
public class LogSearchService {

    private final LogEntryRepository logs;

    public LogSearchService(LogEntryRepository logs) {
        this.logs = logs;
    }

    public Page<LogEntryDto> search(String q, String service, LogLevel minLevel, Long groupId,
                                    LocalDateTime from, LocalDateTime to, Pageable pageable) {
        Specification<LogEntry> spec = (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (service != null && !service.isBlank()) {
                p.add(cb.equal(root.get("service"), service));
            }
            if (minLevel != null) {
                List<LogLevel> levels = new ArrayList<>();
                for (LogLevel l : LogLevel.values()) {
                    if (l.ordinal() >= minLevel.ordinal()) {
                        levels.add(l);
                    }
                }
                p.add(root.get("level").in(levels));
            }
            if (groupId != null) {
                p.add(cb.equal(root.get("errorGroup").get("id"), groupId));
            }
            if (from != null) {
                p.add(cb.greaterThanOrEqualTo(root.<LocalDateTime>get("timestamp"), from));
            }
            if (to != null) {
                p.add(cb.lessThanOrEqualTo(root.<LocalDateTime>get("timestamp"), to));
            }
            if (q != null && !q.isBlank()) {
                String like = "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
                p.add(cb.or(
                        cb.like(cb.lower(root.<String>get("message")), like),
                        cb.like(cb.lower(cb.coalesce(root.<String>get("exceptionType"), "")), like)));
            }
            return cb.and(p.toArray(Predicate[]::new));
        };
        return logs.findAll(spec, pageable).map(LogEntryDto::from);
    }

    public List<String> services() {
        return logs.distinctServices();
    }
}
