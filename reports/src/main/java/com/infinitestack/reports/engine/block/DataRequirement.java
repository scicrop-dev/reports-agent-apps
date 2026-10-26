package com.infinitestack.reports.engine.block;

import java.time.Duration;
import java.util.List;

/**
 * A piece of catalog data a Block cannot work without: a table, the columns it reads and,
 * optionally, how old the data may be. A Block is offered to users only while every requirement
 * is met by the catalog and an admin has released it (ROADMAP, Phase 3).
 *
 * @param maxStaleness null when any age is acceptable
 */
public record DataRequirement(String schema, String table, List<String> columns, Duration maxStaleness) {

    public DataRequirement {
        columns = columns == null ? List.of() : List.copyOf(columns);
    }

    public static DataRequirement table(String schema, String table, String... columns) {
        return new DataRequirement(schema, table, List.of(columns), null);
    }

    public DataRequirement freshWithin(Duration maxAge) {
        return new DataRequirement(schema, table, columns, maxAge);
    }

    public String qualifiedName() {
        return schema + "." + table;
    }
}
