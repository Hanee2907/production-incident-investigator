package com.production.incidents.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class ClockConfig {

    /** UTC, matching how log timestamps are stored. Tests can replace this bean to freeze time. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
