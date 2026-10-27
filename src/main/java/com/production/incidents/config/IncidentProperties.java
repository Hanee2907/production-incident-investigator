package com.production.incidents.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "incident")
public record IncidentProperties(
        @DefaultValue("3") int autoOpenThreshold,
        @DefaultValue("2") int recurringMinHours,
        @DefaultValue SampleData sampleData) {

    public record SampleData(@DefaultValue("true") boolean enabled) {
    }
}
