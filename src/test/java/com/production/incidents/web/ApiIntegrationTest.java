package com.production.incidents.web;

import com.production.incidents.FixedClockConfig;
import com.production.incidents.repository.ErrorGroupRepository;
import com.production.incidents.repository.IncidentRepository;
import com.production.incidents.repository.LogEntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(FixedClockConfig.class)
class ApiIntegrationTest {

    private static final String LOG = """
            2026-10-26 08:00:00 ERROR [inventory-service] c.s.Stock - Failed to reserve stock for SKU 'A-1'
            Caused by: java.sql.SQLTransientConnectionException: pool exhausted
            2026-10-26 08:01:00 ERROR [inventory-service] c.s.Stock - Failed to reserve stock for SKU 'B-2'
            Caused by: java.sql.SQLTransientConnectionException: pool exhausted
            2026-10-26 08:02:00 ERROR [inventory-service] c.s.Stock - Failed to reserve stock for SKU 'C-3'
            Caused by: java.sql.SQLTransientConnectionException: pool exhausted
            """;

    @Autowired MockMvc mvc;
    @Autowired LogEntryRepository logs;
    @Autowired ErrorGroupRepository groups;
    @Autowired IncidentRepository incidents;

    @BeforeEach
    void clean() {
        incidents.deleteAll();
        logs.deleteAll();
        groups.deleteAll();
    }

    @Test
    void uploadThenInvestigateThroughTheApi() throws Exception {
        mvc.perform(multipart("/api/logs/ingest").file(new MockMultipartFile("file", "inv.log", "text/plain", LOG.getBytes())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("inv.log"))
                .andExpect(jsonPath("$.errorsFound").value(3))
                .andExpect(jsonPath("$.incidentsOpened", hasSize(1)));

        mvc.perform(get("/api/error-groups"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].exceptionType").value("java.sql.SQLTransientConnectionException"))
                .andExpect(jsonPath("$[0].normalizedMessage").value("Failed to reserve stock for SKU <str>"))
                .andExpect(jsonPath("$[0].occurrences").value(3));

        Long incidentId = incidents.findAll().get(0).getId();

        mvc.perform(patch("/api/incidents/{id}", incidentId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"RESOLVED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("Resolution notes")));

        mvc.perform(patch("/api/incidents/{id}", incidentId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"RESOLVED\",\"resolutionNotes\":\"Pool size raised to 25\",\"assignee\":\"priya\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.status").value("RESOLVED"))
                .andExpect(jsonPath("$.summary.assignee").value("priya"))
                .andExpect(jsonPath("$.history[*].type", hasItems("CREATED", "ASSIGNED", "STATUS_CHANGED")));

        mvc.perform(get("/api/incidents").param("q", "pool size"))
                .andExpect(jsonPath("$.totalElements").value(1));

        mvc.perform(get("/api/logs").param("service", "inventory-service").param("minLevel", "ERROR"))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content[0].stackTrace", containsString("pool exhausted")));

        mvc.perform(get("/api/reports/summary"))
                .andExpect(jsonPath("$.incidentsByStatus.RESOLVED").value(1))
                .andExpect(jsonPath("$.serviceImpact[0].service").value("inventory-service"))
                .andExpect(jsonPath("$.serviceImpact[0].firstError").value("2026-10-26T08:00:00"));

        mvc.perform(get("/api/reports/summary.md"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("| inventory-service | 3 |")));
    }

    @Test
    void acceptsPastedText() throws Exception {
        mvc.perform(post("/api/logs/ingest").param("source", "clipboard").contentType(MediaType.TEXT_PLAIN).content(LOG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("clipboard"))
                .andExpect(jsonPath("$.entriesStored").value(3));
    }

    @Test
    void validatesManualIncidentAndReportsMissingOnes() throws Exception {
        mvc.perform(post("/api/incidents").contentType(MediaType.APPLICATION_JSON).content("{\"severity\":\"LOW\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("title")));

        mvc.perform(post("/api/incidents").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Login page slow\",\"severity\":\"LOW\",\"affectedServices\":[\"web\"]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.summary.status").value("OPEN"))
                .andExpect(jsonPath("$.summary.affectedServices[0]").value("web"));

        mvc.perform(get("/api/incidents/{id}", 999_999)).andExpect(status().isNotFound());
    }
}
