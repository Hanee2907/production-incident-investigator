package com.production.incidents.web;

import com.production.incidents.domain.IncidentStatus;
import com.production.incidents.domain.Severity;
import com.production.incidents.service.IncidentService;
import com.production.incidents.web.dto.Dtos.*;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/incidents")
public class IncidentController {

    private final IncidentService incidents;

    public IncidentController(IncidentService incidents) {
        this.incidents = incidents;
    }

    @GetMapping
    public PageResponse<IncidentSummaryDto> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) IncidentStatus status,
            @RequestParam(required = false) Severity severity,
            @RequestParam(required = false) String service,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        var pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 200), Sort.by(Sort.Direction.DESC, "startedAt", "id"));
        return PageResponse.of(incidents.search(q, status, severity, service, from, to, pageable), i -> i);
    }

    @GetMapping("/{id}")
    public IncidentDetailDto get(@PathVariable Long id) {
        return IncidentDetailDto.from(incidents.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public IncidentDetailDto create(@Valid @RequestBody CreateIncidentRequest request) {
        return IncidentDetailDto.from(incidents.create(request));
    }

    @PatchMapping("/{id}")
    public IncidentDetailDto update(@PathVariable Long id, @Valid @RequestBody UpdateIncidentRequest request) {
        return IncidentDetailDto.from(incidents.update(id, request));
    }

    @PostMapping("/{id}/notes")
    public IncidentDetailDto addNote(@PathVariable Long id, @Valid @RequestBody NoteRequest request) {
        return IncidentDetailDto.from(incidents.addNote(id, request.note()));
    }
}
