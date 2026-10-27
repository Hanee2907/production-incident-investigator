package com.production.incidents.parsing;

import java.util.List;

/**
 * @param entries      log events recognised in the input (stack trace lines are folded into their event)
 * @param totalLines   non-blank lines read
 * @param skippedLines lines that were neither a log event nor a continuation of one
 */
public record ParseResult(List<ParsedLog> entries, int totalLines, int skippedLines) {
}
