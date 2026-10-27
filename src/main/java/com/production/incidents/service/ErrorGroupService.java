package com.production.incidents.service;

import com.production.incidents.config.IncidentProperties;
import com.production.incidents.domain.ErrorGroup;
import com.production.incidents.domain.Incident;
import com.production.incidents.repository.ErrorGroupRepository;
import com.production.incidents.repository.IncidentRepository;
import com.production.incidents.repository.LogEntryRepository;
import com.production.incidents.web.dto.Dtos.ErrorGroupDetail;
import com.production.incidents.web.dto.Dtos.ErrorGroupDto;
import com.production.incidents.web.dto.Dtos.LogEntryDto;
import com.production.incidents.web.dto.Dtos.TimelinePoint;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
@Transactional(readOnly = true)
public class ErrorGroupService {

    private final ErrorGroupRepository groups;
    private final LogEntryRepository logs;
    private final IncidentRepository incidents;
    private final IncidentProperties properties;

    public ErrorGroupService(ErrorGroupRepository groups, LogEntryRepository logs,
                             IncidentRepository incidents, IncidentProperties properties) {
        this.groups = groups;
        this.logs = logs;
        this.incidents = incidents;
        this.properties = properties;
    }

    public List<ErrorGroupDto> list(String service, boolean recurringOnly) {
        Map<Long, Integer> hours = activeHoursByGroup();
        return groups.findAllByOrderByOccurrencesDesc().stream()
                .filter(g -> service == null || service.isBlank() || g.getServices().contains(service))
                .map(g -> toDto(g, hours.getOrDefault(g.getId(), 0)))
                .filter(dto -> !recurringOnly || dto.recurring())
                .toList();
    }

    public ErrorGroupDetail detail(Long id) {
        ErrorGroup group = groups.findById(id).orElseThrow(() -> new NotFoundException("Error group", id));
        List<TimelinePoint> timeline = logs.hourlyCountsForGroup(id).stream()
                .map(r -> new TimelinePoint(hour(r),((Number) r[4]).longValue()))
                .toList();
        List<LogEntryDto> recent = logs.findByErrorGroupIdOrderByTimestampDesc(id, PageRequest.of(0, 25)).stream()
                .map(LogEntryDto::from)
                .toList();
        return new ErrorGroupDetail(toDto(group, timeline.size()), timeline, recent);
    }

    /** Number of distinct clock hours each group has fired in; a group that keeps coming back is "recurring". */
    Map<Long, Integer> activeHoursByGroup() {
        Map<Long, Integer> result = new HashMap<>();
        for (Object[] row : logs.groupHourBuckets()) {
            result.merge(((Number) row[0]).longValue(), 1, Integer::sum);
        }
        return result;
    }

    ErrorGroupDto toDto(ErrorGroup g, int activeHours) {
        Optional<Incident> incident = incidents.findFirstByErrorGroupIdOrderByDetectedAtDescIdDesc(g.getId());
        boolean recurring = activeHours >= properties.recurringMinHours()
                || incident.map(i -> i.getRecurrenceCount() > 0).orElse(false);
        return new ErrorGroupDto(g.getId(), g.getFingerprint(), g.getExceptionType(), g.getNormalizedMessage(),
                g.getSampleMessage(), g.getHighestLevel(), g.getFirstSeen(), g.getLastSeen(), g.getOccurrences(),
                Set.copyOf(g.getServices()), activeHours, recurring,
                incident.map(Incident::getId).orElse(null), incident.map(Incident::getStatus).orElse(null));
    }

    /** Row columns 0..3 hold year, month, day, hour. */
    private static LocalDateTime hour(Object[] row) {
        return LocalDateTime.of(((Number) row[0]).intValue(), ((Number) row[1]).intValue(),
                ((Number) row[2]).intValue(), ((Number) row[3]).intValue(), 0);
    }
}
