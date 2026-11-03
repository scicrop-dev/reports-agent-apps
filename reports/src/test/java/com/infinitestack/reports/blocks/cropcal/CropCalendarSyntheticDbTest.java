package com.infinitestack.reports.blocks.cropcal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import com.infinitestack.reports.engine.block.BlockQueries;
import com.infinitestack.reports.engine.block.DataRequirement;
import com.zaxxer.hikari.HikariDataSource;

/**
 * The SQL of cropcal-field-calendar against the five gold tables with the DDL is-crop-calendar creates
 * (skillslab/is-crop-calendar/is_crop_calendar/schema.py), in a disposable PostGIS. Same rules as
 * {@code SourceBlocksSyntheticDbTest}: runs only with REPORTS_TEST_DB_URL, is skipped when the tables
 * already exist, and drops what it created. The workflow port is a fake: no run is started for real.
 */
class CropCalendarSyntheticDbTest {

    static final List<String> TABLES = List.of("is_crop_calendar_state", "is_crop_calendar_series", "is_crop_calendar_block",
            "is_crop_calendar_season", "is_crop_calendar_run");

    static HikariDataSource dataSource;
    static JdbcTemplate jdbc;
    static boolean created;

    @TempDir
    Path staging;

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
        // schema.py of is-crop-calendar, column by column
        jdbc.execute("CREATE TABLE gold.is_crop_calendar_run (run_id uuid PRIMARY KEY, request_sha text NOT NULL, request_id text,"
                + " app_id text, trigger_id text, file text, aoi geometry(MultiPolygon, 4674) NOT NULL, aoi_area_ha numeric(14,4),"
                + " date_from date NOT NULL, date_to date NOT NULL, params jsonb NOT NULL DEFAULT '{}'::jsonb, params_hash text NOT NULL,"
                + " status text NOT NULL, error text, s2_dates integer, s2_any_clear_dates integer, s2_clear_dates integer,"
                + " s1_days integer, s1_passes integer, orbit integer, utm_crs text, blocks integer, seasons integer,"
                + " cire_base double precision, started_at timestamptz DEFAULT now(), finished_at timestamptz, seconds double precision)");
        jdbc.execute("CREATE INDEX idx_is_crop_calendar_run_request ON gold.is_crop_calendar_run (request_sha, params_hash)");
        StringBuilder season = new StringBuilder("CREATE TABLE gold.is_crop_calendar_season (run_id uuid NOT NULL"
                + " REFERENCES gold.is_crop_calendar_run ON DELETE CASCADE, season_no integer NOT NULL, blocks integer, green_from date,"
                + " emergence_from date, emergence_to date, planting_from date, planting_to date, senescence_from date,"
                + " senescence_to date, harvest_from date, harvest_to date, peak_evi double precision, peak_evi_on date,"
                + " peak_cire double precision");
        for (String e : List.of("planting", "senescence", "harvest")) {
            season.append(", ").append(e).append("_p10 date, ").append(e).append("_p50 date, ").append(e).append("_p90 date, ")
                    .append(e).append("_uncertainty_days double precision, ").append(e).append("_blocks integer");
        }
        season.append(", harvest_s1_share double precision");
        for (String m : List.of("cycle", "drying", "fallow")) {
            season.append(", ").append(m).append("_blocks integer");
            for (String p : List.of("min", "max", "mid", "mid_p10", "mid_p90")) season.append(", ").append(m).append('_').append(p)
                    .append("_days double precision");
        }
        jdbc.execute(season.append(", PRIMARY KEY (run_id, season_no))").toString());
        jdbc.execute("CREATE TABLE gold.is_crop_calendar_block (run_id uuid NOT NULL REFERENCES gold.is_crop_calendar_run ON DELETE CASCADE,"
                + " season_no integer NOT NULL, block_id integer NOT NULL, block_row integer, block_col integer,"
                + " geom geometry(Polygon, 4674) NOT NULL, bare_on date, visible_on date, planting_from date, planting_to date,"
                + " planting_source text, planting_days integer, senescence_from date, senescence_to date, senescence_source text,"
                + " senescence_days integer, harvest_from date, harvest_to date, harvest_source text, harvest_days integer,"
                + " vh_drop_db double precision, cycle_min_days integer, cycle_max_days integer, drying_min_days integer,"
                + " drying_max_days integer, fallow_min_days integer, fallow_max_days integer, PRIMARY KEY (run_id, season_no, block_id))");
        jdbc.execute("CREATE TABLE gold.is_crop_calendar_series (run_id uuid NOT NULL REFERENCES gold.is_crop_calendar_run ON DELETE CASCADE,"
                + " source text NOT NULL, date date NOT NULL, evi double precision, cire double precision, clear_frac double precision,"
                + " in_field_series boolean, vh_db double precision, vv_db double precision, PRIMARY KEY (run_id, source, date))");
        jdbc.execute("CREATE TABLE gold.is_crop_calendar_state (run_id uuid NOT NULL REFERENCES gold.is_crop_calendar_run ON DELETE CASCADE,"
                + " date date NOT NULL, state text NOT NULL, area_pct double precision, PRIMARY KEY (run_id, date, state))");
    }

    @AfterAll
    static void dropTables() {
        if (created) for (String table : TABLES) jdbc.execute("DROP TABLE IF EXISTS gold." + table);
        if (dataSource != null) dataSource.close();
    }

    static final String AOI = "MULTIPOLYGON(((-55.59 -12.84,-55.5872 -12.84,-55.5872 -12.8373,-55.59 -12.8373,-55.59 -12.84)))";

    static String insertRun(Map<String, Object> in, String status, String error, String startedAgo, String finishedAgo) {
        String sha = CropCalendarBlockTest.sha(in);
        String runId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO gold.is_crop_calendar_run (run_id, request_sha, app_id, aoi, aoi_area_ha, date_from, date_to,"
                + " params_hash, status, error, s2_dates, s2_clear_dates, s1_passes, orbit, blocks, seasons, started_at, finished_at)"
                + " VALUES (CAST(? AS uuid), ?, 'reports', ST_GeomFromText(?, 4674), 9.1234, CAST(? AS date), CAST(? AS date), 'h',"
                + " ?, ?, 89, 32, 27, 141, 4, ?, now() - CAST(? AS interval), " + (finishedAgo == null ? "NULL" : "now() - CAST(? AS interval)") + ")",
                finishedAgo == null
                        ? new Object[] {runId, sha, AOI, in.get("from"), in.get("to"), status, error, "COMPLETED".equals(status) ? 1 : 0, startedAgo}
                        : new Object[] {runId, sha, AOI, in.get("from"), in.get("to"), status, error, "COMPLETED".equals(status) ? 1 : 0, startedAgo, finishedAgo});
        return runId;
    }

    CropCalendarBlock block(CropCalendarBlockTest.FakeRuns runs) {
        CropCalendarSettings settings = new CropCalendarSettings("reports", "is-crop-calendar", "p-1",
                staging.resolve("reports-workflows.json"), staging);
        return new CropCalendarBlock(runs, new GoldCalendarResults(new BlockQueries(dataSource)), settings, Duration.ofMillis(5));
    }

    static Map<String, Object> inputs(String from, String to) {
        Map<String, Object> in = new LinkedHashMap<>(CropCalendarBlockTest.inputs());
        in.put("from", from);
        in.put("to", to);
        return in;
    }

    @Test
    void everyRequirementIsAColumnOfTheScriptsTables() {
        for (DataRequirement r : GoldCalendarResults.REQUIREMENTS) {
            for (String column : r.columns()) {
                Integer found = jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema = ?"
                        + " AND table_name = ? AND column_name = ?", Integer.class, r.schema(), r.table(), column);
                assertEquals(1, found, r.qualifiedName() + "." + column);
            }
        }
    }

    @Test
    void aCompletedRunIsReadFromGold() throws Exception {
        Map<String, Object> in = inputs("2025-08-01", "2026-06-30");
        insertRun(in, "FAILED", "older failure", "3 days", "3 days");
        String runId = insertRun(in, "COMPLETED", null, "10 minutes", "5 minutes");
        jdbc.update("INSERT INTO gold.is_crop_calendar_season (run_id, season_no, blocks, emergence_from, emergence_to, planting_from,"
                + " planting_to, senescence_from, senescence_to, harvest_from, harvest_to, peak_evi, peak_evi_on, planting_p10, planting_p50,"
                + " planting_p90, planting_uncertainty_days, planting_blocks, harvest_s1_share, cycle_min_days, cycle_max_days, cycle_mid_days,"
                + " drying_mid_days) VALUES (CAST(? AS uuid), 1, 4, '2025-10-25', '2025-11-06', '2025-10-10', '2025-10-22', '2026-01-05',"
                + " '2026-01-27', '2026-01-27', '2026-02-08', 0.87, '2025-12-14', '2025-10-10', '2025-10-18', '2025-10-22', 12, 4, 0.9,"
                + " 96, 133, 113, 17.5)", runId);
        for (int b = 0; b < 4; b++) {
            double w = -55.59 + (b % 2) * 0.0009;
            double s = -12.84 + (b / 2) * 0.0009;
            jdbc.update("INSERT INTO gold.is_crop_calendar_block (run_id, season_no, block_id, geom, planting_from, planting_to,"
                    + " harvest_from, harvest_to) VALUES (CAST(? AS uuid), 1, ?, ST_MakeEnvelope(?, ?, ?, ?, 4674), ?, ?, ?, ?)",
                    runId, b, w, s, w + 0.0009, s + 0.0009, LocalDate.parse("2025-10-08").plusDays(4L * b),
                    LocalDate.parse("2025-10-16").plusDays(4L * b), LocalDate.parse("2026-01-28"), LocalDate.parse("2026-02-06"));
        }
        LocalDate day = LocalDate.parse("2025-08-03");
        for (int i = 0; i < 20; i++, day = day.plusDays(15)) {
            jdbc.update("INSERT INTO gold.is_crop_calendar_series (run_id, source, date, evi, cire, clear_frac, in_field_series)"
                    + " VALUES (CAST(? AS uuid), 's2', ?, ?, ?, ?, ?)", runId, day, 0.2 + 0.03 * i, 0.5 + 0.1 * i, i % 3 == 0 ? 0.2 : 0.9, i % 3 != 0);
            jdbc.update("INSERT INTO gold.is_crop_calendar_series (run_id, source, date, vh_db, vv_db) VALUES (CAST(? AS uuid), 's1', ?, ?, ?)",
                    runId, day.plusDays(2), -17.0 + 0.2 * i, -10.0);
            jdbc.update("INSERT INTO gold.is_crop_calendar_state (run_id, date, state, area_pct) VALUES (CAST(? AS uuid), ?, 'ACTIVE', ?),"
                    + " (CAST(? AS uuid), ?, 'PRE_PLANTING', ?)", runId, day, 5.0 * i, runId, day, 100 - 5.0 * i);
        }

        CropCalendarBlockTest.FakeRuns runs = new CropCalendarBlockTest.FakeRuns();
        Map<String, Object> out = block(runs).execute(in, null);

        assertEquals(0, runs.starts);
        assertEquals("COMPLETED", out.get("status"));
        assertEquals(runId, out.get("runId"));
        assertEquals(9.12, out.get("areaHa"));
        assertEquals(32, out.get("s2ClearDates"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> seasons = (List<Map<String, Object>>) out.get("seasons");
        assertEquals(1, seasons.size());
        assertEquals("10/10/2025 a 22/10/2025", seasons.get(0).get("plantingText"));
        assertEquals("2025-10-18", seasons.get(0).get("plantingP50"));
        assertEquals(113.0, seasons.get(0).get("cycleMidDays"));
        assertEquals(90.0, seasons.get(0).get("harvestS1SharePct"));
        assertNotNull(out.get("seriesChart"));
        assertNotNull(out.get("stateChart"));
        assertNotNull(out.get("plantingMap"));
        assertEquals(4, ((List<?>) out.get("plantingLegend")).size(), "four distinct middle dates, four classes");

        List<CalendarResults.SeriesPoint> series = new GoldCalendarResults(new BlockQueries(dataSource)).series(runId);
        assertEquals(20 + 13, series.size(), "every S1 pass and only the S2 dates of the field series");
    }

    @Test
    void aRunningRunIsProcessingAndNoSeasonIsAnAnswer() throws Exception {
        Map<String, Object> running = inputs("2024-08-01", "2025-06-30");
        insertRun(running, "RUNNING", null, "2 minutes", null);
        CropCalendarBlockTest.FakeRuns runs = new CropCalendarBlockTest.FakeRuns();
        Map<String, Object> out = block(runs).execute(running, null);
        assertEquals("PROCESSING", out.get("status"));
        assertEquals(true, out.get("processing"));
        assertEquals(0, runs.starts);

        Map<String, Object> bare = inputs("2023-08-01", "2024-06-30");
        String runId = insertRun(bare, "NO_SEASON", "no green season (EVI >= green_evi) in the field series",
                "400 days", "399 days");
        jdbc.update("INSERT INTO gold.is_crop_calendar_series (run_id, source, date, evi, cire, clear_frac, in_field_series)"
                + " VALUES (CAST(? AS uuid), 's2', '2023-09-01', 0.15, 0.4, 1, true), (CAST(? AS uuid), 's2', '2023-10-01', 0.18, 0.5, 1, true)",
                runId, runId);
        Map<String, Object> none = block(runs).execute(bare, null);
        assertEquals("NO_SEASON", none.get("status"));
        assertEquals(true, none.get("ready"));
        assertEquals(false, none.get("hasSeasons"));
        assertTrue(((String) none.get("message")).contains("no green season"));
        assertNotNull(none.get("seriesChart"));
        assertEquals(0, runs.starts);
    }

    @Test
    void anOldRunThatIsNotFinalIsAskedAgain() throws Exception {
        Map<String, Object> recent = inputs("2025-11-01", "2026-10-29");
        insertRun(recent, "COMPLETED", null, "3 days", "3 days");
        CropCalendarBlockTest.FakeRuns runs = new CropCalendarBlockTest.FakeRuns();
        runs.pollsUntilDone = Integer.MAX_VALUE;
        recent.put("waitSeconds", 0);
        Map<String, Object> out = block(runs).execute(recent, null);
        assertEquals("PROCESSING", out.get("status"));
        assertEquals(1, runs.starts);
    }
}
