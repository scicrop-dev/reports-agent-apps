package com.infinitestack.reports.blocks.brweather;

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
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.infinitestack.reports.blocks.sathub.imagery.HttpCogReader;
import com.infinitestack.reports.blocks.sathub.imagery.LocalFileRangeFetcher;
import com.infinitestack.reports.engine.block.BlockPort;
import com.infinitestack.reports.engine.block.BlockQueries;
import com.infinitestack.reports.engine.block.ReportBlock;
import com.zaxxer.hikari.HikariDataSource;

/**
 * ET0 in brweather-daily-weather, brweather-water-balance and brweather-temperature-anomaly against
 * synthetic BR Weather tables (the columns and the current-row index of br-weather-agent-app) in a
 * disposable PostGIS, like {@code SourceBlocksSyntheticDbTest}: runs only with REPORTS_TEST_DB_URL
 * (+ _USER, _PASSWORD), is skipped when the tables already exist, and drops what it created.
 */
class BrWeatherBlocksSyntheticDbTest {

    static final List<String> TABLES = List.of("br_weather_agent_app_nasa_power_cell", "br_weather_agent_app_nasa_power_daily",
            "br_weather_agent_app_chirps_day");

    /** The cell of the CHIRPS fixture's pixel (col 90, row 159: 6.25 mm every day). */
    static final Map<String, Object> RAIN_BOX = box(-70.02, -2.02, -69.93, -1.93);
    /** A cell far away, for the climatology. */
    static final Map<String, Object> CLIMATE_BOX = box(-50.2, -10.2, -50.0, -10.0);

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
        jdbc.execute("CREATE TABLE gold.br_weather_agent_app_nasa_power_cell (id bigserial PRIMARY KEY, lat numeric(6,3) NOT NULL,"
                + " lon numeric(7,3) NOT NULL, tile text NOT NULL, elevation_m numeric(8,2), geom geometry(MultiPolygon, 4674), valid_to timestamptz)");
        jdbc.execute("CREATE TABLE gold.br_weather_agent_app_nasa_power_daily (id bigserial PRIMARY KEY, lat numeric(6,3) NOT NULL,"
                + " lon numeric(7,3) NOT NULL, day date NOT NULL, year smallint NOT NULL, tile text NOT NULL, t2m numeric(9,3), t2m_max numeric(9,3),"
                + " t2m_min numeric(9,3), rh2m numeric(9,3), ws2m numeric(9,3), allsky_sfc_sw_dwn numeric(9,3), prectotcorr numeric(9,3),"
                + " valid_to timestamptz)");
        jdbc.execute("CREATE UNIQUE INDEX br_weather_agent_app_nasa_power_daily_current ON gold.br_weather_agent_app_nasa_power_daily"
                + " (lat, lon, day) WHERE valid_to IS NULL");
        jdbc.execute("CREATE TABLE gold.br_weather_agent_app_chirps_day (id bigserial PRIMARY KEY, day date NOT NULL, year smallint NOT NULL,"
                + " month smallint NOT NULL, variant text NOT NULL, source_url text NOT NULL, path text NOT NULL, size_bytes bigint NOT NULL,"
                + " sha256 text NOT NULL, width integer NOT NULL, height integer NOT NULL, west double precision NOT NULL,"
                + " south double precision NOT NULL, east double precision NOT NULL, north double precision NOT NULL, crs text NOT NULL,"
                + " nodata double precision NOT NULL, valid_to timestamptz)");
        queries = new BlockQueries(dataSource);
        lake = Files.createTempDirectory("chirps-lake");

        String cell = "INSERT INTO gold.br_weather_agent_app_nasa_power_cell (lat, lon, tile, elevation_m, geom)"
                + " VALUES (?, ?, 't', ?, ST_Multi(ST_MakeEnvelope(?, ?, ?, ?, 4674)))";
        jdbc.update(cell, -2.0, -70.0, 180.5, -70.3125, -2.25, -69.6875, -1.75);
        jdbc.update(cell, -10.0, -50.0, 400.0, -50.3125, -10.25, -49.6875, -9.75);

        // the rain cell: 2026-09-01 to 2026-10-05, no radiation on the last two days
        jdbc.update("INSERT INTO gold.br_weather_agent_app_nasa_power_daily (lat, lon, day, year, tile, t2m, t2m_max, t2m_min, rh2m, ws2m,"
                + " allsky_sfc_sw_dwn, prectotcorr) SELECT -2.0, -70.0, d, 2026, 't', 26, 32, 20, 60, 2,"
                + " CASE WHEN d >= '2026-10-04' THEN NULL ELSE 20 END, 1"
                + " FROM (SELECT '2026-09-01'::date + i AS d FROM generate_series(0, 34) i) s");

        // the climate cell: every day from 2000 to 2026-10-20; t2m = 20 + 0.1 per year since 2000, 3 C more in 2026;
        // 2003 lacks half of September and October (out of the climatology)
        jdbc.update("INSERT INTO gold.br_weather_agent_app_nasa_power_daily (lat, lon, day, year, tile, t2m, t2m_max, t2m_min)"
                + " SELECT -10.0, -50.0, d, extract(year FROM d), 't', t, t + 8, t - 8 FROM ("
                + " SELECT d, 20 + 0.1 * (extract(year FROM d) - 2000) + CASE WHEN extract(year FROM d) = 2026 THEN 3 ELSE 0 END AS t"
                + " FROM (SELECT '2000-01-01'::date + i AS d FROM generate_series(0, '2026-10-20'::date - '2000-01-01'::date) i) g) s"
                + " WHERE NOT (d BETWEEN '2003-09-15' AND '2003-10-20')");
        jdbc.execute("ANALYZE gold.br_weather_agent_app_nasa_power_daily");

        Path fixture = Path.of(BrWeatherBlocksSyntheticDbTest.class.getResource("/cog/rain-4326.tif").toURI());
        String insert = "INSERT INTO gold.br_weather_agent_app_chirps_day (day, year, month, variant, source_url, path, size_bytes, sha256,"
                + " width, height, west, south, east, north, crs, nodata) VALUES (?, 2026, 10, 'rnl', 'u', ?, 1, 's', 300, 260,"
                + " -74.5, -7.0, -59.5, 6.0, 'EPSG:4326', -9999)";
        for (int d = 1; d <= 5; d++) {
            Path file = lake.resolve("rnl/2026/chirps-2026-10-0" + d + ".tif");
            Files.createDirectories(file.getParent());
            if (d != 3) Files.copy(fixture, file); // day 3: the file is gone
            jdbc.update(insert, java.sql.Date.valueOf("2026-10-0" + d), file.toString());
        }
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

    @SuppressWarnings("unchecked")
    static <T> T cast(Object value) {
        return (T) value;
    }

    // ---------------------------------------------------------------- daily weather: ET0

    @Test
    void dailyWeatherAddsPenmanMonteithEt0() throws Exception {
        BrWeatherDailyBlock block = new BrWeatherDailyBlock(queries);
        Map<String, Object> out = run(block, Map.of("geometry", RAIN_BOX, "from", "2026-09-26", "to", "2026-10-05"));
        assertEquals(true, out.get("found"));
        assertEquals(10, out.get("daysWithData"));
        assertEquals(8, out.get("et0Days"), "no radiation on the last two days");
        List<Map<String, Object>> days = cast(out.get("days"));
        double expected = Math.round(Et0.penmanMonteithRhMean(32, 20, 60, 2, 20, 180.5, -2.0,
                LocalDate.parse("2026-09-26").getDayOfYear()) * 100) / 100.0;
        assertEquals(expected, days.get(0).get("et0Mm"));
        assertTrue(expected > 3 && expected < 6, String.valueOf(expected));
        assertNull(days.get(9).get("et0Mm"));
        double total = days.stream().filter(d -> d.get("et0Mm") != null).mapToDouble(d -> (Double) d.get("et0Mm")).sum();
        assertEquals(Math.round(total * 10) / 10.0, out.get("totalEt0Mm"));
        assertEquals(Math.round(total / 8 * 100) / 100.0, (Double) out.get("meanEt0Mm"), 0.011);
    }

    // ---------------------------------------------------------------- water balance

    @Test
    void waterBalanceJoinsChirpsAndEt0() throws Exception {
        BrWeatherWaterBalanceBlock block = new BrWeatherWaterBalanceBlock(queries, new HttpCogReader(new LocalFileRangeFetcher()), lake);
        Map<String, Object> out = run(block, Map.of("geometry", RAIN_BOX, "days", 5, "cadMm", 40, "kc", 1.1));
        assertEquals(true, out.get("found"), String.valueOf(out.get("message")));
        assertEquals("2026-10-01", out.get("from"));
        assertEquals("2026-10-05", out.get("to"), "the latest day with both rain and weather");
        assertEquals(40.0, out.get("cadMm"));
        assertEquals(1.1, out.get("kc"));
        List<Map<String, Object>> days = cast(out.get("days"));
        assertEquals(5, days.size());
        assertEquals(1, out.get("missingRainDays"));
        assertEquals(true, days.get(2).get("rainMissing"));
        assertNull(days.get(2).get("rainMm"));
        assertEquals(6.25, days.get(0).get("rainMm"));
        assertEquals(2, out.get("estimatedEt0Days"));
        assertEquals(true, days.get(4).get("et0Estimated"));
        assertEquals(25.0, out.get("totalRainMm"));
        assertEquals(1, out.get("pixels"));
        double eta = days.stream().mapToDouble(d -> (Double) d.get("etaMm")).sum();
        double excess = days.stream().mapToDouble(d -> (Double) d.get("excessMm")).sum();
        assertEquals((Double) out.get("finalStorageMm") - 40, 25.0 - eta - excess, 0.1, "the water adds up (rounded values)");
        assertTrue(((String) out.get("chart")).startsWith("data:image/png;base64,"));

        Map<String, Object> outside = run(block, Map.of("geometry", box(-40.0, -20.0, -39.9, -19.9)));
        assertEquals(false, outside.get("found"));
        assertThrows(IllegalArgumentException.class, () -> block.execute(Map.of("geometry", RAIN_BOX, "cadMm", 0), null));
        assertThrows(IllegalArgumentException.class, () -> block.execute(Map.of("geometry", RAIN_BOX, "kc", 5), null));
    }

    // ---------------------------------------------------------------- temperature anomaly

    @Test
    void temperatureAnomalyComparesWithTheSameWindowOfTheClosedYears() throws Exception {
        BrWeatherTemperatureAnomalyBlock block = new BrWeatherTemperatureAnomalyBlock(queries);
        Map<String, Object> out = run(block, Map.of("geometry", CLIMATE_BOX));
        assertEquals(true, out.get("found"), String.valueOf(out.get("message")));
        assertEquals("2026-09-21", out.get("from"));
        assertEquals("2026-10-20", out.get("to"));
        assertEquals(30, out.get("daysWithData"));
        assertEquals(25, out.get("yearsUsed"), "2000 to 2025 without 2003");
        assertEquals(2000, out.get("firstYear"));
        assertEquals(2025, out.get("lastYear"));
        // 2026: 20 + 2.6 + 3 = 25.6; climatology: 20 + 0.1 x mean(0..25 without 3) = 21.288
        assertEquals(25.6, out.get("meanT2m"));
        assertEquals(21.29, out.get("climatologyT2m"));
        assertEquals(4.31, out.get("anomalyT2m"));
        assertEquals(33.6, out.get("meanT2mMax"));
        assertEquals(4.31, out.get("anomalyT2mMax"));
        assertEquals(4.31, out.get("anomalyT2mMin"));
        assertEquals(30, out.get("hotDays"));
        assertEquals(0, out.get("coldNights"));
        List<Map<String, Object>> perYear = cast(out.get("perYear"));
        assertEquals(25, perYear.size());
        assertEquals(20.0, perYear.get(0).get("meanT2m"));
        assertFalse(perYear.stream().anyMatch(y -> y.get("year").equals(2003)));
        assertFalse(perYear.stream().anyMatch(y -> Boolean.TRUE.equals(y.get("current"))));

        // a period in a closed year is part of its own climatology
        Map<String, Object> past = run(block, Map.of("geometry", CLIMATE_BOX, "from", "2010-01-01", "to", "2010-01-31"));
        assertEquals(26, past.get("yearsUsed"));
        assertTrue(cast(past.get("perYear")) instanceof List<?> list
                && list.stream().anyMatch(y -> ((Map<?, ?>) y).get("year").equals(2010) && Boolean.TRUE.equals(((Map<?, ?>) y).get("current"))));

        Map<String, Object> outside = run(block, Map.of("geometry", box(-40.0, -20.0, -39.9, -19.9)));
        assertEquals(false, outside.get("found"));
    }

    @Test
    void temperatureAnomalyClimatologyUsesTheCurrentRowIndex() {
        // the plan reads the daily table through the current-row (lat, lon, day) index, not a scan
        String sql = String.format(BrWeatherTemperatureAnomalyBlock.CLIMATOLOGY_SQL, "(-10.0::numeric, -50.0::numeric, 1.0::double precision)",
                "(2000, '2000-09-21'::date, '2000-10-20'::date), (2001, '2001-09-21'::date, '2001-10-20'::date)");
        // the tiny test table would be scanned whatever the query: forbid the scan, on one connection
        String text = jdbc.execute((java.sql.Connection con) -> {
            StringBuilder plan = new StringBuilder();
            try (var st = con.createStatement()) {
                st.execute("SET enable_seqscan = off");
                try (var rs = st.executeQuery("EXPLAIN " + sql)) {
                    while (rs.next()) plan.append(rs.getString(1)).append('\n');
                } finally {
                    st.execute("RESET enable_seqscan");
                }
            }
            return plan.toString();
        });
        assertTrue(text.contains("br_weather_agent_app_nasa_power_daily_current"), text);
    }
}
