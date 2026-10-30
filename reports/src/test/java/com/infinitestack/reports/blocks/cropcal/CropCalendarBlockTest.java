package com.infinitestack.reports.blocks.cropcal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.infinitestack.reports.blocks.cropcal.CalendarResults.BlockCell;
import com.infinitestack.reports.blocks.cropcal.CalendarResults.RunDetail;
import com.infinitestack.reports.blocks.cropcal.CalendarResults.RunSummary;
import com.infinitestack.reports.blocks.cropcal.CalendarResults.SeriesPoint;
import com.infinitestack.reports.blocks.cropcal.CalendarResults.StatePoint;
import com.infinitestack.reports.blocks.skillslab.WorkflowRuns;
import com.infinitestack.reports.engine.block.BlockRegistry;
import com.infinitestack.reports.engine.block.ExecutionMetadata;

class CropCalendarBlockTest {

    static final ExecutionMetadata META = new ExecutionMetadata(7L, 1, 2, "user-42", "USER");
    static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path staging;

    /** A host whose run ends after {@code pollsUntilDone} polls; {@code onDone} is what the script wrote. */
    static final class FakeRuns implements WorkflowRuns {
        String result = "SUCCESS";
        String log = "";
        int pollsUntilDone = 2;
        Runnable onDone = () -> {};
        List<GrantedWorkflow> grants = List.of(new GrantedWorkflow("is-crop-calendar", true, "is-crop-calendar"));
        int polls;
        int starts;
        String startedProject;
        Map<String, Object> startedParams;
        String startedFor;

        @Override public List<GrantedWorkflow> granted(String projectId) { return grants; }

        @Override
        public String start(String projectId, String workflow, Map<String, Object> params, String onBehalfOf) {
            starts++;
            startedProject = projectId;
            startedParams = params;
            startedFor = onBehalfOf;
            return "t-1";
        }

        @Override
        public RunState get(String projectId, String triggerId) {
            if (++polls < pollsUntilDone) return new RunState(false, "RUNNING");
            onDone.run();
            return new RunState(true, result);
        }

        @Override
        public OutputSlice output(String projectId, String triggerId, long from, int max) {
            int start = (int) Math.min(from, log.length());
            int end = Math.min(log.length(), start + max);
            return new OutputSlice(true, log.length(), end, end < log.length(), log.substring(start, end));
        }

        @Override public boolean stop(String projectId, String triggerId) { return true; }
    }

    /** gold.is_crop_calendar_* in memory. */
    static final class FakeResults implements CalendarResults {
        final Map<String, List<RunSummary>> runs = new LinkedHashMap<>();
        final Map<String, RunDetail> details = new LinkedHashMap<>();
        final List<Map<String, Object>> seasons = new ArrayList<>();
        final List<SeriesPoint> series = new ArrayList<>();
        final List<StatePoint> states = new ArrayList<>();
        final List<BlockCell> blocks = new ArrayList<>();

        @Override public List<RunSummary> runs(String requestSha) { return runs.getOrDefault(requestSha, List.of()); }
        @Override public Optional<RunDetail> run(String runId) { return Optional.ofNullable(details.get(runId)); }
        @Override public List<Map<String, Object>> seasons(String runId) { return seasons; }
        @Override public List<SeriesPoint> series(String runId) { return series; }
        @Override public List<StatePoint> states(String runId) { return states; }
        @Override public List<BlockCell> blocks(String runId, int seasonNo, int limit) { return blocks; }
    }

    static Map<String, Object> square() {
        return Map.of("type", "Polygon", "coordinates", List.of(List.of(List.of(-55.59, -12.84), List.of(-55.5872, -12.84),
                List.of(-55.5872, -12.8373), List.of(-55.59, -12.8373), List.of(-55.59, -12.84))));
    }

    static Map<String, Object> inputs() {
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("geometry", square());
        in.put("from", "2025-08-01");
        in.put("to", "2026-06-30");
        return in;
    }

    static String sha(Map<String, Object> in) {
        LocalDate[] period = CropCalendarBlock.period(in.get("from"), in.get("to"), LocalDate.now());
        @SuppressWarnings("unchecked")
        Map<String, Object> geometry = (Map<String, Object>) in.get("geometry");
        return CropCalendarRequest.of(geometry, period[0], period[1], CropCalendarBlock.params(in)).requestSha();
    }

    CropCalendarBlock block(FakeRuns runs, FakeResults results, String projectId) {
        CropCalendarSettings settings = new CropCalendarSettings("reports", "is-crop-calendar", projectId,
                staging.resolve("reports-workflows.json"), staging);
        return new CropCalendarBlock(runs, results, settings, Duration.ofMillis(5));
    }

    CropCalendarBlock block(FakeRuns runs, FakeResults results) {
        return block(runs, results, "p-1");
    }

    static RunSummary summary(String status, Double finishedAgo, boolean settled, double age) {
        return new RunSummary("run-1", status, FAILED_ERROR, "t-0", finishedAgo == null ? null : "2026-10-29T12:00:00Z", age,
                finishedAgo, settled);
    }

    static final String FAILED_ERROR = "ValueError: the AOI holds no 100 m block";

    /** A completed run of two seasons, as the script writes it for soybean.geojson (02_CALENDARIO_SAFRA_S1_S2.md). */
    static void completed(FakeResults results, String sha) {
        results.runs.put(sha, List.of(summary("COMPLETED", 120.0, false, 400)));
        results.details.put("run-1", new RunDetail("run-1", "COMPLETED", null, 737.123, LocalDate.parse("2025-08-01"),
                LocalDate.parse("2026-06-30"), 89, 32, 27, 141, 710, 2, "2026-10-29T12:00:00Z",
                "{\"type\":\"MultiPolygon\",\"coordinates\":[[[[-55.59,-12.84],[-55.5872,-12.84],[-55.5872,-12.8373],[-55.59,-12.8373],[-55.59,-12.84]]]]}"));
        results.seasons.add(seasonRow(1, "2025-10-10", "2025-10-22", "2026-01-27", "2026-02-08", 47.0));
        results.seasons.add(seasonRow(2, "2026-02-26", "2026-02-26", "2026-05-27", "2026-06-10", null));
        LocalDate day = LocalDate.parse("2025-08-03");
        for (int i = 0; i < 30; i++, day = day.plusDays(10)) {
            double evi = 0.15 + 0.6 * Math.sin(Math.PI * i / 15.0) * Math.sin(Math.PI * i / 15.0);
            results.series.add(new SeriesPoint("s2", day, evi, evi * 4, null));
            results.series.add(new SeriesPoint("s1", day.plusDays(3), null, null, -18 + 6 * evi));
        }
        day = LocalDate.parse("2025-08-03");
        for (int i = 0; i < 300; i++, day = day.plusDays(1)) {
            double active = i < 70 ? 0 : i < 180 ? 80 : 10;
            results.states.add(new StatePoint(day, "ACTIVE", active));
            results.states.add(new StatePoint(day, "PRE_PLANTING", i < 70 ? 95 : 0));
            results.states.add(new StatePoint(day, "HARVESTED", i < 70 ? 0 : 100 - active - 5));
            results.states.add(new StatePoint(day, "UNKNOWN", 5));
        }
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                double w = -55.59 + c * 0.0009;
                double s = -12.84 + r * 0.0009;
                String square = String.format(java.util.Locale.ROOT, "{\"type\":\"Polygon\",\"coordinates\":[[[%f,%f],[%f,%f],[%f,%f],[%f,%f],[%f,%f]]]}",
                        w, s, w + 0.0009, s, w + 0.0009, s + 0.0009, w, s + 0.0009, w, s);
                LocalDate p = LocalDate.parse("2025-10-08").plusDays(3L * (r * 3 + c));
                results.blocks.add(new BlockCell(r * 3 + c, square, r == 2 && c == 2 ? null : p, r == 2 && c == 2 ? null : p.plusDays(8),
                        LocalDate.parse("2026-01-28").plusDays(c * 4), LocalDate.parse("2026-02-06").plusDays(c * 4)));
            }
        }
    }

    static Map<String, Object> seasonRow(int no, String plantingFrom, String plantingTo, String harvestFrom, String harvestTo, Double fallow) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (String c : GoldCalendarResults.SEASON_COLUMNS) row.put(c, null);
        row.put("season_no", no);
        row.put("planting_from", LocalDate.parse(plantingFrom));
        row.put("planting_to", LocalDate.parse(plantingTo));
        row.put("emergence_from", LocalDate.parse(plantingFrom).plusDays(15));
        row.put("emergence_to", LocalDate.parse(plantingTo).plusDays(15));
        row.put("senescence_from", LocalDate.parse(harvestFrom).minusDays(30));
        row.put("senescence_to", LocalDate.parse(harvestFrom).minusDays(10));
        row.put("harvest_from", LocalDate.parse(harvestFrom));
        row.put("harvest_to", LocalDate.parse(harvestTo));
        row.put("planting_p10", LocalDate.parse("2025-10-10"));
        row.put("planting_p50", LocalDate.parse("2025-10-18"));
        row.put("planting_p90", LocalDate.parse("2025-10-22"));
        row.put("planting_uncertainty_days", 12.0);
        row.put("harvest_s1_share", 0.9);
        row.put("peak_evi", 0.8712);
        row.put("peak_evi_on", LocalDate.parse("2025-12-14"));
        row.put("cycle_mid_days", 113.0);
        row.put("cycle_min_days", 96.0);
        row.put("cycle_max_days", 133.0);
        row.put("drying_mid_days", 17.5);
        row.put("fallow_mid_days", fallow);
        return row;
    }

    @Test
    void definitionIsValidForTheRegistry() {
        new BlockRegistry(List.of(block(new FakeRuns(), new FakeResults())));
    }

    // --- request_sha ----------------------------------------------------------------------------

    @Test
    void requestShaIsTheOneOfIsCropCalendar() throws Exception {
        // tests/crop_calendar/test_request.py, test_a_known_request_sha (is-geo-sam, branch feat/is-crop-calendar)
        String request = "{\"requestId\": \"abc\", \"to\": \"2026-06-30\", \"from\": \"2025-08-01\","
                + " \"params\": {\"green_evi\": 0.55, \"relative_orbit\": null},"
                + " \"aoi\": {\"type\": \"Feature\", \"properties\": {\"name\": \"Talhao 1\"},"
                + " \"geometry\": {\"type\": \"Polygon\", \"coordinates\": [[[-55.59, -12.84],"
                + " [-55.5872, -12.84], [-55.5872, -12.8373], [-55.59, -12.8373], [-55.59, -12.84]]]}}}";
        Map<?, ?> parsed = JSON.readValue(request, Map.class);
        Map<Object, Object> hashed = new LinkedHashMap<>(parsed);
        hashed.remove("requestId");
        assertEquals("{\"aoi\":{\"geometry\":{\"coordinates\":[[[-55.59,-12.84],[-55.5872,-12.84],"
                + "[-55.5872,-12.8373],[-55.59,-12.8373],[-55.59,-12.84]]],\"type\":\"Polygon\"},"
                + "\"properties\":{\"name\":\"Talhao 1\"},\"type\":\"Feature\"},\"from\":\"2025-08-01\","
                + "\"params\":{\"green_evi\":0.55,\"relative_orbit\":null},\"to\":\"2026-06-30\"}",
                new String(CropCalendarRequest.canonical(hashed), StandardCharsets.UTF_8));
        assertEquals("3b3767c611ef06410ec7d827eec30fb0ffadad3bcb8eed532139f17f0bc64d6f", CropCalendarRequest.requestSha(request));
    }

    @Test
    void numbersAreCanonicalAsInEcmaScript() {
        assertEquals("[10,0.000001,1e-7,4.5,1e+30]", new String(CropCalendarRequest.canonical(
                List.of(10.0, 0.000001, 0.0000001, 4.50, 1e30)), StandardCharsets.UTF_8));
    }

    @Test
    void theRequestCarriesTheFieldInSirgasAndItsSha() throws Exception {
        CropCalendarRequest request = CropCalendarRequest.of(square(), LocalDate.parse("2025-08-01"), LocalDate.parse("2026-06-30"),
                Map.of("relative_orbit", 39L));
        String text = new String(request.json(), StandardCharsets.UTF_8);
        assertTrue(text.startsWith("{\"aoi\":{\"coordinates\":[[[-55.59,-12.84],"), text);
        assertTrue(text.contains("\"crs\":{\"properties\":{\"name\":\"EPSG:4674\"},\"type\":\"name\"}"), text);
        assertTrue(text.contains("\"from\":\"2025-08-01\",\"params\":{\"relative_orbit\":39}"), text);
        assertTrue(text.contains("\"requestSha\":\"" + request.requestSha() + "\""), text);
        assertEquals("reports-cropcal-" + request.requestSha().substring(0, 12) + ".json", request.fileName());
        // the file the script reads hashes to the sha it carries (requestId and requestSha are not hashed)
        assertEquals(request.requestSha(), CropCalendarRequest.requestSha(text));
        // the same field and period with numbers of other Java types is the same request
        Map<String, Object> ints = Map.of("type", "Polygon", "coordinates", List.of(List.of(List.of(-55.59, -12.84),
                List.of(-55.5872, -12.84), List.of(-55.5872, -12.8373), List.of(-55.59, -12.8373), List.of(-55.59, -12.84))));
        assertEquals(request.requestSha(), CropCalendarRequest.of(ints, LocalDate.parse("2025-08-01"), LocalDate.parse("2026-06-30"),
                Map.of("relative_orbit", 39.0)).requestSha());
        assertNotEquals(request.requestSha(), CropCalendarRequest.of(square(), LocalDate.parse("2025-08-01"),
                LocalDate.parse("2026-06-30"), Map.of()).requestSha());
        // no params: no "params" key at all
        assertFalse(new String(CropCalendarRequest.of(square(), LocalDate.parse("2025-08-01"), LocalDate.parse("2026-06-30"),
                Map.of()).json(), StandardCharsets.UTF_8).contains("params"));
    }

    @Test
    void periodDefaultsToTheLastTwelveMonths() {
        LocalDate today = LocalDate.parse("2026-10-30");
        LocalDate[] p = CropCalendarBlock.period(null, null, today);
        assertEquals(LocalDate.parse("2025-10-30"), p[0]);
        assertEquals(today, p[1]);
        assertEquals(LocalDate.parse("2026-06-30"), CropCalendarBlock.period("2025-06-30", null, today)[1]);
        assertEquals(LocalDate.parse("2025-06-30"), CropCalendarBlock.period(null, "2026-06-30", today)[0]);
        assertThrows(IllegalArgumentException.class, () -> CropCalendarBlock.period("2026-01-01", "2025-01-01", today));
        assertThrows(IllegalArgumentException.class, () -> CropCalendarBlock.period("2023-01-01", "2026-01-01", today));
        assertThrows(IllegalArgumentException.class, () -> CropCalendarBlock.params(Map.of("relativeOrbit", 39.5)));
        assertEquals(Map.of("relative_orbit", 39L, "sowing_to_visible_days", 10L),
                CropCalendarBlock.params(Map.of("relativeOrbit", 39, "sowingToVisibleDays", 10.0)));
    }

    // --- status mapping -------------------------------------------------------------------------

    @Test
    void decisionFollowsTheRunsOfTheRequest() {
        assertEquals(CropCalendarBlock.Action.ASK, CropCalendarBlock.decide(List.of()).action());
        // finished: shown when final, or when it ended in the last 24 h
        assertEquals(CropCalendarBlock.Action.SHOW, CropCalendarBlock.decide(List.of(summary("COMPLETED", 999_999.0, true, 0))).action());
        assertEquals(CropCalendarBlock.Action.SHOW, CropCalendarBlock.decide(List.of(summary("NO_SEASON", 3_600.0, false, 0))).action());
        assertEquals(CropCalendarBlock.Action.ASK, CropCalendarBlock.decide(List.of(summary("COMPLETED", 200_000.0, false, 0))).action());
        // running: waited for while alive
        assertEquals(CropCalendarBlock.Action.PROCESSING, CropCalendarBlock.decide(List.of(summary("RUNNING", null, false, 120))).action());
        assertEquals(CropCalendarBlock.Action.ASK, CropCalendarBlock.decide(List.of(summary("RUNNING", null, false, 7_200))).action());
        // a failure of the last minutes is the answer; an older one is asked again; INTERRUPTED always is
        assertEquals(CropCalendarBlock.Action.FAILED, CropCalendarBlock.decide(List.of(summary("FAILED", 60.0, false, 400))).action());
        assertEquals(CropCalendarBlock.Action.ASK, CropCalendarBlock.decide(List.of(summary("FAILED", 3_600.0, false, 4_000))).action());
        assertEquals(CropCalendarBlock.Action.ASK, CropCalendarBlock.decide(List.of(summary("INTERRUPTED", 30.0, false, 400))).action());
        // a final run wins over everything else
        assertEquals("COMPLETED", CropCalendarBlock.decide(List.of(summary("COMPLETED", 999_999.0, true, 0),
                summary("FAILED", 60.0, false, 400), summary("RUNNING", null, false, 60))).run().status());
    }

    // --- execution ------------------------------------------------------------------------------

    @Test
    void aFinishedCalendarIsShownWithoutRunning() throws Exception {
        FakeRuns runs = new FakeRuns();
        FakeResults results = new FakeResults();
        Map<String, Object> in = inputs();
        completed(results, sha(in));

        Map<String, Object> out = block(runs, results).execute(in, META);

        assertEquals(0, runs.starts);
        assertTrue(Files.list(staging).findAny().isEmpty(), "nothing written to staging");
        assertEquals("COMPLETED", out.get("status"));
        assertEquals(true, out.get("ready"));
        assertEquals(false, out.get("processing"));
        assertEquals(true, out.get("reused"));
        assertEquals(737.12, out.get("areaHa"));
        assertEquals(141, out.get("orbit"));
        assertEquals("01/08/2025", out.get("fromLabel"));
        assertEquals("29/10/2026", out.get("computedAtLabel"));
        assertEquals(true, out.get("hasSeasons"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> seasons = (List<Map<String, Object>>) out.get("seasons");
        assertEquals(2, seasons.size());
        Map<String, Object> first = seasons.get(0);
        assertEquals("Safra 1", first.get("label"));
        assertEquals("2025-10-10", first.get("plantingFrom"));
        assertEquals("10/10/2025 a 22/10/2025", first.get("plantingText"));
        assertEquals("18/10/2025", first.get("plantingP50Label"));
        assertNull(first.get("senescenceP10Label"));
        assertEquals(90.0, first.get("harvestS1SharePct"));
        assertEquals(0.87, first.get("peakEvi"));
        assertEquals(113.0, first.get("cycleMidDays"));
        assertEquals(17.5, first.get("dryingMidDays"));
        assertEquals(47.0, first.get("fallowMidDays"));
        assertEquals(true, first.get("hasFallow"));
        assertEquals("26/02/2026", seasons.get(1).get("plantingText"), "one day when both ends are the same");
        assertEquals(false, seasons.get(1).get("hasFallow"));
        image((String) out.get("seriesChart"), 1000);
        image((String) out.get("stateChart"), 1000);
        image((String) out.get("plantingMap"), 900);
        image((String) out.get("harvestMap"), 900);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> legend = (List<Map<String, Object>>) out.get("plantingLegend");
        assertEquals("sem data", legend.get(legend.size() - 1).get("label"));
        assertEquals(5, ((List<?>) out.get("stateLegend")).size());
    }

    @Test
    void aNewFieldIsSentToTheScriptAndReadWhenItEnds() throws Exception {
        FakeRuns runs = new FakeRuns();
        FakeResults results = new FakeResults();
        Map<String, Object> in = inputs();
        String sha = sha(in);
        runs.onDone = () -> completed(results, sha);

        Map<String, Object> out = block(runs, results).execute(in, META);

        assertEquals(1, runs.starts);
        assertEquals("p-1", runs.startedProject);
        String file = (String) runs.startedParams.get("file");
        assertEquals(Map.of("file", file, "appId", "reports"), runs.startedParams);
        assertEquals("user-42", runs.startedFor);
        assertTrue(Files.isRegularFile(staging.resolve(file)), "the script deletes it, not the Block");
        assertEquals(sha, CropCalendarRequest.requestSha(Files.readString(staging.resolve(file))));
        assertEquals("COMPLETED", out.get("status"));
        assertEquals(false, out.get("reused"));
        assertEquals("t-1", out.get("triggerId"));
    }

    @Test
    void aSlowRunIsLeftRunningAndAnsweredProcessing() throws Exception {
        FakeRuns runs = new FakeRuns();
        runs.pollsUntilDone = Integer.MAX_VALUE;
        Map<String, Object> in = inputs();
        in.put("waitSeconds", 0);

        Map<String, Object> out = block(runs, new FakeResults()).execute(in, META);

        assertEquals("PROCESSING", out.get("status"));
        assertEquals(true, out.get("processing"));
        assertEquals(false, out.get("ready"));
        assertEquals("t-1", out.get("triggerId"));
        assertEquals(List.of(), out.get("seasons"));
        assertTrue(((String) out.get("message")).contains("generate the report again"));
        assertEquals(1, Files.list(staging).count());

        // the next report finds the file still waiting, and then the run RUNNING: no second start
        assertEquals("PROCESSING", block(runs, new FakeResults()).execute(in, META).get("status"));
        FakeResults running = new FakeResults();
        running.runs.put(sha(in), List.of(summary("RUNNING", null, false, 90)));
        Files.list(staging).forEach(f -> f.toFile().delete());
        assertEquals("PROCESSING", block(runs, running).execute(in, META).get("status"));
        assertEquals(1, runs.starts);
    }

    @Test
    void noSeasonIsAnAnswer() throws Exception {
        FakeResults results = new FakeResults();
        Map<String, Object> in = inputs();
        results.runs.put(sha(in), List.of(summary("NO_SEASON", 50.0, false, 100)));
        results.details.put("run-1", new RunDetail("run-1", "NO_SEASON", "no green season (EVI >= green_evi) in the field series",
                12.0, LocalDate.parse("2025-08-01"), LocalDate.parse("2026-06-30"), 70, 20, 25, 141, 12, 0, "2026-10-29T12:00:00Z", null));
        results.series.add(new SeriesPoint("s2", LocalDate.parse("2025-09-01"), 0.18, 0.5, null));
        results.series.add(new SeriesPoint("s2", LocalDate.parse("2025-10-01"), 0.21, 0.6, null));

        Map<String, Object> out = block(new FakeRuns(), results).execute(in, META);

        assertEquals("NO_SEASON", out.get("status"));
        assertEquals(true, out.get("ready"));
        assertEquals(false, out.get("hasSeasons"));
        assertTrue(((String) out.get("message")).contains("no green season"));
        assertNotNull(out.get("seriesChart"));
        assertNull(out.get("plantingMap"));
    }

    @Test
    void aFailedRunIsShownWithTheScriptsReason() throws Exception {
        FakeRuns runs = new FakeRuns();
        runs.result = "FAILED";
        runs.log = "INFO start\nIS_CROP_CALENDAR_RESULT {\"file\": \"x.json\", \"status\": \"BAD_REQUEST\", \"error\": \"aoi has 0.40 ha\"}\n";
        Map<String, Object> out = block(runs, new FakeResults()).execute(inputs(), META);
        assertEquals("FAILED", out.get("status"));
        assertTrue(((String) out.get("message")).contains("BAD_REQUEST, aoi has 0.40 ha"), (String) out.get("message"));

        FakeResults failed = new FakeResults();
        failed.runs.put(sha(inputs()), List.of(summary("FAILED", 30.0, false, 300)));
        Map<String, Object> again = block(new FakeRuns(), failed).execute(inputs(), META);
        assertEquals("FAILED", again.get("status"));
        assertTrue(((String) again.get("message")).contains(FAILED_ERROR));
    }

    @Test
    void theProjectComesFromTheGrantWhenNotSet() throws Exception {
        Files.writeString(staging.resolve("reports-workflows.json"),
                "{\"workflows\":[{\"projectId\":\"p-9\",\"workflow\":\"is-geo-sam\"},{\"projectId\":\"p-7\",\"workflow\":\"is-crop-calendar\"}]}");
        FakeRuns runs = new FakeRuns();
        runs.pollsUntilDone = Integer.MAX_VALUE;
        Map<String, Object> in = inputs();
        in.put("waitSeconds", 0);
        block(runs, new FakeResults(), "").execute(in, META);
        assertEquals("p-7", runs.startedProject);
    }

    @Test
    void withoutAProjectOrGrantTheBlockSaysWhatToSet() {
        Exception e = assertThrows(IllegalArgumentException.class,
                () -> block(new FakeRuns(), new FakeResults(), "").execute(inputs(), META));
        assertTrue(e.getMessage().contains("REPORTS_CROPCAL_PROJECT_ID"), e.getMessage());
    }

    @Test
    void aWorkflowNotGrantedFailsBeforeWritingAnything() throws Exception {
        FakeRuns runs = new FakeRuns();
        runs.grants = List.of(new WorkflowRuns.GrantedWorkflow("other", true, null));
        Exception e = assertThrows(IllegalArgumentException.class, () -> block(runs, new FakeResults()).execute(inputs(), META));
        assertTrue(e.getMessage().contains("not granted"), e.getMessage());
        assertTrue(Files.list(staging).findAny().isEmpty());
        assertEquals(0, runs.starts);
    }

    @Test
    void theDateMapColorsEachBlockByItsMiddleDate() throws Exception {
        FakeResults results = new FakeResults();
        completed(results, "x");
        CalendarCharts.MapImage map = new CalendarCharts(false).dateMap(results.details.get("run-1").aoiGeoJson(), results.blocks,
                c -> new LocalDate[] {c.plantingFrom(), c.plantingTo()}, 300, 300);
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(map.png()));
        assertEquals(300, image.getWidth());
        assertEquals(6, map.legend().size(), "five classes and 'sem data'");
        assertEquals(0, CalendarCharts.classOf(LocalDate.parse("2025-10-12"), LocalDate.parse("2025-10-12"), 24, 5));
        assertEquals(4, CalendarCharts.classOf(LocalDate.parse("2025-11-05"), LocalDate.parse("2025-10-12"), 24, 5));
    }

    static void image(String dataUri, int width) throws Exception {
        assertNotNull(dataUri);
        assertTrue(dataUri.startsWith("data:image/png;base64,"));
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(dataUri.substring(22))));
        assertEquals(width, image.getWidth());
    }
}
