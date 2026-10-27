package com.production.incidents.parsing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.production.incidents.domain.LogLevel;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses application logs in two formats:
 * <ul>
 *   <li>Plain text: {@code 2026-10-27 08:15:02.123 ERROR [payment-service] c.p.GatewayClient - message}
 *       (thread names in brackets and an ISO {@code T}/offset are also accepted)</li>
 *   <li>JSON lines: {@code {"timestamp": "...", "level": "ERROR", "service": "...", "message": "..."}}</li>
 * </ul>
 * Indented lines, {@code at ...} frames and {@code Caused by:} lines are attached to the preceding event.
 */
@Component
public class LogParser {

    private static final Pattern TEXT_LINE = Pattern.compile(
            "^(?<ts>\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d{1,9})?(?:Z|[+-]\\d{2}:?\\d{2})?)\\s+"
                    + "(?:\\[(?<thread>[^\\]]*)]\\s+)?"
                    + "(?<level>TRACE|DEBUG|INFO|WARN|WARNING|ERROR|FATAL|SEVERE|CRITICAL)\\s+"
                    + "\\[(?<service>[^\\]]+)]\\s+"
                    + "(?:(?<logger>[\\w.$]+)\\s+-\\s+)?"
                    + "(?<msg>.*)$");

    private static final Pattern EXCEPTION = Pattern.compile(
            "\\b((?:[a-zA-Z_$][\\w$]*\\.)+[A-Z][\\w$]*(?:Exception|Error|Throwable))\\b");

    private static final Pattern CAUSED_BY = Pattern.compile("^\\s*Caused by:\\s*([\\w.$]+)");

    private final ObjectMapper objectMapper;

    public LogParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ParseResult parse(String content) {
        return parse(new StringReader(content));
    }

    public ParseResult parse(Reader reader) {
        List<ParsedLog> entries = new ArrayList<>();
        Builder current = null;
        int total = 0;
        int skipped = 0;

        try (BufferedReader in = new BufferedReader(reader)) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                total++;

                Builder next = parseEventLine(line);
                if (next != null) {
                    if (current != null) {
                        entries.add(current.build());
                    }
                    current = next;
                } else if (current != null && isContinuation(line)) {
                    current.appendStackLine(line);
                } else {
                    skipped++;
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read log input", e);
        }

        if (current != null) {
            entries.add(current.build());
        }
        return new ParseResult(entries, total, skipped);
    }

    private Builder parseEventLine(String line) {
        String trimmed = line.trim();
        if (trimmed.startsWith("{")) {
            return parseJson(trimmed);
        }
        Matcher m = TEXT_LINE.matcher(line);
        if (!m.matches()) {
            return null;
        }
        LocalDateTime ts = parseTimestamp(m.group("ts"));
        if (ts == null) {
            return null;
        }
        return new Builder(ts, LogLevel.parse(m.group("level")), m.group("service").trim(),
                m.group("logger"), m.group("msg").trim());
    }

    private Builder parseJson(String line) {
        try {
            JsonNode node = objectMapper.readTree(line);
            LocalDateTime ts = parseTimestamp(text(node, "timestamp", "@timestamp", "time", "ts"));
            String message = text(node, "message", "msg");
            if (ts == null || message == null) {
                return null;
            }
            String service = text(node, "service", "app", "application", "serviceName");
            Builder b = new Builder(ts, LogLevel.parse(text(node, "level", "severity")),
                    service == null ? "unknown" : service, text(node, "logger", "logger_name"), message);
            String exception = text(node, "exception", "error", "stack_trace", "stackTrace");
            if (exception != null) {
                exception.lines().forEach(b::appendStackLine);
            }
            return b;
        } catch (IOException e) {
            return null;
        }
    }

    private static String text(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode v = node.get(name);
            if (v != null && !v.isNull() && !v.asText().isBlank()) {
                return v.asText();
            }
        }
        return null;
    }

    static LocalDateTime parseTimestamp(String raw) {
        if (raw == null) {
            return null;
        }
        String iso = raw.trim().replace(' ', 'T').replace(',', '.');
        try {
            if (iso.endsWith("Z") || iso.matches(".*[+-]\\d{2}:?\\d{2}$")) {
                // Store everything as UTC so logs from different hosts line up.
                String normalized = iso.replaceAll("([+-]\\d{2})(\\d{2})$", "$1:$2");
                return OffsetDateTime.parse(normalized).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
            }
            return LocalDateTime.parse(iso);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static boolean isContinuation(String line) {
        return Character.isWhitespace(line.charAt(0))
                || line.startsWith("at ")
                || line.startsWith("Caused by:")
                || line.startsWith("...")
                || EXCEPTION.matcher(line).lookingAt();
    }

    private static final class Builder {
        private final LocalDateTime timestamp;
        private final LogLevel level;
        private final String service;
        private final String logger;
        private final String message;
        private final StringBuilder stack = new StringBuilder();

        Builder(LocalDateTime timestamp, LogLevel level, String service, String logger, String message) {
            this.timestamp = timestamp;
            this.level = level;
            this.service = service;
            this.logger = logger;
            this.message = message;
        }

        void appendStackLine(String line) {
            if (!stack.isEmpty()) {
                stack.append('\n');
            }
            stack.append(line);
        }

        ParsedLog build() {
            String stackTrace = stack.isEmpty() ? null : stack.toString();
            return new ParsedLog(timestamp, level, service, logger, message,
                    detectExceptionType(message, stackTrace), stackTrace);
        }

        /** Prefers the root cause, since that is what similar failures have in common. */
        private static String detectExceptionType(String message, String stackTrace) {
            if (stackTrace != null) {
                String rootCause = null;
                for (String line : stackTrace.split("\n")) {
                    Matcher c = CAUSED_BY.matcher(line);
                    if (c.find()) {
                        rootCause = c.group(1).replaceAll(":$", "");
                    }
                }
                if (rootCause != null) {
                    return rootCause;
                }
                Matcher first = EXCEPTION.matcher(stackTrace);
                if (first.find()) {
                    return first.group(1);
                }
            }
            Matcher inMessage = EXCEPTION.matcher(message);
            return inMessage.find() ? inMessage.group(1) : null;
        }
    }
}
