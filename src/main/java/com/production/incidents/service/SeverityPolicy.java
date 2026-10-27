package com.production.incidents.service;

import com.production.incidents.domain.ErrorGroup;
import com.production.incidents.domain.LogLevel;
import com.production.incidents.domain.Severity;
import org.springframework.stereotype.Component;

/** Decides how bad an error group is, from its level, volume and blast radius. */
@Component
public class SeverityPolicy {

    public Severity assess(ErrorGroup group) {
        int services = group.getServices().size();
        long count = group.getOccurrences();

        if (group.getHighestLevel() == LogLevel.FATAL || services >= 3 || count >= 50) {
            return Severity.CRITICAL;
        }
        if (services == 2 || count >= 20) {
            return Severity.HIGH;
        }
        if (count >= 5) {
            return Severity.MEDIUM;
        }
        return Severity.LOW;
    }
}
