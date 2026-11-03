package com.infinitestack.reports.blocks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.infinitestack.reports.blocks.brgeo.BrGeoCropProgressBlock;
import com.infinitestack.reports.blocks.brgeo.BrGeoZarcRiskBlock;
import com.infinitestack.reports.blocks.brweather.BrWeatherDailyBlock;
import com.infinitestack.reports.blocks.brweather.BrWeatherRainfallBlockAccess;
import com.infinitestack.reports.blocks.car.ReportAreaBlock;
import com.infinitestack.reports.blocks.gis.GisAreas;
import com.infinitestack.reports.blocks.sathub.SatelliteHubIndexSeriesBlock;
import com.infinitestack.reports.engine.block.BlockPort;
import com.infinitestack.reports.engine.block.BlockQueries;
import com.infinitestack.reports.engine.block.ReportBlock;
import com.zaxxer.hikari.HikariDataSource;

/**
 * The SQL of the ZARC, CONAB, BR Weather and Satellite Hub series Blocks, and the polygon path of
 * report-area, against synthetic tables with the columns the
 * source apps create (br-geo-agent-app, br-weather-agent-app), in a disposable PostGIS. Runs only
 * with REPORTS_TEST_DB_URL (+ _USER, _PASSWORD), like the end-to-end test. It creates the gold tables
 * only when none of them exists, and drops what it created: pointed at a database that has the real
 * tables, it is skipped instead of touching them.
 */
class SourceBlocksSyntheticDbTest {

    static final List<String> TABLES = List.of("br_geo_agent_app_zarc_risk", "br_geo_agent_app_conab_crop_progress",
            "br_weather_agent_app_nasa_power_cell", "br_weather_agent_app_nasa_power_daily", "br_weather_agent_app_chirps_day",
            "satellite_hub_areas", "satellite_hub_analytics_indices", "satellite_hub_analytics_values");

    static HikariDataSource dataSource;
    static JdbcTemplate jdbc;
    static BlockQueries queries;
    static Path lake;
    static boolean created;

    @BeforeAll
    static void createTables() throws Exception {
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
        jdbc.execute("CREATE TABLE gold.br_geo_agent_app_zarc_risk (id bigserial PRIMARY KEY, season text NOT NULL, crop text NOT NULL,"
                + " cropping_system text NOT NULL, climate_type text NOT NULL, uf varchar(2) NOT NULL, ordinance text NOT NULL,"
                + " ordinance_date date, cd_mun varchar(7), municipality text NOT NULL, cultivar_group text NOT NULL, soil text NOT NULL,"
                + " risk smallint[] NOT NULL, valid_to timestamptz)");
        jdbc.execute("CREATE INDEX ON gold.br_geo_agent_app_zarc_risk (season, crop, cropping_system, climate_type, uf)");
        jdbc.execute("CREATE TABLE gold.br_geo_agent_app_conab_crop_progress (id bigserial PRIMARY KEY, crop text NOT NULL, season text NOT NULL,"
                + " operation text NOT NULL, state text NOT NULL, measure text NOT NULL, week_end date NOT NULL, pct_done numeric(6,4),"
                + " source_week date NOT NULL, source_page text NOT NULL, valid_to timestamptz)");
        jdbc.execute("CREATE TABLE gold.br_weather_agent_app_nasa_power_cell (id bigserial PRIMARY KEY, lat numeric(6,3) NOT NULL,"
                + " lon numeric(7,3) NOT NULL, tile text NOT NULL, elevation_m numeric(8,2), geom geometry(MultiPolygon, 4674), valid_to timestamptz)");
        jdbc.execute("CREATE TABLE gold.br_weather_agent_app_nasa_power_daily (id bigserial PRIMARY KEY, lat numeric(6,3) NOT NULL,"
                + " lon numeric(7,3) NOT NULL, day date NOT NULL, year smallint NOT NULL, tile text NOT NULL, t2m numeric(9,3), t2m_max numeric(9,3),"
                + " t2m_min numeric(9,3), rh2m numeric(9,3), ws2m numeric(9,3), allsky_sfc_sw_dwn numeric(9,3), prectotcorr numeric(9,3),"
                + " valid_to timestamptz)");
        jdbc.execute("CREATE UNIQUE INDEX ON gold.br_weather_agent_app_nasa_power_daily (lat, lon, day) WHERE valid_to IS NULL");
        jdbc.execute("CREATE TABLE gold.br_weather_agent_app_chirps_day (id bigserial PRIMARY KEY, day date NOT NULL, year smallint NOT NULL,"
                + " month smallint NOT NULL, variant text NOT NULL, source_url text NOT NULL, path text NOT NULL, size_bytes bigint NOT NULL,"
                + " sha256 text NOT NULL, width integer NOT NULL, height integer NOT NULL, west double precision NOT NULL,"
                + " south double precision NOT NULL, east double precision NOT NULL, north double precision NOT NULL, crs text NOT NULL,"
                + " nodata double precision NOT NULL, valid_to timestamptz)");
        jdbc.execute("CREATE TABLE gold.satellite_hub_areas (id bigserial PRIMARY KEY, farm_name text, block_name text, parcel_name text,"
                + " area_ha numeric, geom geometry(MultiPolygon, 4326))");
        jdbc.execute("CREATE TABLE gold.satellite_hub_analytics_indices (id bigserial PRIMARY KEY, slug text, name text, description text,"
                + " collection text, enabled boolean)");
        jdbc.execute("CREATE TABLE gold.satellite_hub_analytics_values (index_id bigint, area_id bigint, acquired_at timestamptz, mean double precision,"
                + " min_value double precision, max_value double precision, p10 double precision, p90 double precision, valid_pixels integer,"
                + " valid_area_pct double precision)");
        queries = new BlockQueries(dataSource);
        lake = Files.createTempDirectory("chirps-lake");
    }

    @AfterAll
    static void dropTables() throws Exception {
        if (created) for (String table : TABLES) jdbc.execute("DROP TABLE IF EXISTS gold." + table);
        if (dataSource != null) dataSource.close();
        if (lake != null) {
            try (var files = Files.walk(lake)) {
                files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

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

    static Map<String, Object> box(double west, double south, double east, double north) {
        return Map.of("type", "Polygon", "coordinates", List.of(List.of(List.of(west, south), List.of(east, south),
                List.of(east, north), List.of(west, north), List.of(west, south))));
    }

    static String riskArray(int value, int... periods) {
        int[] risk = new int[36];
        for (int p : periods) risk[p - 1] = value;
        StringBuilder out = new StringBuilder("{");
        for (int i = 0; i < 36; i++) out.append(i == 0 ? "" : ",").append(risk[i]);
        return out.append("}").toString();
    }

    // ---------------------------------------------------------------- ZARC

    @Test
    void zarcTakesTheLatestSeasonOfTheCropInTheMunicipality() throws Exception {
        String insert = "INSERT INTO gold.br_geo_agent_app_zarc_risk (season, crop, cropping_system, climate_type, uf, ordinance,"
                + " ordinance_date, cd_mun, municipality, cultivar_group, soil, risk) VALUES (?, ?, 'Sequeiro', 'Não se aplica', ?, ?, ?, ?, ?, ?, ?, ?::smallint[])";
        // Soja in Rio Branco: 2025/2026 and 2026/2027; the window crosses the turn of the year
        jdbc.update(insert, "2025/2026", "Soja", "AC", "100", java.sql.Date.valueOf("2025-07-01"), "1200401", "Rio Branco", "Grupo I", "AD3",
                riskArray(20, 30, 31));
        jdbc.update(insert, "2026/2027", "Soja", "AC", "193", java.sql.Date.valueOf("2026-07-03"), "1200401", "Rio Branco", "Grupo I", "AD3",
                riskArray(20, 29, 30, 31, 32, 33, 34, 35, 36, 1, 2));
        jdbc.update(insert, "2026/2027", "Soja", "AC", "193", java.sql.Date.valueOf("2026-07-03"), "1200401", "Rio Branco", "Grupo I", "AD1",
                riskArray(0));
        jdbc.update(insert, "2026/2027", "Soja", "AC", "193", java.sql.Date.valueOf("2026-07-03"), "1200302", "Xapuri", "Grupo I", "AD3",
                riskArray(30, 30));
        jdbc.update(insert, "2026/2027", "Milho 2ª safra", "MT", "201", java.sql.Date.valueOf("2026-07-10"), "5107065", "Querência", "Grupo II", "AD4",
                riskArray(20, 3, 4, 5));
        jdbc.execute("UPDATE gold.br_geo_agent_app_zarc_risk SET risk[33] = 30 WHERE season = '2026/2027' AND cd_mun = '1200401' AND soil = 'AD3'");

        BrGeoZarcRiskBlock block = new BrGeoZarcRiskBlock(queries);
        Map<String, Object> out = run(block, Map.of("municipalityIbge", 1200401, "crop", "SOJA"));
        assertEquals(true, out.get("found"));
        assertEquals("2026/2027", out.get("season"));
        assertEquals("193", out.get("ordinance"));
        assertEquals("2026-07-03", out.get("ordinanceDate"));
        assertEquals(2, out.get("count"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get("rows");
        Map<String, Object> ad3 = rows.stream().filter(r -> "AD3".equals(r.get("soil"))).findFirst().orElseThrow();
        assertEquals(10, ad3.get("indicatedPeriods"));
        assertEquals(20, ad3.get("lowestRisk"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> windows = (List<Map<String, Object>>) ad3.get("windows");
        assertEquals(List.of("11/10 a 20/11 (20%)", "21/11 a 30/11 (30%)", "01/12 a 20/01 (20%)"),
                windows.stream().map(w -> w.get("label")).toList());
        Map<String, Object> ad1 = rows.stream().filter(r -> "AD1".equals(r.get("soil"))).findFirst().orElseThrow();
        assertEquals(List.of(), ad1.get("windows"));
        assertEquals(36, ((List<?>) out.get("periods")).size());
        assertTrue(((List<?>) out.get("availableCrops")).contains("Milho 2ª safra"));

        Map<String, Object> older = run(block, Map.of("municipalityIbge", 1200401, "crop", "soja", "season", "2025/2026"));
        assertEquals("100", older.get("ordinance"));

        Map<String, Object> milho = run(block, Map.of("municipalityIbge", 5107065, "crop", "milho 2a safra"));
        assertEquals(true, milho.get("found"), String.valueOf(milho.get("message")));
        assertEquals("MT", milho.get("uf"));

        Map<String, Object> soil = run(block, Map.of("municipalityIbge", 1200401, "crop", "Soja", "soil", "ad1"));
        assertEquals(1, soil.get("count"));

        Map<String, Object> unknown = run(block, Map.of("municipalityIbge", 1200401, "crop", "Café"));
        assertEquals(false, unknown.get("found"));
        assertTrue(((String) unknown.get("message")).contains("no crop 'Café'"), (String) unknown.get("message"));

        Map<String, Object> elsewhere = run(block, Map.of("municipalityIbge", 1100205, "crop", "Soja"));
        assertEquals(false, elsewhere.get("found"));
        assertEquals("RO", elsewhere.get("uf"));
    }

    // ---------------------------------------------------------------- CONAB

    @Test
    void conabKeepsTheNewestWeekOfEachCropInTheState() throws Exception {
        String insert = "INSERT INTO gold.br_geo_agent_app_conab_crop_progress (crop, season, operation, state, measure, week_end, pct_done,"
                + " source_week, source_page) VALUES (?, ?, ?, ?, ?, ?, ?, ?, '/page')";
        LocalDate week = LocalDate.parse("2026-10-24");
        jdbc.update(insert, "Soja", "2026/27", "sowing", "Mato Grosso", "pct_done", java.sql.Date.valueOf(week), 0.452, java.sql.Date.valueOf(week));
        jdbc.update(insert, "Soja", "2026/27", "sowing", "Mato Grosso", "pct_done", java.sql.Date.valueOf(week.minusDays(7)), 0.301, java.sql.Date.valueOf(week));
        jdbc.update(insert, "Soja", "2026/27", "sowing", "Mato Grosso", "pct_done", java.sql.Date.valueOf(week.minusDays(364)), 0.55, java.sql.Date.valueOf(week));
        jdbc.update(insert, "Soja", "2026/27", "sowing", "Mato Grosso", "avg_5y", java.sql.Date.valueOf(week), 0.4, java.sql.Date.valueOf(week));
        // an old harvest, finished months ago: dropped by maxAgeDays
        jdbc.update(insert, "Milho 2ª safra", "2025/26", "harvest", "Mato Grosso", "pct_done", java.sql.Date.valueOf("2026-07-25"), 1.0,
                java.sql.Date.valueOf("2026-07-25"));
        jdbc.update(insert, "Soja", "2026/27", "sowing", "Paraná", "pct_done", java.sql.Date.valueOf(week), 0.7, java.sql.Date.valueOf(week));

        BrGeoCropProgressBlock block = new BrGeoCropProgressBlock(queries);
        Map<String, Object> out = run(block, Map.of("uf", "mt"));
        assertEquals(true, out.get("found"));
        assertEquals("Mato Grosso", out.get("state"));
        assertEquals("2026-10-24", out.get("latestWeekEnd"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) out.get("items");
        assertEquals(1, items.size());
        Map<String, Object> soja = items.get(0);
        assertEquals("Semeadura", soja.get("operationLabel"));
        assertEquals(45.2, soja.get("pctDone"));
        assertEquals(30.1, soja.get("previousWeekPct"));
        assertEquals(15.1, soja.get("weekChangePoints"));
        assertEquals(55.0, soja.get("lastYearPct"));
        assertEquals(40.0, soja.get("avg5yPct"));
        assertEquals(5.2, soja.get("vsAvg5yPoints"));
        assertEquals(2, ((List<?>) soja.get("series")).size(), "last year's point is not part of this campaign");

        Map<String, Object> withHarvest = run(block, Map.of("uf", "Mato Grosso", "maxAgeDays", 200));
        assertEquals(2, withHarvest.get("count"));

        Map<String, Object> none = run(block, Map.of("uf", "AC"));
        assertEquals(false, none.get("found"));
        assertEquals(List.of("Mato Grosso", "Paraná"), none.get("availableStates"));
    }

    // ---------------------------------------------------------------- NASA POWER

    @Test
    void dailyWeatherWeightsTheCellsByTheShareOfTheArea() throws Exception {
        String cell = "INSERT INTO gold.br_weather_agent_app_nasa_power_cell (lat, lon, tile, elevation_m, geom)"
                + " VALUES (?, ?, 't', ?, ST_Multi(ST_MakeEnvelope(?, ?, ?, ?, 4674)))";
        jdbc.update(cell, -2.0, -70.0, 180.5, -70.3125, -2.25, -69.6875, -1.75);
        jdbc.update(cell, -2.0, -69.375, 200.0, -69.6875, -2.25, -69.0625, -1.75);
        String daily = "INSERT INTO gold.br_weather_agent_app_nasa_power_daily (lat, lon, day, year, tile, t2m, t2m_max, t2m_min, rh2m, ws2m,"
                + " allsky_sfc_sw_dwn, prectotcorr) VALUES (?, ?, ?, 2026, 't', ?, ?, ?, 80, 2, ?, ?)";
        LocalDate last = LocalDate.parse("2026-10-20");
        for (int i = 0; i < 40; i++) {
            LocalDate day = last.minusDays(i);
            jdbc.update(daily, -2.0, -70.0, java.sql.Date.valueOf(day), 20, 30, 4, i == 0 ? null : 18.0, 1.0);
            jdbc.update(daily, -2.0, -69.375, java.sql.Date.valueOf(day), 28, i == 5 ? 40 : 34, 2, 20.0, 3.0);
        }

        BrWeatherDailyBlock block = new BrWeatherDailyBlock(queries);
        // 0.1125 degree in the first cell, 0.1875 in the second: 37.5% / 62.5%
        Map<String, Object> out = run(block, Map.of("geometry", box(-69.8, -2.1, -69.5, -1.9)));
        assertEquals(true, out.get("found"));
        assertEquals("2026-10-20", out.get("latestDay"));
        assertEquals("2026-09-21", out.get("from"));
        assertEquals(30, out.get("daysWithData"));
        assertFalse((Boolean) out.get("nearestCell"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cells = (List<Map<String, Object>>) out.get("cells");
        assertEquals(62.5, cells.get(0).get("weightPercent"));
        assertEquals(25.0, out.get("meanT2m"));
        @SuppressWarnings("unchecked")
        Map<String, Object> lastDay = ((List<Map<String, Object>>) out.get("days")).get(29);
        assertEquals(20.0, lastDay.get("radiation"), "a missing value is not counted as zero");
        assertEquals(2.25, lastDay.get("precipitation"));
        assertEquals(1, out.get("hotDays"));
        assertEquals(30, out.get("coldNights"));
        assertEquals(450.0, out.get("degreeDays"));
        assertEquals("2026-10-15", out.get("maxT2mDate"));
        assertTrue(((String) out.get("chart")).startsWith("data:image/png;base64,"));

        Map<String, Object> period = run(block, Map.of("geometry", box(-69.8, -2.1, -69.5, -1.9), "from", "2026-10-01", "to", "2026-10-10"));
        assertEquals(10, period.get("daysWithData"));

        Map<String, Object> outside = run(block, Map.of("geometry", box(-50.0, -20.0, -49.9, -19.9)));
        assertEquals(false, outside.get("found"));
    }

    // ---------------------------------------------------------------- CHIRPS

    @Test
    void rainfallReadsThePixelsOfTheAreaFromTheClips() throws Exception {
        Path fixture = Path.of(getClass().getResource("/cog/rain-4326.tif").toURI());
        String insert = "INSERT INTO gold.br_weather_agent_app_chirps_day (day, year, month, variant, source_url, path, size_bytes, sha256,"
                + " width, height, west, south, east, north, crs, nodata) VALUES (?, 2026, 10, ?, 'u', ?, 1, 's', 300, 260,"
                + " -74.5, -7.0, -59.5, 6.0, 'EPSG:4326', -9999)";
        for (int d = 1; d <= 5; d++) {
            Path file = lake.resolve("rnl/2026/chirps-2026-10-0" + d + ".tif");
            Files.createDirectories(file.getParent());
            if (d != 3) Files.copy(fixture, file); // day 3: the file is gone
            jdbc.update(insert, java.sql.Date.valueOf("2026-10-0" + d), d == 5 ? "prelim" : "rnl", file.toString());
        }

        ReportBlock block = BrWeatherRainfallBlockAccess.create(queries, lake);
        // pixel (col 90, row 159) only: ((90 * 3 + 159 * 7) % 97) / 4 = 6.25 mm
        Map<String, Object> out = run(block, Map.of("geometry", box(-70.02, -2.02, -69.93, -1.93), "days", 7));
        assertEquals(true, out.get("found"), String.valueOf(out.get("message")));
        assertEquals("2026-10-05", out.get("latestDay"));
        assertEquals(1, out.get("pixels"));
        assertEquals(true, out.get("wholePixels"));
        assertEquals(4, out.get("daysWithData"));
        assertEquals(3, out.get("missingDays"), "two days before the first clip and day 3 without file");
        assertEquals(25.0, out.get("totalMm"));
        assertEquals(4, out.get("rainyDays"));
        assertEquals(1, out.get("preliminaryDays"));

        // smaller than a pixel and off its centre: the pixel of its box
        Map<String, Object> tiny = run(block, Map.of("geometry", box(-69.99, -1.99, -69.98, -1.98), "from", "2026-10-01", "to", "2026-10-01"));
        assertEquals(false, tiny.get("wholePixels"));
        assertEquals(1, tiny.get("pixels"));
        assertEquals(6.25, ((List<Map<String, Object>>) cast(tiny.get("days"))).get(0).get("rainMm"));

        jdbc.update(insert, java.sql.Date.valueOf("2026-10-06"), "prelim", "/etc/passwd");
        Exception e = assertThrows(IllegalStateException.class,
                () -> block.execute(Map.of("geometry", box(-70.02, -2.02, -69.93, -1.93)), null));
        assertTrue(e.getMessage().contains("outside reports.brweather.chirps-dir"), e.getMessage());
        jdbc.update("DELETE FROM gold.br_weather_agent_app_chirps_day WHERE day = '2026-10-06'");
    }

    @Test
    void reportAreaCleansAPolygonAndRefusesWhatIsNotOne() throws Exception {
        ReportAreaBlock block = new ReportAreaBlock(queries, new GisAreas(queries));
        // Two overlapping squares in a FeatureCollection: merged into one area, not counted twice.
        Map<String, Object> collection = Map.of("type", "FeatureCollection", "features", List.of(
                Map.of("type", "Feature", "properties", Map.of(), "geometry", box(-52.21, -12.64, -52.17, -12.61)),
                Map.of("type", "Feature", "properties", Map.of(), "geometry", box(-52.19, -12.64, -52.15, -12.61))));
        Map<String, Object> out = run(block, Map.of("geometry", collection, "name", "Fazenda Teste"));
        assertEquals("polygon", out.get("source"));
        assertEquals(false, out.get("isCar"));
        assertEquals("Fazenda Teste", out.get("label"));
        double merged = (Double) out.get("areaHa");
        double single = (Double) run(block, Map.of("geometry", box(-52.21, -12.64, -52.17, -12.61))).get("areaHa");
        assertTrue(merged > single && merged < 2 * single, merged + " vs " + single);
        assertEquals(-52.15, ((Map<String, Object>) cast(out.get("bbox"))).get("maxLon"));
        // A self-crossing "bow tie" is made valid instead of failing.
        Map<String, Object> bowTie = Map.of("type", "Polygon", "coordinates", List.of(List.of(List.of(-52.2, -12.6), List.of(-52.1, -12.7),
                List.of(-52.1, -12.6), List.of(-52.2, -12.7), List.of(-52.2, -12.6))));
        assertTrue((Double) run(block, Map.of("geometry", bowTie)).get("areaHa") > 0);

        assertThrows(IllegalArgumentException.class, () -> block.execute(Map.of("geometry", box(654770, 8596060, 655770, 8597060)), null));
        assertThrows(IllegalArgumentException.class, () -> block.execute(Map.of("geometry",
                Map.of("type", "LineString", "coordinates", List.of(List.of(-52.2, -12.6), List.of(-52.1, -12.7)))), null));
        assertThrows(IllegalArgumentException.class, () -> block.execute(Map.of(), null));
        assertThrows(IllegalArgumentException.class, () -> block.execute(Map.of("codImovel", "MT-1", "geometry", box(-52.2, -12.7, -52.1, -12.6)), null));
    }

    @Test
    void indexSeriesTakesAFromToWindow() throws Exception {
        jdbc.update("INSERT INTO gold.satellite_hub_areas (id, farm_name, block_name, parcel_name, area_ha, geom)"
                + " VALUES (1, 'F', 'B', 'T1', 100, ST_Multi(ST_MakeEnvelope(-52.2, -12.7, -52.1, -12.6, 4326)))");
        jdbc.update("INSERT INTO gold.satellite_hub_analytics_indices (id, slug, name, description, collection, enabled)"
                + " VALUES (1, 'ndvi', 'NDVI', 'Normalized Difference Vegetation Index', NULL, true)");
        for (int month = 1; month <= 6; month++) {
            jdbc.update("INSERT INTO gold.satellite_hub_analytics_values VALUES (1, 1, ?::timestamptz, ?, 0, 1, 0.1, 0.9, 1000, 95)",
                    String.format("2026-%02d-15T13:30:00Z", month), month / 10.0);
        }
        SatelliteHubIndexSeriesBlock block = new SatelliteHubIndexSeriesBlock(queries);
        Map<String, Object> geometry = box(-52.2, -12.7, -52.1, -12.6);

        Map<String, Object> window = run(block, Map.of("geometry", geometry, "from", "2026-02-01", "to", "2026-04-15"));
        assertEquals(List.of("2026-02-15", "2026-03-15", "2026-04-15"), dates(window));
        // 'to' alone counts 'days' back from it; nothing set keeps the old behaviour (back from the latest image).
        assertEquals(List.of("2026-03-15", "2026-04-15"), dates(run(block, Map.of("geometry", geometry, "to", "2026-04-15", "days", 40))));
        assertEquals(List.of("2026-05-15", "2026-06-15"), dates(run(block, Map.of("geometry", geometry, "days", 40))));
        assertEquals(List.of("2026-05-15", "2026-06-15"), dates(run(block, Map.of("geometry", geometry, "from", "2026-05-01"))));
        assertThrows(IllegalArgumentException.class, () -> block.execute(Map.of("geometry", geometry, "from", "2026-05-01", "to", "2026-04-01"), null));
    }

    private static List<Object> dates(Map<String, Object> out) {
        List<Map<String, Object>> series = cast(out.get("series"));
        return series.stream().map(p -> p.get("date")).toList();
    }

    @SuppressWarnings("unchecked")
    static <T> T cast(Object value) {
        return (T) value;
    }
}
