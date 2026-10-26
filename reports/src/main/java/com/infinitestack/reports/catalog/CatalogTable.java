package com.infinitestack.reports.catalog;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One table of the InfiniteStack data catalog, as returned by the host's internal port
 * {@code GET /api/extensions/internal/catalog/tables} (infinitestack-back#1279). A table listed
 * under several sources comes once per source.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CatalogTable(
        String sourceId,
        String sourceName,
        String sourceType,
        boolean sourceActive,
        String schema,
        String table,
        String status,
        String lastUpdate,
        List<CatalogColumn> columns) {

    public CatalogTable {
        columns = columns == null ? List.of() : List.copyOf(columns);
    }
}
