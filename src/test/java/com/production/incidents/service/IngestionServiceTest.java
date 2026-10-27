package com.production.incidents.service;

import com.production.incidents.FixedClockConfig;
import com.production.incidents.domain.*;
import com.production.incidents.repository.ErrorGroupRepository;
import com.production.incidents.repository.IncidentRepository;
import com.production.incidents.repository.LogEntryRepository;
import com.production.incidents.web.dto.Dtos.ErrorGroupDto;
import com.production.incidents.web.dto.Dtos.UpdateIncidentRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(FixedClockConfig.class)
class IngestionServiceTest {

    private static final String TIMEOUTS = """
            2026-10-26 08:14:55 ERROR [payment-service] c.s.Gateway - Payment failed for order 1001 after 3000 ms
            java.net.SocketTimeoutException: Read timed out
            2026-10-26 08:21:03 ERROR [payment-service] c.s.Gateway - Payment failed for order 1002 after 3000 ms
            java.net.SocketTimeoutException: Read timed out
            2026-10-26 09:29:47 ERROR [checkout-service] c.s.Gateway - Payment failed for order 1003 after 3000 ms
            java.net.SocketTimeoutException: Read timed out
            2026-10-26 09:30:00 INFO [payment-service] c.s.Gateway - recovered
            """;

    @Autowired IngestionService ingestion;
    @Autowired IncidentService incidentService;
    @Autowired ErrorGroupService errorGroups;
    @Autowired ReportService reports;
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
    void groupsSimilarErrorsAndOpensIncidentAtThreshold() {
        IngestionResult result = ingestion.ingest("app.log", TIMEOUTS);

        assertThat(result.entriesStored()).isEqualTo(4);
        assertThat(result.errorsFound()).isEqualTo(3);
        assertThat(result.newErrorGroups()).isEqualTo(1);
        assertThat(result.incidentsOpened()).hasSize(1);

        ErrorGroupDto group = errorGroups.list(null, false).get(0);
        assertThat(group.occurrences()).isEqualTo(3);
        assertThat(group.services()).containsExactlyInAnyOrder("payment-service", "checkout-service");
        assertThat(group.firstSeen()).hasToString("2026-10-26T08:14:55");
        assertThat(group.activeHours()).isEqualTo(2);
        assertThat(group.recurring()).isTrue();

        Incident incident = incidentService.get(result.incidentsOpened().get(0));
        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.OPEN);
        assertThat(incident.getSeverity()).isEqualTo(Severity.HIGH); // two services affected
        assertThat(incident.getStartedAt()).isEqualTo(group.firstSeen());
        assertThat(incident.getAffectedServices()).containsExactlyInAnyOrder("payment-service", "checkout-service");
    }

    @Test
    void staysQuietBelowThreshold() {
        IngestionResult result = ingestion.ingest("app.log", """
                2026-10-26 08:00:00 ERROR [mailer] c.s.Mail - SMTP connect failed to smtp.internal port 587
                2026-10-26 08:05:00 ERROR [mailer] c.s.Mail - SMTP connect failed to smtp.internal port 587
                """);

        assertThat(result.newErrorGroups()).isEqualTo(1);
        assertThat(result.incidentsOpened()).isEmpty();
    }

    @Test
    void fatalErrorOpensCriticalIncidentImmediately() {
        IngestionResult result = ingestion.ingest("app.log", """
                2026-10-26 23:58:59 FATAL [order-service] c.s.Job - Worker crashed
                java.lang.OutOfMemoryError: Java heap space
                """);

        assertThat(result.incidentsOpened()).hasSize(1);
        assertThat(incidentService.get(result.incidentsOpened().get(0)).getSeverity()).isEqualTo(Severity.CRITICAL);
    }

    @Test
    void addsNewOccurrencesToTheOpenIncidentInsteadOfDuplicating() {
        Long id = ingestion.ingest("a.log", TIMEOUTS).incidentsOpened().get(0);

        IngestionResult second = ingestion.ingest("b.log", """
                2026-10-26 10:00:00 ERROR [refund-service] c.s.Gateway - Payment failed for order 2001 after 3000 ms
                java.net.SocketTimeoutException: Read timed out
                """);

        assertThat(second.incidentsOpened()).isEmpty();
        assertThat(incidents.count()).isEqualTo(1);
        Incident incident = incidentService.get(id);
        assertThat(incident.getAffectedServices()).contains("refund-service");
        assertThat(incident.getSeverity()).isEqualTo(Severity.CRITICAL); // now three services
        assertThat(incident.getEvents()).extracting(IncidentEvent::getType)
                .contains(EventType.NEW_OCCURRENCES, EventType.SEVERITY_CHANGED);
    }

    @Test
    void reopensResolvedIncidentWhenFailureRecurs() {
        Long id = ingestion.ingest("a.log", TIMEOUTS).incidentsOpened().get(0);
        incidentService.update(id, new UpdateIncidentRequest(IncidentStatus.RESOLVED, null, "sam",
                "Raised gateway timeout to 10 s"));
        assertThat(incidentService.get(id).getResolvedAt()).isNotNull();

        // Clock is frozen at 12:00, so 13:00 is after the resolution.
        IngestionResult later = ingestion.ingest("later.log", """
                2026-10-26 13:00:00 ERROR [payment-service] c.s.Gateway - Payment failed for order 3001 after 3000 ms
                java.net.SocketTimeoutException: Read timed out
                """);

        assertThat(later.incidentsReopened()).containsExactly(id);
        Incident incident = incidentService.get(id);
        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.OPEN);
        assertThat(incident.getRecurrenceCount()).isEqualTo(1);
        assertThat(incident.getResolvedAt()).isNull();
        assertThat(incident.getEvents()).extracting(IncidentEvent::getType).contains(EventType.RECURRED);
    }

    @Test
    void resolvingRequiresNotes() {
        Long id = ingestion.ingest("a.log", TIMEOUTS).incidentsOpened().get(0);

        assertThatThrownBy(() -> incidentService.update(id, new UpdateIncidentRequest(IncidentStatus.RESOLVED, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Resolution notes");
    }

    @Test
    void summaryReportsServiceImpactAndFirstError() {
        ingestion.ingest("a.log", TIMEOUTS);

        ReportService.Summary s = reports.summary(null, null);

        assertThat(s.totalLogEntries()).isEqualTo(4);
        assertThat(s.totalErrors()).isEqualTo(3);
        assertThat(s.errorGroups()).isEqualTo(1);
        assertThat(s.firstErrorAt()).hasToString("2026-10-26T08:14:55");
        assertThat(s.incidentsByStatus().get(IncidentStatus.OPEN)).isEqualTo(1);
        assertThat(s.serviceImpact()).extracting(i -> i.service())
                .containsExactly("payment-service", "checkout-service"); // ordered by when errors began
        assertThat(reports.markdown(null, null)).contains("# Production Incident Summary", "payment-service");
    }

    @Test
    void searchFindsIncidentsByHistoryText() {
        Long id = ingestion.ingest("a.log", TIMEOUTS).incidentsOpened().get(0);
        incidentService.addNote(id, "Suspect the new TLS proxy in front of the gateway");

        List<Long> hits = incidentService.search("tls proxy", null, null, null, null, null,
                org.springframework.data.domain.Pageable.unpaged()).map(i -> i.id()).getContent();

        assertThat(hits).containsExactly(id);
        assertThat(incidentService.search("nothing like this", null, null, null, null, null,
                org.springframework.data.domain.Pageable.unpaged()).getTotalElements()).isZero();
        assertThat(incidentService.search(null, IncidentStatus.OPEN, null, "checkout-service", null, null,
                org.springframework.data.domain.Pageable.unpaged()).getTotalElements()).isEqualTo(1);
    }
}
