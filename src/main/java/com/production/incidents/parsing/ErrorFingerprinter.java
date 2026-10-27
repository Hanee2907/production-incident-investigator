package com.production.incidents.parsing;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Turns an error message into a stable signature by masking the parts that vary between
 * occurrences (ids, numbers, addresses, quoted values), so that the same failure groups together.
 */
@Component
public class ErrorFingerprinter {

    private record Rule(Pattern pattern, String replacement) {
    }

    // Order matters: specific shapes first, so a UUID is not chewed up by the number rule.
    private static final List<Rule> RULES = List.of(
            new Rule(Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"), "<uuid>"),
            new Rule(Pattern.compile("https?://\\S+"), "<url>"),
            new Rule(Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.]+"), "<email>"),
            new Rule(Pattern.compile("\\b\\d{1,3}(?:\\.\\d{1,3}){3}(?::\\d+)?\\b"), "<ip>"),
            new Rule(Pattern.compile("\\b0x[0-9a-fA-F]+\\b"), "<hex>"),
            new Rule(Pattern.compile("\\b(?=[0-9a-fA-F]*\\d)(?=[0-9a-fA-F]*[a-fA-F])[0-9a-fA-F]{12,}\\b"), "<hex>"),
            new Rule(Pattern.compile("'[^']*'"), "<str>"),
            new Rule(Pattern.compile("\"[^\"]*\""), "<str>"),
            new Rule(Pattern.compile("\\d+(?:\\.\\d+)?"), "<num>"),
            new Rule(Pattern.compile("\\s+"), " "));

    public String normalize(String message) {
        if (message == null) {
            return "";
        }
        String result = message;
        for (Rule rule : RULES) {
            result = rule.pattern().matcher(result).replaceAll(rule.replacement());
        }
        return result.trim();
    }

    /**
     * The service name is deliberately left out: the same failure surfacing in several services
     * is one problem, and the group then shows every service it touched.
     */
    public String fingerprint(String exceptionType, String normalizedMessage) {
        String key = (exceptionType == null ? "" : exceptionType) + "|" + normalizedMessage;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
