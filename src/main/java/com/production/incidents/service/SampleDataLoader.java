package com.production.incidents.service;

import com.production.incidents.config.IncidentProperties;
import com.production.incidents.repository.LogEntryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;

/** Loads the bundled sample logs on first start so the dashboard has something to show. */
@Component
public class SampleDataLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SampleDataLoader.class);

    private final IncidentProperties properties;
    private final LogEntryRepository logs;
    private final IngestionService ingestion;

    public SampleDataLoader(IncidentProperties properties, LogEntryRepository logs, IngestionService ingestion) {
        this.properties = properties;
        this.logs = logs;
        this.ingestion = ingestion;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!properties.sampleData().enabled() || logs.count() > 0) {
            return;
        }
        Resource[] files = new PathMatchingResourcePatternResolver().getResources("classpath:sample-logs/*.*");
        Arrays.sort(files, Comparator.comparing(Resource::getFilename));
        for (Resource file : files) {
            try (Reader reader = new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8)) {
                ingestion.ingest(file.getFilename(), reader);
            }
        }
        log.info("Loaded {} sample log file(s)", files.length);
    }
}
