package com.infinitestack.reports.catalog;

import java.time.Instant;
import java.util.List;

/** The catalog as read at {@code fetchedAt}. {@code error} is set when the last read failed. */
public record CatalogSnapshot(String origin, Instant fetchedAt, List<CatalogTable> tables, String error) {

    public CatalogSnapshot {
        tables = tables == null ? List.of() : List.copyOf(tables);
    }
}
