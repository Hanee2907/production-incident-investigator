package com.production.incidents.web.dto;

import com.production.incidents.domain.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Page;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/** Request and response shapes for the REST API, kept apart from the JPA entities. */
public final class Dtos {

    private Dtos() {
    }

    public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
        public static <E, T> PageResponse<T> of(Page<E> page, Function<E, T> mapper) {
            return new PageResponse<>(page.getContent().stream().map(mapper).toList(),
                    page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
        }
    }

    public record LogEntryDto(Long id, LocalDateTime timestamp, LogLevel level, String service, String logger,
                              String message, String exceptionType, String stackTrace, String sourceFile,
                              Long errorGroupId) {
        public static LogEntryDto from(LogEntry e) {
            return new LogEntryDto(e.getId(), e.getTimestamp(), e.getLevel(), e.getService(), e.getLogger(),
                    e.getMessage(), e.getExceptionType(), e.getStackTrace(), e.getSourceFile(),
                    e.getErrorGroup() == null ? null : e.getErrorGroup().getId());
        }
    }

    public record ErrorGroupDto(Long id, String fingerprint, String exceptionType, String normalizedMessage,
                                String sampleMessage, LogLevel highestLevel, LocalDateTime firstSeen,
                                LocalDateTime lastSeen, long occurrences, Set<String> services,
                                int activeHours, boolean recurring, Long incidentId, IncidentStatus incidentStatus) {
    }

    public record TimelinePoint(LocalDateTime hour, long count) {
    }

    public record ErrorGroupDetail(ErrorGroupDto group, List<TimelinePoint> timeline, List<LogEntryDto> recentEntries) {
    }

    public record IncidentSummaryDto(Long id, String title, Severity severity, IncidentStatus status,
                                     Set<String> affectedServices, String assignee, LocalDateTime startedAt,
                                     LocalDateTime detectedAt, LocalDateTime resolvedAt, int recurrenceCount,
                                     Long errorGroupId, Long occurrences) {
        public static IncidentSummaryDto from(Incident i) {
            ErrorGroup g = i.getErrorGroup();
            return new IncidentSummaryDto(i.getId(), i.getTitle(), i.getSeverity(), i.getStatus(),
                    Set.copyOf(i.getAffectedServices()), i.getAssignee(), i.getStartedAt(), i.getDetectedAt(),
                    i.getResolvedAt(), i.getRecurrenceCount(),
                    g == null ? null : g.getId(), g == null ? null : g.getOccurrences());
        }
    }

    public record IncidentEventDto(Long id, EventType type, String message, LocalDateTime timestamp) {
        public static IncidentEventDto from(IncidentEvent e) {
            return new IncidentEventDto(e.getId(), e.getType(), e.getMessage(), e.getTimestamp());
        }
    }

    public record IncidentDetailDto(IncidentSummaryDto summary, String description, String resolutionNotes,
                                    List<IncidentEventDto> history) {
        public static IncidentDetailDto from(Incident i) {
            return new IncidentDetailDto(IncidentSummaryDto.from(i), i.getDescription(), i.getResolutionNotes(),
                    i.getEvents().stream().map(IncidentEventDto::from).toList());
        }
    }

    public record CreateIncidentRequest(
            @NotBlank @Size(max = 200) String title,
            @Size(max = 4000) String description,
            @NotNull Severity severity,
            Set<@NotBlank String> affectedServices,
            Long errorGroupId,
            LocalDateTime startedAt,
            @Size(max = 120) String assignee) {
    }

    /** Partial update: null fields are left unchanged. */
    public record UpdateIncidentRequest(
            IncidentStatus status,
            Severity severity,
            @Size(max = 120) String assignee,
            @Size(max = 4000) String resolutionNotes) {
    }

    public record NoteRequest(@NotBlank @Size(max = 4000) String note) {
    }

    public record ServiceImpactDto(String service, long errorCount, LocalDateTime firstError,
                                   LocalDateTime lastError, long distinctErrorGroups) {
    }
}
