package com.production.incidents.web;

import com.production.incidents.domain.LogLevel;
import com.production.incidents.service.IngestionResult;
import com.production.incidents.service.IngestionService;
import com.production.incidents.service.LogSearchService;
import com.production.incidents.web.dto.Dtos.LogEntryDto;
import com.production.incidents.web.dto.Dtos.PageResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/logs")
public class LogController {

    private final IngestionService ingestion;
    private final LogSearchService search;

    public LogController(IngestionService ingestion, LogSearchService search) {
        this.ingestion = ingestion;
        this.search = search;
    }

    @PostMapping(value = "/ingest", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public IngestionResult ingestFile(@RequestParam("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("Uploaded file is empty");
        }
        try (Reader reader = new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8)) {
            return ingestion.ingest(file.getOriginalFilename(), reader);
        }
    }

    @PostMapping(value = "/ingest", consumes = MediaType.TEXT_PLAIN_VALUE)
    public IngestionResult ingestText(@RequestBody String content,
                                      @RequestParam(defaultValue = "pasted") String source) {
        return ingestion.ingest(source, content);
    }

    @GetMapping
    public PageResponse<LogEntryDto> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String service,
            @RequestParam(required = false) LogLevel minLevel,
            @RequestParam(required = false) Long groupId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        var pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 500), Sort.by(Sort.Direction.DESC, "timestamp", "id"));
        return PageResponse.of(search.search(q, service, minLevel, groupId, from, to, pageable), e -> e);
    }

    @GetMapping("/services")
    public List<String> services() {
        return search.services();
    }
}
