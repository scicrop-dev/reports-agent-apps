package com.infinitestack.reports.blocks.sathub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.infinitestack.reports.engine.block.BlockPort;
import com.infinitestack.reports.engine.block.BlockQueries;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Runs {@code sathub-index-series} against the shared dev database. Skipped unless
 * {@code CAR_TEST_DB_URL} is set. The dev Satellite Hub areas are in northern France, so the
 * geometry case uses a small square there, not a CAR property.
 */
class SatelliteHubDevDbTest {

    /** A dev Satellite Hub area with the 8 indices from 2026-04 to 2026-10. */
    static final long AREA_ID = 16991;

    static HikariDataSource dataSource;
    static SatelliteHubIndexSeriesBlock block;

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
        block = new SatelliteHubIndexSeriesBlock(new BlockQueries(dataSource));
    }

    @AfterAll
    static void close() {
        if (dataSource != null) dataSource.close();
    }

    static Map<String, Object> run(Map<String, Object> inputs) throws Exception {
        long start = System.currentTimeMillis();
        Map<String, Object> out = block.execute(inputs, null);
        for (BlockPort port : block.definition().outputs()) {
            if (port.required()) assertNotNull(out.get(port.name()), port.name());
            port.type().coerce(out.get(port.name()));
        }
        assertEquals(block.definition().outputs().size(), out.size());
        System.out.println("sathub-index-series (" + (System.currentTimeMillis() - start) + " ms): count=" + out.get("count")
                + " areas=" + out.get("areaCount") + " latest=" + out.get("latestDate") + " " + out.get("latestMean")
                + " chart=" + ((String) out.get("chart")).length() + " chars");
        return out;
    }

    @Test
    void oneAreaById() throws Exception {
        Map<String, Object> out = run(Map.of("areaId", AREA_ID));
        assertEquals(1, ((Number) out.get("areaCount")).intValue());
        assertTrue(((Number) out.get("count")).intValue() > 5);
        double ndvi = ((Number) out.get("latestMean")).doubleValue();
        assertTrue(ndvi > -1 && ndvi < 1);
        byte[] png = Base64.getDecoder().decode(((String) out.get("chart")).substring("data:image/png;base64,".length()));
        Path file = Path.of("target", "sathub-index-series.png");
        Files.createDirectories(file.getParent());
        Files.write(file, png);
    }

    @Test
    void areasInsideAGeometry() throws Exception {
        Map<String, Object> square = Map.of("type", "Polygon", "coordinates", List.of(List.of(
                List.of(2.40, 49.35), List.of(2.50, 49.35), List.of(2.50, 49.42), List.of(2.40, 49.42), List.of(2.40, 49.35))));
        Map<String, Object> out = run(Map.of("geometry", square, "index", "evi", "days", 90));
        assertTrue(((Number) out.get("areaCount")).intValue() > 1);
    }

    @Test
    void unknownIndexFails() {
        assertThrows(IllegalArgumentException.class, () -> block.execute(Map.of("areaId", AREA_ID, "index", "xyz"), null));
    }
}
