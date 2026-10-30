package com.infinitestack.reports.blocks.sathub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.infinitestack.reports.engine.block.BlockPort;
import com.infinitestack.reports.engine.block.BlockQueries;
import com.infinitestack.reports.engine.block.DataRequirement;
import com.infinitestack.reports.engine.block.ReportBlock;
import com.zaxxer.hikari.HikariDataSource;

/**
 * The Satellite Hub series Blocks (sathub-index-series, -compare, -anomaly) and the index definitions
 * of sathub-latest-image against synthetic tables with the DDL of the Satellite Hub analytics script
 * (agent-scicrop-satellite-hub-analytics.py, schema_ddl: no display_min/display_max, which only the
 * Satellite Hub app adds), in a disposable PostGIS. Runs only with REPORTS_TEST_DB_URL (+ _USER,
 * _PASSWORD), e.g. {@code docker run -p 55444:5432 -e POSTGRES_PASSWORD=pg postgis/postgis:16-3.4}.
 * Like SourceBlocksSyntheticDbTest, it creates the tables only when none exists and drops them at the end.
 *
 * <p>Data: areas 1 and 2 inside a box in Mato Grosso, area 3 outside. Indices: ndvi (Sentinel-2, every
 * 5 days), ndvi_landsat (disabled, every 16 days, 0.05 below NDVI), rvi_s1 (radar, 0 to 4, every 6
 * days), ndmi (enabled, no data), nbr (disabled, no data).
 */
class SatelliteHubSyntheticDbTest {

    static final List<String> TABLES = List.of("satellite_hub_analytics_values", "satellite_hub_analytics_indices", "satellite_hub_areas");
    static final LocalDate START = LocalDate.parse("2026-01-01");
    static final LocalDate END = LocalDate.parse("2026-06-30");

    static HikariDataSource dataSource;
    static JdbcTemplate jdbc;
    static BlockQueries queries;
    static boolean created;

    @BeforeAll
    static void createTables() {
        String url = System.getenv("REPORTS_TEST_DB_URL");
        assumeTrue(url != null && !url.isBlank(), "REPORTS_TEST_DB_URL not set");
        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(url);
        dataSource.setUsername(System.getenv().getOrDefault("REPORTS_TEST_DB_USER", "postgres"));
        dataSource.setPassword(System.getenv().getOrDefault("REPORTS_TEST_DB_PASSWORD", ""));
        dataSource.setMaximumPoolSize(2);
        jdbc = new JdbcTemplate(dataSource);
        for (String table : TABLES) {
            Integer exists = jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_schema = 'gold' AND table_name = ?",
                    Integer.class, table);
            assumeTrue(exists == 0, "gold." + table + " already exists: not a disposable database");
        }
        created = true;
        jdbc.execute("CREATE EXTENSION IF NOT EXISTS postgis");
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS gold");
        jdbc.execute("CREATE TABLE gold.satellite_hub_areas (id bigserial PRIMARY KEY, farm_name text, block_name text, parcel_name text,"
                + " area_ha numeric, geom geometry(MultiPolygon, 4326))");
        // schema_ddl() of the analytics script, as it is on satellite-hub-agent-app main
        jdbc.execute("CREATE TABLE gold.satellite_hub_analytics_indices (id BIGSERIAL PRIMARY KEY, slug VARCHAR(60) NOT NULL UNIQUE,"
                + " name VARCHAR(200) NOT NULL, description TEXT, formula TEXT NOT NULL, bands JSONB, collection VARCHAR(255),"
                + " mask_clouds BOOLEAN DEFAULT TRUE, clamp_min NUMERIC, clamp_max NUMERIC, enabled BOOLEAN DEFAULT TRUE,"
                + " created_at TIMESTAMPTZ DEFAULT NOW(), updated_at TIMESTAMPTZ DEFAULT NOW())");
        jdbc.execute("CREATE TABLE gold.satellite_hub_analytics_values (id BIGSERIAL PRIMARY KEY,"
                + " index_id BIGINT NOT NULL REFERENCES gold.satellite_hub_analytics_indices(id) ON DELETE CASCADE,"
                + " area_id BIGINT NOT NULL, scene_id BIGINT NOT NULL, acquired_at TIMESTAMPTZ, mean REAL, median REAL, min_value REAL,"
                + " max_value REAL, stddev REAL, variance REAL, p10 REAL, p25 REAL, p75 REAL, p90 REAL, cv REAL, valid_pixels INTEGER,"
                + " total_pixels INTEGER, valid_area_pct REAL, computed_at TIMESTAMPTZ DEFAULT NOW(), UNIQUE (index_id, area_id, scene_id))");
        queries = new BlockQueries(dataSource);
        seed();
    }

    @AfterAll
    static void dropTables() {
        if (created) for (String table : TABLES) jdbc.execute("DROP TABLE IF EXISTS gold." + table);
        if (dataSource != null) dataSource.close();
    }

    static void seed() {
        String area = "INSERT INTO gold.satellite_hub_areas (id, farm_name, block_name, parcel_name, area_ha, geom) VALUES (?, 'F', 'B', ?, ?,"
                + " ST_Multi(ST_MakeEnvelope(?, ?, ?, ?, 4326)))";
        jdbc.update(area, 1, "T1", 100, -52.20, -12.70, -52.15, -12.65);
        jdbc.update(area, 2, "T2", 80, -52.15, -12.70, -52.10, -12.65);
        jdbc.update(area, 3, "T3", 50, -50.00, -10.00, -49.95, -9.95);
        String index = "INSERT INTO gold.satellite_hub_analytics_indices (id, slug, name, description, formula, collection, mask_clouds,"
                + " clamp_min, clamp_max, enabled) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        jdbc.update(index, 1, "ndvi", "NDVI", "Vegetation", "(nir - red) / (nir + red)", "sentinel-2-l2a", true, -1, 1, true);
        jdbc.update(index, 2, "ndvi_landsat", "NDVI (Landsat)", "NDVI with Landsat", "(nir08 - red) / (nir08 + red)", "landsat-c2-l2", true, -1, 1, false);
        jdbc.update(index, 3, "rvi_s1", "RVI (Sentinel-1)", "Radar vegetation index", "4 * vh / (vv + vh)", "sentinel-1-rtc", false, 0, 4, true);
        jdbc.update(index, 4, "ndmi", "NDMI", "Moisture", "(nir - swir16) / (nir + swir16)", null, true, -1, 1, true);
        jdbc.update(index, 5, "nbr", "NBR", "Burn", "(nir - swir22) / (nir + swir22)", "sentinel-2-l2a", true, -1, 1, false);

        long scene = 1;
        // NDVI: a season curve with one clear drop on 2026-04-06 (and a cloudy image that must be dropped)
        for (LocalDate day = START; !day.isAfter(END); day = day.plusDays(5)) {
            double ndvi = ndvi(day);
            if (day.equals(LocalDate.parse("2026-04-06"))) ndvi -= 0.35;
            value(1, 1, scene, day, ndvi, 1000, 95);
            value(1, 2, scene++, day, ndvi + 0.02, 800, 90);
        }
        value(1, 1, scene++, LocalDate.parse("2026-03-03"), 0.05, 1000, 20); // cloudy: under minValidPercent
        for (LocalDate day = START.plusDays(1); !day.isAfter(END); day = day.plusDays(16)) {
            value(2, 1, scene, day, ndvi(day) - 0.05, 110, 95);
            value(2, 2, scene++, day, ndvi(day) - 0.05 + 0.02, 90, 90);
        }
        for (LocalDate day = START.plusDays(2); !day.isAfter(END); day = day.plusDays(6)) {
            value(3, 1, scene, day, 1 + 2 * ndvi(day), 1000, 100);
            value(3, 2, scene++, day, 1 + 2 * ndvi(day), 800, 100);
        }
        value(1, 3, scene, END, 0.1, 1000, 100); // area 3 is outside the geometry
    }

    /** A crop season: low in January, a peak in April, down again by the end of June. */
    static double ndvi(LocalDate day) {
        double t = (day.getDayOfYear() - 1) / 180.0;
        return 0.3 + 0.45 * Math.sin(Math.PI * t);
    }

    static void value(long index, long area, long scene, LocalDate day, double mean, int validPixels, double validPct) {
        jdbc.update("INSERT INTO gold.satellite_hub_analytics_values (index_id, area_id, scene_id, acquired_at, mean, median, min_value,"
                + " max_value, stddev, p10, p25, p75, p90, cv, valid_pixels, total_pixels, valid_area_pct)"
                + " VALUES (?, ?, ?, ?::timestamptz, ?, ?, ?, ?, 0.05, ?, ?, ?, ?, 0.1, ?, ?, ?)",
                index, area, scene, day + "T13:30:00Z", mean, mean, mean - 0.2, mean + 0.2, mean - 0.08, mean - 0.04, mean + 0.04,
                mean + 0.08, validPixels, validPixels, validPct);
    }

    static Map<String, Object> box() {
        double west = -52.21, south = -12.71, east = -52.09, north = -12.64;
        return Map.of("type", "Polygon", "coordinates", List.of(List.of(List.of(west, south), List.of(east, south),
                List.of(east, north), List.of(west, north), List.of(west, south))));
    }

    static Map<String, Object> run(ReportBlock block, Map<String, Object> inputs) throws Exception {
        Map<String, Object> out = block.execute(inputs, null);
        for (BlockPort port : block.definition().outputs()) {
            Object value = out.get(port.name());
            if (port.required()) assertNotNull(value, block.definition().id() + "." + port.name());
            port.type().coerce(value);
        }
        assertEquals(block.definition().outputs().size(), out.size(), "only declared outputs");
        String chart = (String) out.get("chart");
        if (chart != null) {
            byte[] png = Base64.getDecoder().decode(chart.substring("data:image/png;base64,".length()));
            Path file = Path.of("target", "sathub-synthetic", block.definition().id() + ".png");
            Files.createDirectories(file.getParent());
            Files.write(file, png);
        }
        return out;
    }

    static Map<String, Object> inputs(Object... keyValues) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) m.put((String) keyValues[i], keyValues[i + 1]);
        return m;
    }

    @SuppressWarnings("unchecked")
    static <T> T cast(Object value) {
        return (T) value;
    }

    @Test
    void requirementsMatchTheScriptDdl() {
        List<ReportBlock> blocks = List.of(new SatelliteHubIndexSeriesBlock(queries), new SatelliteHubIndexCompareBlock(queries),
                new SatelliteHubIndexAnomalyBlock(queries), new SatelliteHubLatestImageBlock(queries, null, null));
        for (ReportBlock block : blocks) {
            for (DataRequirement requirement : block.definition().dataRequirements()) {
                for (String column : requirement.columns()) {
                    Integer found = jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema = ?"
                            + " AND table_name = ? AND column_name = ?", Integer.class, requirement.schema(), requirement.table(), column);
                    assertEquals(1, found, block.definition().id() + " requires " + requirement.qualifiedName() + "." + column);
                }
            }
        }
    }

    @Test
    void seriesShowsTheHistoryOfADisabledIndex() throws Exception {
        SatelliteHubIndexSeriesBlock block = new SatelliteHubIndexSeriesBlock(queries);
        Map<String, Object> ndvi = run(block, inputs("geometry", box(), "from", START.toString(), "to", END.toString()));
        assertEquals(true, ndvi.get("indexEnabled"));
        assertEquals(2, ndvi.get("areaCount"));
        assertEquals(37, ndvi.get("count"), "the cloudy image and area 3 are left out");

        Map<String, Object> landsat = run(block, inputs("geometry", box(), "index", "ndvi_landsat", "from", START.toString()));
        assertEquals(false, landsat.get("indexEnabled"));
        assertEquals(false, ((Map<String, Object>) cast(landsat.get("index"))).get("enabled"));
        assertEquals(12, landsat.get("count"));
        assertEquals("landsat-c2-l2", ((Map<String, Object>) cast(landsat.get("index"))).get("collection"));

        IllegalArgumentException noData = assertThrows(IllegalArgumentException.class,
                () -> block.execute(inputs("areaId", 1L, "index", "nbr"), null));
        assertTrue(noData.getMessage().contains("disabled"), noData.getMessage());
        assertThrows(IllegalArgumentException.class, () -> block.execute(inputs("areaId", 1L, "index", "nope"), null));
        // An enabled index without data is an empty series, as before
        assertEquals(0, run(block, inputs("areaId", 1L, "index", "ndmi")).get("count"));
    }

    @Test
    void compareSeparatesTheRadarScaleAndPairsTheOpticalIndices() throws Exception {
        SatelliteHubIndexCompareBlock block = new SatelliteHubIndexCompareBlock(queries);
        Map<String, Object> out = run(block, inputs("geometry", box()));
        List<Map<String, Object>> series = cast(out.get("series"));
        assertEquals(List.of("ndvi", "ndvi_landsat", "rvi_s1"),
                series.stream().map(s -> ((Map<String, Object>) cast(s.get("index"))).get("slug")).toList());
        assertEquals(List.of(false, false, true), series.stream().map(s -> s.get("rightAxis")).toList());
        assertEquals(true, out.get("hasSeries"));
        assertEquals(List.of(), out.get("withoutData"));

        List<Map<String, Object>> pairs = cast(out.get("pairs"));
        assertEquals(1, pairs.size(), "rvi_s1 is not paired with optical indices");
        Map<String, Object> pair = pairs.get(0);
        assertEquals("ndvi", pair.get("a"));
        assertEquals("ndvi_landsat", pair.get("b"));
        assertTrue((Integer) pair.get("matchedDates") >= 10, "Landsat every 16 days, one day after an NDVI image: " + pair);
        // NDVI is 0.05 above Landsat, except where the Landsat image of 2026-04-07 meets the NDVI drop of
        // 04-06 (a one-image drop that Landsat did not see): it pulls the bias to ~0.02 and r to ~0.8
        assertEquals(0.02, (Double) pair.get("meanDiff"), 0.01);
        assertTrue((Double) pair.get("correlation") > 0.7, pair.toString());

        // Explicit list: unknown is an error; an index without data is listed apart
        assertThrows(IllegalArgumentException.class, () -> block.execute(inputs("areaId", 1L, "indices", "ndvi,nope"), null));
        Map<String, Object> withNdmi = run(block, inputs("areaId", 1L, "indices", "ndvi, NDMI", "days", 30));
        assertEquals(List.of("ndmi"), withNdmi.get("withoutData"));
        assertEquals(1, withNdmi.get("seriesCount"));
        // One period for all: 30 days back from the latest image of any index (2026-06-30)
        List<Map<String, Object>> points = cast(((List<Map<String, Object>>) cast(withNdmi.get("series"))).get(0).get("points"));
        assertTrue(points.stream().allMatch(p -> ((String) p.get("date")).compareTo("2026-06-01") >= 0), points.toString());

        Map<String, Object> none = run(block, inputs("areaId", 3L, "indices", "ndmi"));
        assertEquals(false, none.get("hasSeries"));
        assertEquals(0, none.get("seriesCount"));
    }

    @Test
    void anomalyFindsTheDropAndUsesHistoryBeforeTheWindow() throws Exception {
        SatelliteHubIndexAnomalyBlock block = new SatelliteHubIndexAnomalyBlock(queries);
        Map<String, Object> out = run(block, inputs("geometry", box()));
        assertEquals(true, out.get("hasDrops"));
        List<Map<String, Object>> events = cast(out.get("events"));
        assertEquals(List.of("2026-04-06"), events.stream().map(e -> e.get("date")).toList(), events.toString());
        assertEquals("2026-04-06", out.get("lastDropDate"));
        assertEquals("normal", out.get("latestStatus"));
        assertEquals(false, out.get("latestIsDrop"));
        assertEquals("2026-06-30", out.get("latestDate"));
        assertEquals(false, out.get("hasSeasonalBaseline"), "half a year of history");

        // The window starts at the drop: the baseline there still comes from March
        Map<String, Object> window = run(block, inputs("areaId", 1L, "from", "2026-04-06", "to", "2026-04-06"));
        assertEquals(1, window.get("count"));
        assertEquals("drop", window.get("latestStatus"));
        assertEquals(true, window.get("latestIsDrop"));
        assertNotNull(window.get("latestDelta"));

        // A higher threshold finds nothing; an index without data is no-data, not an error
        assertEquals(false, run(block, inputs("areaId", 1L, "dropThreshold", 0.5)).get("hasDrops"));
        Map<String, Object> empty = run(block, inputs("areaId", 1L, "index", "ndmi"));
        assertEquals("no-data", empty.get("latestStatus"));
        assertNull(empty.get("latestDate"));
        assertThrows(IllegalArgumentException.class, () -> block.execute(inputs("areaId", 1L, "index", "nope"), null));
        assertThrows(IllegalArgumentException.class, () -> block.execute(inputs("index", "ndvi"), null));
    }

    @Test
    void latestImageReadsTheDisplayRangeOnlyWhenTheAppCreatedIt() {
        SatelliteHubLatestImageBlock block = new SatelliteHubLatestImageBlock(queries, null, null);
        Map<String, Object> ndvi = block.indexRows().stream().filter(r -> "ndvi".equals(r.get("slug"))).findFirst().orElseThrow();
        assertTrue(ndvi.containsKey("display_min"));
        assertNull(ndvi.get("display_min"));
        assertNull(ndvi.get("display_max"));
        try {
            jdbc.execute("ALTER TABLE gold.satellite_hub_analytics_indices ADD COLUMN display_min NUMERIC, ADD COLUMN display_max NUMERIC");
            jdbc.update("UPDATE gold.satellite_hub_analytics_indices SET display_min = 0.1, display_max = 0.9 WHERE slug = 'ndvi'");
            ndvi = block.indexRows().stream().filter(r -> "ndvi".equals(r.get("slug"))).findFirst().orElseThrow();
            assertEquals(0.1, ((Number) ndvi.get("display_min")).doubleValue(), 1e-9);
            assertEquals(0.9, ((Number) ndvi.get("display_max")).doubleValue(), 1e-9);
            assertFalse(block.indexRows().isEmpty());
        } finally {
            jdbc.execute("ALTER TABLE gold.satellite_hub_analytics_indices DROP COLUMN IF EXISTS display_min, DROP COLUMN IF EXISTS display_max");
        }
    }
}
