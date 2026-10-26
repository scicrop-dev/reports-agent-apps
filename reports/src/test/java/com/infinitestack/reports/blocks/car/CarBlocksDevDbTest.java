package com.infinitestack.reports.blocks.car;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.infinitestack.reports.engine.block.BlockPort;
import com.infinitestack.reports.engine.block.BlockQueries;
import com.infinitestack.reports.engine.block.ReportBlock;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Runs the CAR Blocks against a database with the real CAR tables (the shared dev database), and
 * checks their outputs against the contracts in BLOCKS.md. Skipped unless {@code CAR_TEST_DB_URL}
 * is set (with {@code CAR_TEST_DB_USER} / {@code CAR_TEST_DB_PASSWORD}). Read-only and per-property:
 * it never scans whole tables.
 */
class CarBlocksDevDbTest {

    /** BA, has APP (aggregate APP_TOTAL) and overlaps with other properties. */
    static final String WITH_APP = "BA-2915601-735B9BEE97F6498387B672DAA9506DF1";
    /** AC, has legal reserve, native vegetation, APP, consolidated area and hydrography. */
    static final String WITH_LEGAL_RESERVE = "AC-1200302-0C2A5FDCEF4341FD95E106A8EA98FAA1";
    /** AC, legal reserve and native vegetation, no APP. */
    static final String WITHOUT_APP = "AC-1200302-1B8ED9D6DC494197947BED64EE69FFB6";
    static final String MISSING = "XX-0000000-00000000000000000000000000000000";

    static HikariDataSource dataSource;
    static BlockQueries queries;

    @BeforeAll
    static void connect() {
        String url = System.getenv("CAR_TEST_DB_URL");
        assumeTrue(url != null && !url.isBlank(), "CAR_TEST_DB_URL not set");
        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(url);
        dataSource.setUsername(System.getenv("CAR_TEST_DB_USER"));
        dataSource.setPassword(System.getenv("CAR_TEST_DB_PASSWORD"));
        dataSource.setReadOnly(true);
        dataSource.setMaximumPoolSize(2);
        queries = new BlockQueries(dataSource);
    }

    @AfterAll
    static void close() {
        if (dataSource != null) dataSource.close();
    }

    /** Runs a Block and checks its outputs the way the executor does: declared, typed, required present. */
    static Map<String, Object> run(ReportBlock block, Map<String, Object> inputs) throws Exception {
        Map<String, Object> out = block.execute(inputs, null);
        for (BlockPort port : block.definition().outputs()) {
            Object value = out.get(port.name());
            if (port.required()) assertNotNull(value, block.definition().id() + "." + port.name());
            port.type().coerce(value);
        }
        assertEquals(block.definition().outputs().size(), out.size(), "only declared outputs");
        return out;
    }

    static Map<String, Object> cod(String codImovel) {
        return Map.of("codImovel", codImovel);
    }

    static double num(Object value) {
        return ((Number) value).doubleValue();
    }

    @Test
    void property() throws Exception {
        Map<String, Object> out = run(new CarPropertyBlock(queries), cod(WITH_APP));
        assertEquals(WITH_APP, out.get("codImovel"));
        assertEquals("BA", out.get("uf"));
        assertEquals(2915601, num(out.get("municipalityIbge")), 0);
        assertNotNull(out.get("municipality"));
        assertNotNull(out.get("statusLabel"));
        assertNotNull(out.get("typeLabel"));
        assertTrue(num(out.get("declaredAreaHa")) > 0);
        assertNotNull(out.get("versionSince"));
        System.out.println("car-property: " + out);
    }

    @Test
    @SuppressWarnings("unchecked")
    void geometry() throws Exception {
        Map<String, Object> out = run(new CarPropertyGeometryBlock(queries), cod(WITH_APP));
        Map<String, Object> geometry = (Map<String, Object>) out.get("geometry");
        assertEquals("MultiPolygon", geometry.get("type"));
        Map<String, Object> bbox = (Map<String, Object>) out.get("bbox");
        double lat = num(out.get("centroidLat")), lon = num(out.get("centroidLon"));
        assertTrue(num(bbox.get("minLat")) <= lat && lat <= num(bbox.get("maxLat")));
        assertTrue(num(bbox.get("minLon")) <= lon && lon <= num(bbox.get("maxLon")));
        assertTrue(num(out.get("areaHa")) > 0);
        assertTrue(num(out.get("vertices")) >= 4);
        System.out.println("car-property-geometry: areaHa=" + out.get("areaHa") + " point=" + lat + "," + lon
                + " bbox=" + bbox + " vertices=" + out.get("vertices"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void environmentalLayers() throws Exception {
        CarEnvironmentalLayersBlock block = new CarEnvironmentalLayersBlock(queries);
        for (String codImovel : List.of(WITH_APP, WITH_LEGAL_RESERVE, WITHOUT_APP)) {
            Map<String, Object> out = run(block, cod(codImovel));
            List<Map<String, Object>> layers = (List<Map<String, Object>>) out.get("layers");
            Map<String, Object> summary = (Map<String, Object>) out.get("summary");
            assertEquals(12, summary.size());
            int previousOrder = -1;
            for (Map<String, Object> layer : layers) {
                assertFalse("AREA_IMOVEL".equals(layer.get("layer")));
                assertTrue(num(layer.get("declaredAreaHa")) >= 0);
                assertTrue(num(layer.get("percentOfProperty")) >= 0);
                List<Map<String, Object>> themes = (List<Map<String, Object>>) layer.get("themes");
                assertFalse(themes.isEmpty());
                double themeSum = themes.stream().mapToDouble(t -> num(t.get("declaredAreaHa"))).sum();
                assertTrue(num(layer.get("declaredAreaHa")) <= themeSum + 1e-6, "layer area never exceeds its themes");
                int order = List.of("APPS", "VEGETACAO_NATIVA", "AREA_CONSOLIDADA", "AREA_POUSIO", "HIDROGRAFIA",
                        "USO_RESTRITO", "SERVIDAO_ADMINISTRATIVA", "RESERVA_LEGAL").indexOf(layer.get("layer"));
                assertTrue(order > previousOrder, "layers in camada.ordem");
                previousOrder = order;
            }
            System.out.println("car-environmental-layers " + codImovel + ": " + summary
                    + " hasLegalReserve=" + out.get("hasLegalReserve"));
        }

        Map<String, Object> app = run(block, cod(WITH_APP));
        Map<String, Object> appSummary = (Map<String, Object>) app.get("summary");
        assertEquals(20.0967, num(appSummary.get("appHa")), 1e-9, "APP_TOTAL, not APP_TOTAL + its parts");
        assertFalse((Boolean) app.get("hasLegalReserve"));

        Map<String, Object> rl = run(block, cod(WITH_LEGAL_RESERVE));
        Map<String, Object> rlSummary = (Map<String, Object>) rl.get("summary");
        assertTrue((Boolean) rl.get("hasLegalReserve"));
        assertTrue(num(rlSummary.get("legalReserveHa")) > 0);
        assertTrue(num(rlSummary.get("nativeVegetationHa")) > 0);
        assertEquals(1.2021, num(rlSummary.get("appHa")), 1e-9);

        Map<String, Object> noApp = run(block, cod(WITHOUT_APP));
        assertEquals(0.0, num(((Map<String, Object>) noApp.get("summary")).get("appHa")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void overlaps() throws Exception {
        Map<String, Object> out = run(new CarOverlapsBlock(queries), cod(WITH_APP));
        List<Map<String, Object>> overlaps = (List<Map<String, Object>>) out.get("overlaps");
        assertEquals(overlaps.size(), num(out.get("count")), 0);
        assertTrue(overlaps.size() > 0);
        assertFalse((Boolean) out.get("truncated"));
        double previous = Double.MAX_VALUE, sum = 0;
        for (Map<String, Object> o : overlaps) {
            assertFalse(WITH_APP.equals(o.get("codImovel")), "never the property itself");
            double ha = num(o.get("overlapHa"));
            assertTrue(ha >= 0.01 && ha <= previous, "largest first, above the minimum");
            assertTrue(num(o.get("percentOfProperty")) >= 0);
            previous = ha;
            sum += ha;
        }
        assertEquals(sum, num(out.get("totalOverlapHa")), 1e-3);
        System.out.println("car-overlaps: count=" + out.get("count") + " total=" + out.get("totalOverlapHa")
                + " first=" + overlaps.get(0));

        Map<String, Object> strict = new HashMap<>(cod(WITH_APP));
        strict.put("minOverlapHa", 1_000_000);
        Map<String, Object> none = run(new CarOverlapsBlock(queries), strict);
        assertEquals(0, num(none.get("count")), 0);
        assertTrue(((List<?>) none.get("overlaps")).isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void history() throws Exception {
        Map<String, Object> out = run(new CarHistoryBlock(queries), cod(WITH_APP));
        List<Map<String, Object>> versions = (List<Map<String, Object>>) out.get("versions");
        assertEquals(versions.size(), num(out.get("count")), 0);
        assertFalse(versions.isEmpty());
        assertFalse((Boolean) versions.get(0).get("geometryChanged"));
        assertEquals(null, versions.get(versions.size() - 1).get("validTo"), "the newest is the current version");
        for (int i = 1; i < versions.size(); i++) {
            assertTrue(((String) versions.get(i - 1).get("validFrom")).compareTo((String) versions.get(i).get("validFrom")) < 0);
        }
        System.out.println("car-history: " + out);
    }

    @Test
    void missingPropertyFailsWithAClearMessage() {
        List<ReportBlock> blocks = List.of(new CarPropertyBlock(queries), new CarPropertyGeometryBlock(queries),
                new CarEnvironmentalLayersBlock(queries), new CarOverlapsBlock(queries), new CarHistoryBlock(queries));
        for (ReportBlock block : blocks) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> block.execute(cod(MISSING), null));
            assertEquals("CAR property " + MISSING + " not found", e.getMessage());
        }
    }
}
