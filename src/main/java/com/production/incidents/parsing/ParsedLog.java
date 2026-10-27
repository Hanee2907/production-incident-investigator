package com.production.incidents.parsing;

import com.production.incidents.domain.LogLevel;

import java.time.LocalDateTime;

public record ParsedLog(
        LocalDateTime timestamp,
        LogLevel level,
        String service,
        String logger,
        String message,
        String exceptionType,
        String stackTrace) {
}
