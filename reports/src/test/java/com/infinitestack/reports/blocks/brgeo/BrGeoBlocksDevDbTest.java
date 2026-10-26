package com.infinitestack.reports.blocks.brgeo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.infinitestack.reports.blocks.car.CarPropertyGeometryBlock;
import com.infinitestack.reports.engine.block.BlockPort;
import com.infinitestack.reports.engine.block.BlockQueries;
import com.infinitestack.reports.engine.block.ReportBlock;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Runs the BR Geo Blocks against the shared dev database, chained the way a Flow does: the geometry
 * comes from {@code car-property-geometry}. Skipped unless {@code CAR_TEST_DB_URL} is set (with
 * {@code CAR_TEST_DB_USER} / {@code CAR_TEST_DB_PASSWORD}). Read-only, one property at a time.
 */
class BrGeoBlocksDevDbTest {

    /** AC, Amazonia biome, with IBAMA embargoes, DETER alerts and PRODES increments (dev data of 2026-10-26). */
    static final String AMAZON_PROPERTY = "AC-1200302-7ECA40E36E154380BC739F9CB7E33DE0";

    static HikariDataSource dataSource;
    static BlockQueries queries;
    static Map<String, Object> geometry;

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void connect() throws Exception {
        String url = System.getenv("CAR_TEST_DB_URL");
        assumeTrue(url != null && !url.isBlank(), "CAR_TEST_DB_URL not set");
        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(url);
        dataSource.setUsername(System.getenv("CAR_TEST_DB_USER"));
        dataSource.setPassword(System.getenv("CAR_TEST_DB_PASSWORD"));
        dataSource.setReadOnly(true);
        dataSource.setMaximumPoolSize(2);
        queries = new BlockQueries(dataSource);
        geometry = (Map<String, Object>) new CarPropertyGeometryBlock(queries)
                .execute(Map.of("codImovel", AMAZON_PROPERTY), null).get("geometry");
    }

    @AfterAll
    static void close() {
        if (dataSource != null) dataSource.close();
    }

    /** Runs a Block and checks its outputs the way the executor does. */
    static Map<String, Object> run(ReportBlock block, Map<String, Object> inputs) throws Exception {
        long start = System.currentTimeMillis();
        Map<String, Object> out = block.execute(inputs, null);
        for (BlockPort port : block.definition().outputs()) {
            Object value = out.get(port.name());
            if (port.required()) assertNotNull(value, block.definition().id() + "." + port.name());
            port.type().coerce(value);
        }
        assertEquals(block.definition().outputs().size(), out.size(), "only declared outputs");
        System.out.println(block.definition().id() + " (" + (System.currentTimeMillis() - start) + " ms): " + out);
        return out;
    }

    static Map<String, Object> area() {
        Map<String, Object> inputs = new HashMap<>();
        inputs.put("geometry", geometry);
        return inputs;
    }

    static double num(Object value) {
        return ((Number) value).doubleValue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void municipalities() throws Exception {
        Map<String, Object> out = run(new BrGeoMunicipalitiesBlock(queries), area());
        assertTrue(num(out.get("count")) >= 1);
        Map<String, Object> main = (Map<String, Object>) out.get("main");
        assertEquals("AC", main.get("uf"));
        assertEquals(7, String.valueOf(out.get("mainIbgeCode")).length());
        double sum = ((List<Map<String, Object>>) out.get("municipalities")).stream().mapToDouble(m -> num(m.get("percentOfArea"))).sum();
        assertTrue(sum > 95 && sum < 101, "shares add up to the area: " + sum);
    }

    @Test
    void biomes() throws Exception {
        Map<String, Object> out = run(new BrGeoBiomesBlock(queries), area());
        assertEquals("Amazônia", out.get("mainBiome"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void embargoes() throws Exception {
        Map<String, Object> out = run(new BrGeoEmbargoesBlock(queries), area());
        assertTrue(num(out.get("count")) > 0);
        assertTrue((Boolean) out.get("hasActive"));
        List<Map<String, Object>> list = (List<Map<String, Object>>) out.get("embargoes");
        assertTrue((Boolean) list.get(0).get("active"), "active terms come first");

        Map<String, Object> onlyActive = area();
        onlyActive.put("includeInactive", false);
        Map<String, Object> active = run(new BrGeoEmbargoesBlock(queries), onlyActive);
        assertEquals(out.get("activeCount"), active.get("count"));
    }

    @Test
    void deterAlerts() throws Exception {
        Map<String, Object> out = run(new BrGeoDeterAlertsBlock(queries), area());
        assertTrue((Boolean) out.get("monitored"));
        assertTrue(num(out.get("count")) > 0);

        Map<String, Object> period = area();
        period.put("dateFrom", "2024-01-01");
        period.put("dateTo", "2024-12-31");
        Map<String, Object> year = run(new BrGeoDeterAlertsBlock(queries), period);
        assertTrue(num(year.get("count")) < num(out.get("count")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void prodes() throws Exception {
        Map<String, Object> out = run(new BrGeoProdesBlock(queries), area());
        assertTrue((Boolean) out.get("monitored"));
        assertFalse(((List<Object>) out.get("byYear")).isEmpty());
        Map<String, Object> from = area();
        from.put("yearFrom", 2012);
        Map<String, Object> later = run(new BrGeoProdesBlock(queries), from);
        assertTrue(num(later.get("count")) < num(out.get("count")));
    }

    @Test
    void cropProduction() throws Exception {
        Map<String, Object> out = run(new BrGeoCropProductionBlock(queries), Map.of("municipalityIbge", 1200302));
        assertNotNull(out.get("year"));
        assertTrue(num(out.get("count")) > 0);
        assertTrue(num(out.get("totalHarvestedHa")) > 0);
    }
}
