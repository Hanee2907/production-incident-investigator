package com.production.incidents.service;

import java.util.List;

public record IngestionResult(
        String source,
        int linesRead,
        int linesSkipped,
        int entriesStored,
        int errorsFound,
        int newErrorGroups,
        List<Long> incidentsOpened,
        List<Long> incidentsReopened) {
}
