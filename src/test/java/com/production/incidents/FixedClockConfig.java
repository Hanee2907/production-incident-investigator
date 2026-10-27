package com.production.incidents;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/** Freezes "now" so tests can place log timestamps before and after a resolution. */
@TestConfiguration
public class FixedClockConfig {

    public static final Instant NOW = Instant.parse("2026-10-26T12:00:00Z");

    @Bean
    @Primary
    public Clock fixedClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }
}
