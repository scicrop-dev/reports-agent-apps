package com.infinitestack.reports.catalog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Fallback for local development, when there is no host to ask: treats every table of the
 * bronze/silver/gold schemas of the database as catalogued. It knows nothing about freshness, so
 * {@code lastUpdate} is always null.
 */
public class InformationSchemaCatalogClient implements CatalogClient {

    private final JdbcTemplate jdbc;

    public InformationSchemaCatalogClient(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<CatalogTable> fetchTables() {
        Map<String, List<CatalogColumn>> columns = new LinkedHashMap<>();
        jdbc.query("""
                SELECT table_schema, table_name, column_name, data_type
                  FROM information_schema.columns
                 WHERE table_schema IN ('bronze', 'silver', 'gold')
                 ORDER BY table_schema, table_name, ordinal_position
                """, rs -> {
            String key = rs.getString("table_schema") + "." + rs.getString("table_name");
            columns.computeIfAbsent(key, k -> new ArrayList<>())
                    .add(new CatalogColumn(rs.getString("column_name"), rs.getString("data_type"), null, null));
        });
        List<CatalogTable> tables = new ArrayList<>();
        columns.forEach((key, cols) -> {
            int dot = key.indexOf('.');
            tables.add(new CatalogTable("information_schema", "Local database", "database", true,
                    key.substring(0, dot), key.substring(dot + 1), null, null, cols));
        });
        return tables;
    }

    @Override
    public String origin() {
        return "information_schema";
    }
}
