package com.production.incidents.parsing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.production.incidents.domain.LogLevel;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class LogParserTest {

    private final LogParser parser = new LogParser(new ObjectMapper());

    @Test
    void parsesPlainTextLine() {
        ParseResult result = parser.parse(
                "2026-10-26 08:14:55.731 ERROR [payment-service] c.s.payment.GatewayClient - Payment failed for order 42");

        assertThat(result.entries()).hasSize(1);
        ParsedLog log = result.entries().get(0);
        assertThat(log.timestamp()).isEqualTo(LocalDateTime.of(2026, 10, 26, 8, 14, 55, 731_000_000));
        assertThat(log.level()).isEqualTo(LogLevel.ERROR);
        assertThat(log.service()).isEqualTo("payment-service");
        assertThat(log.logger()).isEqualTo("c.s.payment.GatewayClient");
        assertThat(log.message()).isEqualTo("Payment failed for order 42");
    }

    @Test
    void attachesStackTraceAndPrefersRootCause() {
        String input = """
                2026-10-26 09:05:31.776 ERROR [inventory-service] c.s.StockRepository - Failed to reserve stock
                org.springframework.dao.DataAccessResourceFailureException: Unable to acquire JDBC Connection
                \tat com.shop.inventory.StockRepository.reserve(StockRepository.java:41)
                Caused by: java.sql.SQLTransientConnectionException: HikariPool-1 - Connection is not available
                \t... 14 more
                2026-10-26 09:05:32.010 INFO  [order-service] c.s.order.CheckoutFlow - next event
                """;

        ParseResult result = parser.parse(input);

        assertThat(result.entries()).hasSize(2);
        assertThat(result.skippedLines()).isZero();
        ParsedLog error = result.entries().get(0);
        assertThat(error.exceptionType()).isEqualTo("java.sql.SQLTransientConnectionException");
        assertThat(error.stackTrace()).contains("StockRepository.java:41").contains("... 14 more");
        assertThat(result.entries().get(1).stackTrace()).isNull();
    }

    @Test
    void detectsExceptionTypeInMessageWhenThereIsNoStackTrace() {
        ParsedLog log = parser.parse(
                "2026-10-26 09:00:00 WARN [api] - call failed with java.io.IOException: broken pipe").entries().get(0);

        assertThat(log.exceptionType()).isEqualTo("java.io.IOException");
        assertThat(log.logger()).isNull();
    }

    @Test
    void parsesJsonLinesAndConvertsOffsetsToUtc() {
        String json = """
                {"timestamp":"2026-10-27T04:14:31.004+02:00","level":"error","service":"search-service","message":"Bulk rejected","exception":"org.elasticsearch.client.ResponseException: 429"}
                """;

        ParsedLog log = parser.parse(json).entries().get(0);

        assertThat(log.timestamp()).isEqualTo(LocalDateTime.of(2026, 10, 27, 2, 14, 31, 4_000_000));
        assertThat(log.level()).isEqualTo(LogLevel.ERROR);
        assertThat(log.service()).isEqualTo("search-service");
        assertThat(log.exceptionType()).isEqualTo("org.elasticsearch.client.ResponseException");
    }

    @Test
    void acceptsThreadNameAndLevelAliases() {
        ParsedLog log = parser.parse(
                "2026-10-26T10:00:00Z [http-nio-1] SEVERE [billing] com.x.Job - boom").entries().get(0);

        assertThat(log.level()).isEqualTo(LogLevel.ERROR);
        assertThat(log.service()).isEqualTo("billing");
    }

    @Test
    void countsUnrecognisedLinesAsSkipped() {
        ParseResult result = parser.parse("""
                random banner text
                2026-10-26 10:00:00 INFO [svc] - hello

                another stray line
                """);

        assertThat(result.totalLines()).isEqualTo(3);
        assertThat(result.entries()).hasSize(1);
        assertThat(result.skippedLines()).isEqualTo(2);
    }
}
