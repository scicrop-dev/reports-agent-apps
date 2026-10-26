package com.infinitestack.reports.blocks.brgeo;

import static com.infinitestack.reports.blocks.brgeo.BrGeoTables.Q_BIOME;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Which deforestation monitoring products cover an area. DETER and PRODES are published per biome,
 * and a product BR Geo has not loaded says nothing; so "no alert" only means something when a
 * product that covers the area is loaded. This is the rule the BR Geo methodology asks for:
 * "not monitored" is never reported as "no alert".
 *
 * <p>Coverage is approximated by the IBGE biome: DETER Amazonia actually covers the Legal Amazon,
 * which is larger than the Amazonia biome.
 */
final class MonitoringCoverage {

    /** A monitoring product, the biome it covers (IBGE code) and a readable name. */
    record Product(String id, int biomeCode, String name) {}

    private MonitoringCoverage() {}

    /**
     * One row per product: {product, productName, biome, intersectsArea, loaded, lastYear (PRODES only)}.
     * Runs inside the caller's read-only transaction; {@code table} is the qualified product table.
     */
    static List<Map<String, Object>> check(JdbcTemplate jdbc, String geoJson, String table, List<Product> products,
                                           boolean withLastYear) {
        String values = products.stream().map(p -> "('" + p.id() + "', " + p.biomeCode() + ")")
                .collect(Collectors.joining(", "));
        String sql = "WITH " + BrGeoValues.INPUT_CTE + ","
                + " products(product, biome_code) AS (VALUES " + values + "),"
                + " edition AS (SELECT max(edition) AS edition FROM " + Q_BIOME + " WHERE valid_to IS NULL)"
                + " SELECT p.product, b.nm_bioma,"
                + " COALESCE(b.geom && input.geom AND ST_Intersects(b.geom, input.geom), false) AS intersects_area,"
                + " EXISTS (SELECT 1 FROM " + table + " t WHERE t.product = p.product AND t.valid_to IS NULL) AS loaded"
                + (withLastYear ? ", (SELECT max(t.year) FROM " + table + " t WHERE t.product = p.product AND t.valid_to IS NULL) AS last_year" : "")
                + " FROM products p CROSS JOIN input CROSS JOIN edition"
                + " LEFT JOIN " + Q_BIOME + " b ON b.valid_to IS NULL AND b.edition = edition.edition AND b.cd_bioma = p.biome_code"
                + " ORDER BY p.product";
        List<Map<String, Object>> rows = jdbc.queryForList(sql, geoJson);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Product product : products) {
            Map<String, Object> row = rows.stream().filter(r -> product.id().equals(r.get("product"))).findFirst().orElse(Map.of());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("product", product.id());
            item.put("productName", product.name());
            item.put("biome", row.get("nm_bioma"));
            item.put("intersectsArea", Boolean.TRUE.equals(row.get("intersects_area")));
            item.put("loaded", Boolean.TRUE.equals(row.get("loaded")));
            if (withLastYear) item.put("lastYear", row.get("last_year"));
            out.add(item);
        }
        return out;
    }

    /** At least one product that covers the area is loaded. */
    static boolean monitored(List<Map<String, Object>> coverage) {
        return coverage.stream().anyMatch(c -> Boolean.TRUE.equals(c.get("intersectsArea")) && Boolean.TRUE.equals(c.get("loaded")));
    }

    static String nameOf(List<Product> products, String id) {
        return products.stream().filter(p -> p.id().equals(id)).map(Product::name).findFirst().orElse(id);
    }
}
