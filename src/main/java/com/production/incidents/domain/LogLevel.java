package com.production.incidents.domain;

import java.util.Locale;

public enum LogLevel {
    TRACE, DEBUG, INFO, WARN, ERROR, FATAL;

    public boolean isError() {
        return this == ERROR || this == FATAL;
    }

    /** Accepts common aliases emitted by different logging frameworks. */
    public static LogLevel parse(String raw) {
        if (raw == null) {
            return INFO;
        }
        return switch (raw.trim().toUpperCase(Locale.ROOT)) {
            case "TRACE", "FINEST", "FINER" -> TRACE;
            case "DEBUG", "FINE" -> DEBUG;
            case "WARN", "WARNING" -> WARN;
            case "ERROR", "ERR", "SEVERE" -> ERROR;
            case "FATAL", "CRITICAL", "CRIT" -> FATAL;
            default -> INFO;
        };
    }
}
