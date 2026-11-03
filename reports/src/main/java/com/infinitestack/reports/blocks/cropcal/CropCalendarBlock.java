package com.infinitestack.reports.blocks.cropcal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.infinitestack.reports.blocks.skillslab.WorkflowRuns;
import com.infinitestack.reports.engine.DataType;
import com.infinitestack.reports.engine.block.BlockDefinition;
import com.infinitestack.reports.engine.block.BlockDocs;
import com.infinitestack.reports.engine.block.BlockPort;
import com.infinitestack.reports.engine.block.BlockQueries;
import com.infinitestack.reports.engine.block.ExecutionMetadata;
import com.infinitestack.reports.engine.block.ReportBlock;

/**
 * {@code cropcal-field-calendar}: the crop calendar of one field (planting, senescence and harvest per
 * season and per 100 m block) from Sentinel-2 and Sentinel-1, computed by the Skills Lab package
 * is-crop-calendar (repo is-geo-sam, skillslab/is-crop-calendar).
 *
 * <p>A run without cache takes 4 to 7 minutes, more than a report's 120 s, so the Block works like
 * {@code isgeosam-field-candidates}:
 * <ol>
 * <li>it builds the request (aoi in EPSG:4674, from, to, params) and its request_sha (RFC 8785 + sha256,
 * the identity the script computes the same way, {@link CropCalendarRequest});</li>
 * <li>a finished run of that request in {@code gold.is_crop_calendar_run} that is final (ended more than 7
 * days after "to") or recent (ended in the last 24 h) is shown at once;</li>
 * <li>a run still RUNNING, or the request still waiting in staging, answers PROCESSING;</li>
 * <li>otherwise it writes the request to the lake staging, starts the is-crop-calendar workflow through
 * the host's workflow port and waits up to {@code waitSeconds}; still going after that, PROCESSING, and
 * the next execution of the report reads the result from gold.</li>
 * </ol>
 * The script owns the gold tables and deletes the staging file when it ends.
 */
@Component
public class CropCalendarBlock implements ReportBlock {

    static final String RESULT_MARKER = "IS_CROP_CALENDAR_RESULT ";

    static final String COMPLETED = "COMPLETED";
    static final String NO_SEASON = "NO_SEASON";
    static final String PROCESSING = "PROCESSING";
    static final String FAILED = "FAILED";
    static final String RUNNING = "RUNNING";

    static final int DEFAULT_WAIT_SECONDS = 60;
    static final int MAX_WAIT_SECONDS = 110;
    static final Duration BLOCK_TIMEOUT = Duration.ofSeconds(118);
    /** A RUNNING run or a staging file older than this is taken as dead (backend restarted) and asked again. */
    static final Duration STALE_AFTER = Duration.ofMinutes(30);
    /** A finished run that is not final yet (its period ends near today) is still shown for this long. */
    static final Duration FRESH_FOR = Duration.ofHours(24);
    /** A failure this recent is shown instead of asking again (the report generated again right after PROCESSING). */
    static final Duration SHOW_FAILURE_FOR = Duration.ofMinutes(10);
    static final int MAX_DAYS = 730;
    static final int MAX_MAP_BLOCKS = 20_000;
    static final int LOG_TAIL_BYTES = 65_536;

    static final DateTimeFormatter BR_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final WorkflowRuns runs;
    private final CalendarResults results;
    private final CropCalendarSettings settings;
    private final Duration pollInterval;
    private final CalendarCharts charts = new CalendarCharts(true);
    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    public CropCalendarBlock(WorkflowRuns runs, BlockQueries queries, CropCalendarSettings settings) {
        this(runs, new GoldCalendarResults(queries), settings, Duration.ofSeconds(2));
    }

    CropCalendarBlock(WorkflowRuns runs, CalendarResults results, CropCalendarSettings settings, Duration pollInterval) {
        this.runs = runs;
        this.results = results;
        this.settings = settings;
        this.pollInterval = pollInterval;
    }

    @Override
    public BlockDefinition definition() {
        return new BlockDefinition("cropcal-field-calendar", "Field crop calendar (S2 + S1)",
                "Planting, senescence and harvest of each season of one field, as intervals, from Sentinel-2 and Sentinel-1 (is-crop-calendar)",
                "1.0.0",
                List.of(
                        BlockPort.required("geometry", DataType.GEOMETRY,
                                "ONE field (talhao), GeoJSON in SIRGAS 2000 lon/lat; connect report-area.geometry"),
                        BlockPort.optional("from", DataType.DATE, "start of the period, YYYY-MM-DD; default one year before 'to'"),
                        BlockPort.optional("to", DataType.DATE, "end of the period, YYYY-MM-DD; default today"),
                        BlockPort.optional("relativeOrbit", DataType.NUMBER, "Sentinel-1 relative orbit; default the most frequent over the field"),
                        BlockPort.optional("sowingToVisibleDays", DataType.NUMBER,
                                "days from sowing to the crop showing in the EVI (planting = emergence minus this); default 15"),
                        BlockPort.optional("waitSeconds", DataType.NUMBER,
                                "how long to wait for a new calendar, 0 to " + MAX_WAIT_SECONDS + " seconds; default "
                                        + DEFAULT_WAIT_SECONDS + "; the run goes on after it")),
                List.of(
                        BlockPort.required("status", DataType.STRING, "COMPLETED, NO_SEASON, PROCESSING or FAILED"),
                        BlockPort.required("ready", DataType.BOOLEAN, "the calendar is computed (COMPLETED or NO_SEASON)"),
                        BlockPort.required("processing", DataType.BOOLEAN, "still computing: generate the report again in a few minutes"),
                        BlockPort.optional("message", DataType.STRING, "why there is no calendar (no season, still running, failure)"),
                        BlockPort.optional("runId", DataType.STRING, "is-crop-calendar run (gold.is_crop_calendar_run.run_id)"),
                        BlockPort.optional("triggerId", DataType.STRING, "the workflow run started by this execution, as shown in Workloads"),
                        BlockPort.optional("requestSha", DataType.STRING, "identity of the request (sha256 of its RFC 8785 JSON)"),
                        BlockPort.required("reused", DataType.BOOLEAN, "the calendar came from an earlier run of the same request"),
                        BlockPort.optional("computedAt", DataType.STRING, "when the run finished, UTC ISO-8601"),
                        BlockPort.optional("computedAtLabel", DataType.STRING, "the same as dd/MM/yyyy"),
                        BlockPort.required("from", DataType.DATE, "start of the period asked"),
                        BlockPort.required("to", DataType.DATE, "end of the period asked"),
                        BlockPort.required("fromLabel", DataType.STRING, "from as dd/MM/yyyy"),
                        BlockPort.required("toLabel", DataType.STRING, "to as dd/MM/yyyy"),
                        BlockPort.optional("areaHa", DataType.NUMBER, "field area the script measured, hectares"),
                        BlockPort.optional("s2Dates", DataType.NUMBER, "Sentinel-2 dates found in the period"),
                        BlockPort.optional("s2ClearDates", DataType.NUMBER, "of those, with at least half of the field clear (the field series)"),
                        BlockPort.optional("s1Passes", DataType.NUMBER, "Sentinel-1 passes used (covering >= 90% of the field)"),
                        BlockPort.optional("orbit", DataType.NUMBER, "Sentinel-1 relative orbit used"),
                        BlockPort.optional("blocks", DataType.NUMBER, "100 m blocks of the field"),
                        BlockPort.required("hasSeasons", DataType.BOOLEAN, "at least one season"),
                        BlockPort.required("seasons", DataType.ARRAY,
                                "{seasonNo, label, emergence/planting/senescence/harvest From, To and Text (dd/MM/yyyy a dd/MM/yyyy), "
                                        + "planting/senescence/harvest P10, P50, P90 (+Label), UncertaintyDays and Blocks, harvestS1SharePct, "
                                        + "peakEvi, peakEviOn, peakEviOnLabel, cycle/drying/fallow MinDays, MaxDays, MidDays, MidP10Days, MidP90Days, hasFallow}"),
                        BlockPort.optional("seriesChart", DataType.STRING,
                                "EVI and CIre (Sentinel-2) and VH dB (Sentinel-1, right axis) of the field, planting, senescence and harvest shaded; PNG data URI"),
                        BlockPort.optional("stateChart", DataType.STRING, "share of the field in each state per day, stacked; PNG data URI"),
                        BlockPort.required("stateLegend", DataType.ARRAY, "{state, label, color} of the state chart"),
                        BlockPort.optional("plantingMap", DataType.STRING, "blocks of season 1 colored by the middle of their planting interval; PNG data URI"),
                        BlockPort.required("plantingLegend", DataType.ARRAY, "{color, label} of the planting map"),
                        BlockPort.optional("harvestMap", DataType.STRING, "blocks of season 1 colored by the middle of their harvest interval; PNG data URI"),
                        BlockPort.required("harvestLegend", DataType.ARRAY, "{color, label} of the harvest map")),
                GoldCalendarResults.REQUIREMENTS,
                BLOCK_TIMEOUT);
    }

    @Override
    public BlockDocs docs() {
        return new BlockDocs("Skills Lab",
                "The crop calendar of ONE field: for each season, the intervals in which planting, senescence and harvest certainly "
                        + "happened, for the field and for each 100 m block, and the days of cycle, drying and fallow. Computed by the Skills "
                        + "Lab package is-crop-calendar from Sentinel-2 (EVI, red-edge CIre) and Sentinel-1 (VH radar).",
                List.of(
                        "Builds the request (the field in EPSG:4674, from, to and the optional parameters) and its request_sha "
                                + "(sha256 of the RFC 8785 canonical JSON, the identity the script computes the same way).",
                        "Looks in gold.is_crop_calendar_run for a finished run (COMPLETED or NO_SEASON) of that request, whatever the "
                                + "parameters of the script's .env (params_hash): the latest one is shown when it is final (ended more than "
                                + GoldCalendarResults.SETTLE_DAYS + " days after 'to') or ended in the last 24 hours.",
                        "A run of the request still RUNNING, or the request waiting in the lake staging, answers PROCESSING.",
                        "Otherwise writes the request to the lake staging and starts the is-crop-calendar workflow on behalf of the user. "
                                + "Waits up to waitSeconds: a run without cache takes 4 to 7 minutes, so the first report usually comes "
                                + "out PROCESSING and the run goes on; generating the report again a few minutes later reads it from gold.",
                        "Reads the run, its seasons, series, states and the blocks of season 1, and draws the charts and maps."),
                List.of(
                        "Connect geometry to report-area.geometry. It must be ONE field (or one homogeneous management area): the seasons "
                                + "of the field are the reference of its blocks; a whole farm with different crops gives a meaningless calendar.",
                        "from/to: take the period from before the first planting to after the last harvest; at most " + MAX_DAYS + " days. "
                                + "Default: the last 12 months up to today.",
                        "In the template: {{#<node>.ready}} ... {{/<node>.ready}}, {{#<node>.processing}} a notice to generate again {{/<node>.processing}}, "
                                + "{{#<node>.seasons}}{{label}}: {{plantingText}}{{/<node>.seasons}}."),
                List.of(
                        "Each date is an interval in which the event certainly happened, not a day; the 10/50/90% use the middle of each block's interval only to summarize.",
                        "Planting is the coarsest estimate: the emergence seen in the EVI minus sowingToVisibleDays (15 by default).",
                        "Clouds: a season without clear dates before and after does not appear; senescence without clear dates at the end of the cycle takes the harvest interval.",
                        "Low EVI is not always harvest (desiccated soybean, dry corn standing); the radar narrows the harvest when it sees the VH drop.",
                        "It does not identify the crop.",
                        "Images from Microsoft Planetary Computer (sentinel-2-l2a, sentinel-1-rtc); the Skills Lab needs outbound HTTPS to "
                                + "planetarycomputer.microsoft.com and *.blob.core.windows.net.",
                        "Needs, on the instance: is-crop-calendar installed in a Skills Lab project with a workflow is-crop-calendar running "
                                + "is-crop-calendar/main.py, that workflow granted to the reports app in /opt/infinitestack/etc/reports-workflows.json "
                                + "(the project is taken from there, or from reports.cropcal.project-id), write access to the lake staging, and the "
                                + "gold.is_crop_calendar_* tables registered for the app (one run with appId reports).",
                        "Works only inside the IS (it needs the host's internal token)."),
                "is-crop-calendar (Skills Lab) over Sentinel-2 L2A and Sentinel-1 RTC of Microsoft Planetary Computer; gold.is_crop_calendar_*",
                "{{#<node>.ready}}{{#<node>.seasons}}<p>{{label}}: plantio {{plantingText}}, colheita {{harvestText}}</p>{{/<node>.seasons}}"
                        + "<img src=\"{{<node>.seriesChart}}\">{{/<node>.ready}}");
    }

    // --- execution ------------------------------------------------------------------------------

    /** What to do with the runs gold already has for a request. */
    enum Action { SHOW, PROCESSING, FAILED, ASK }

    record Decision(Action action, CalendarResults.RunSummary run) {}

    /**
     * The decision on the latest run of each status of a request: a finished run that is final or fresh
     * is shown; a live RUNNING run is waited for; a failure of the last minutes is shown; otherwise the
     * script is asked again.
     */
    static Decision decide(List<CalendarResults.RunSummary> latest) {
        CalendarResults.RunSummary finished = latest.stream()
                .filter(r -> COMPLETED.equals(r.status()) || NO_SEASON.equals(r.status()))
                .min(Comparator.comparing(r -> r.finishedSecondsAgo() == null ? Double.MAX_VALUE : r.finishedSecondsAgo()))
                .orElse(null);
        if (finished != null && (finished.settled() || seconds(finished.finishedSecondsAgo()) < FRESH_FOR.toSeconds())) {
            return new Decision(Action.SHOW, finished);
        }
        for (CalendarResults.RunSummary r : latest) {
            if (RUNNING.equals(r.status()) && seconds(r.ageSeconds()) < STALE_AFTER.toSeconds()) return new Decision(Action.PROCESSING, r);
        }
        for (CalendarResults.RunSummary r : latest) {
            if (FAILED.equals(r.status()) && seconds(r.finishedSecondsAgo()) < SHOW_FAILURE_FOR.toSeconds()
                    && (finished == null || seconds(r.finishedSecondsAgo()) < seconds(finished.finishedSecondsAgo()))) {
                return new Decision(Action.FAILED, r);
            }
        }
        return new Decision(Action.ASK, null);
    }

    private static double seconds(Double value) {
        return value == null ? Double.MAX_VALUE : value;
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> inputs, ExecutionMetadata metadata) throws Exception {
        LocalDate[] period = period(inputs.get("from"), inputs.get("to"), LocalDate.now());
        CropCalendarRequest request = CropCalendarRequest.of(geometry(inputs.get("geometry")), period[0], period[1], params(inputs));

        Decision decision = decide(results.runs(request.requestSha()));
        switch (decision.action()) {
            case SHOW:
                return fromRun(request, period, decision.run(), true, null);
            case PROCESSING:
                return empty(PROCESSING, "the crop calendar of this field is being computed (started "
                        + Math.round(seconds(decision.run().ageSeconds()) / 60) + " min ago); generate the report again in a few minutes",
                        request, period, decision.run().runId(), decision.run().triggerId());
            case FAILED:
                return empty(FAILED, "is-crop-calendar failed: " + decision.run().error(), request, period, decision.run().runId(),
                        decision.run().triggerId());
            default:
                break;
        }
        Path file = settings.stagingDir().resolve(request.fileName());
        if (isPending(file)) {
            return empty(PROCESSING, "the crop calendar of this field is waiting to be computed; generate the report again in a few minutes",
                    request, period, null, null);
        }

        String projectId = settings.projectId().orElseThrow(() -> new IllegalArgumentException(settings.missingProjectMessage()));
        requireGranted(projectId, settings.workflow());
        write(file, request.json());
        Instant startedAt = Instant.now();
        String triggerId;
        try {
            triggerId = runs.start(projectId, settings.workflow(), settings.params(request.fileName()),
                    metadata == null ? null : metadata.userId());
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(file);
            throw e;
        }

        // The run is never stopped here: a run past the wait lands in gold and the next report reads it.
        WorkflowRuns.RunState state = waitUntilDone(projectId, triggerId, startedAt.plusSeconds(waitSeconds(inputs.get("waitSeconds"))));
        if (state == null) {
            return empty(PROCESSING, "the crop calendar did not finish within the wait (run " + triggerId
                    + " goes on; a run without cache takes 4 to 7 minutes); generate the report again in a few minutes",
                    request, period, null, triggerId);
        }
        Decision done = decide(results.runs(request.requestSha()));
        if (done.action() == Action.SHOW) return fromRun(request, period, done.run(), false, triggerId);
        if (done.action() == Action.FAILED) {
            return empty(FAILED, "is-crop-calendar failed: " + done.run().error(), request, period, done.run().runId(), triggerId);
        }
        return empty(FAILED, "is-crop-calendar run " + triggerId + " ended " + state.result() + ": " + failure(projectId, triggerId),
                request, period, null, triggerId);
    }

    private Map<String, Object> fromRun(CropCalendarRequest request, LocalDate[] period, CalendarResults.RunSummary summary,
                                        boolean reused, String triggerId) {
        CalendarResults.RunDetail run = results.run(summary.runId())
                .orElseThrow(() -> new IllegalStateException("run " + summary.runId() + " disappeared from gold.is_crop_calendar_run"));
        Map<String, Object> out = base(run.status(), NO_SEASON.equals(run.status())
                ? "no crop season found in the period" + (run.error() == null ? "" : ": " + run.error()) : null,
                request, period, run.runId(), triggerId != null ? triggerId : summary.triggerId());
        out.put("ready", true);
        out.put("reused", reused);
        out.put("computedAt", run.finishedAt());
        out.put("computedAtLabel", run.finishedAt() == null ? null : label(LocalDate.parse(run.finishedAt().substring(0, 10))));
        out.put("areaHa", run.areaHa() == null ? null : Math.round(run.areaHa() * 100) / 100.0);
        out.put("s2Dates", run.s2Dates());
        out.put("s2ClearDates", run.s2ClearDates());
        out.put("s1Passes", run.s1Passes());
        out.put("orbit", run.orbit());
        out.put("blocks", run.blocks());

        List<Map<String, Object>> rows = results.seasons(run.runId());
        List<Map<String, Object>> seasons = new ArrayList<>();
        List<CalendarCharts.Interval> intervals = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            seasons.add(season(row));
            intervals.add(new CalendarCharts.Interval(date(row, "planting_from"), date(row, "planting_to"), CalendarCharts.PLANTING));
            intervals.add(new CalendarCharts.Interval(date(row, "senescence_from"), date(row, "senescence_to"), CalendarCharts.SENESCENCE));
            intervals.add(new CalendarCharts.Interval(date(row, "harvest_from"), date(row, "harvest_to"), CalendarCharts.HARVEST));
        }
        out.put("hasSeasons", !seasons.isEmpty());
        out.put("seasons", seasons);

        List<CalendarResults.SeriesPoint> series = results.series(run.runId());
        out.put("seriesChart", series.isEmpty() ? null : dataUri(charts.series(series, intervals, 1000, 380)));
        List<CalendarResults.StatePoint> states = results.states(run.runId());
        out.put("stateChart", states.isEmpty() ? null : dataUri(charts.states(states, 1000, 300)));
        out.put("stateLegend", stateLegend());

        if (!rows.isEmpty()) {
            int first = ((Number) rows.get(0).get("season_no")).intValue();
            List<CalendarResults.BlockCell> cells = results.blocks(run.runId(), first, MAX_MAP_BLOCKS);
            if (!cells.isEmpty()) {
                CalendarCharts.MapImage planting = charts.dateMap(run.aoiGeoJson(), cells,
                        c -> new LocalDate[] {c.plantingFrom(), c.plantingTo()}, 900, 700);
                CalendarCharts.MapImage harvest = charts.dateMap(run.aoiGeoJson(), cells,
                        c -> new LocalDate[] {c.harvestFrom(), c.harvestTo()}, 900, 700);
                if (planting != null) {
                    out.put("plantingMap", dataUri(planting.png()));
                    out.put("plantingLegend", planting.legend());
                }
                if (harvest != null) {
                    out.put("harvestMap", dataUri(harvest.png()));
                    out.put("harvestLegend", harvest.legend());
                }
            }
        }
        return out;
    }

    /** One season row of gold as the template reads it. */
    static Map<String, Object> season(Map<String, Object> row) {
        Map<String, Object> s = new LinkedHashMap<>();
        int no = ((Number) row.get("season_no")).intValue();
        s.put("seasonNo", no);
        s.put("label", "Safra " + no);
        for (String event : List.of("emergence", "planting", "senescence", "harvest")) {
            LocalDate from = date(row, event + "_from");
            LocalDate to = date(row, event + "_to");
            s.put(event + "From", iso(from));
            s.put(event + "To", iso(to));
            s.put(event + "Text", interval(from, to));
        }
        for (String event : List.of("planting", "senescence", "harvest")) {
            for (String p : List.of("P10", "P50", "P90")) {
                LocalDate d = date(row, event + "_" + p.toLowerCase());
                s.put(event + p, iso(d));
                s.put(event + p + "Label", label(d));
            }
            s.put(event + "UncertaintyDays", rounded(row.get(event + "_uncertainty_days"), 1));
            s.put(event + "Blocks", row.get(event + "_blocks"));
        }
        Object share = row.get("harvest_s1_share");
        s.put("harvestS1SharePct", share instanceof Number n ? Math.round(n.doubleValue() * 1000) / 10.0 : null);
        s.put("peakEvi", rounded(row.get("peak_evi"), 2));
        s.put("peakEviOn", iso(date(row, "peak_evi_on")));
        s.put("peakEviOnLabel", label(date(row, "peak_evi_on")));
        for (String measure : List.of("cycle", "drying", "fallow")) {
            for (String part : List.of("min", "max", "mid", "mid_p10", "mid_p90")) {
                String key = measure + camel(part) + "Days";
                s.put(key, rounded(row.get(measure + "_" + part + "_days"), 1));
            }
        }
        s.put("hasFallow", s.get("fallowMidDays") != null);
        return s;
    }

    private Map<String, Object> empty(String status, String message, CropCalendarRequest request, LocalDate[] period,
                                      String runId, String triggerId) {
        Map<String, Object> out = base(status, message, request, period, runId, triggerId);
        out.put("ready", false);
        out.put("reused", false);
        out.put("computedAt", null);
        out.put("computedAtLabel", null);
        for (String key : List.of("areaHa", "s2Dates", "s2ClearDates", "s1Passes", "orbit", "blocks")) out.put(key, null);
        out.put("hasSeasons", false);
        out.put("seasons", List.of());
        out.put("seriesChart", null);
        out.put("stateChart", null);
        out.put("stateLegend", stateLegend());
        return out;
    }

    private static Map<String, Object> base(String status, String message, CropCalendarRequest request, LocalDate[] period,
                                            String runId, String triggerId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", status);
        out.put("processing", PROCESSING.equals(status));
        out.put("message", message);
        out.put("runId", runId);
        out.put("triggerId", triggerId);
        out.put("requestSha", request.requestSha());
        out.put("from", period[0].toString());
        out.put("to", period[1].toString());
        out.put("fromLabel", label(period[0]));
        out.put("toLabel", label(period[1]));
        out.put("plantingMap", null);
        out.put("plantingLegend", List.of());
        out.put("harvestMap", null);
        out.put("harvestLegend", List.of());
        return out;
    }

    private static List<Map<String, Object>> stateLegend() {
        List<Map<String, Object>> legend = new ArrayList<>();
        CalendarCharts.STATE_COLORS.forEach((state, color) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("state", state);
            item.put("label", CalendarCharts.STATE_LABELS.get(state));
            item.put("color", String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue()));
            legend.add(item);
        });
        return legend;
    }

    // --- inputs ---------------------------------------------------------------------------------

    /** {from, to}: to defaults to today, from to one year before it; to after from, at most 730 days. */
    static LocalDate[] period(Object fromValue, Object toValue, LocalDate today) {
        LocalDate to = parse(toValue, "to");
        LocalDate from = parse(fromValue, "from");
        if (to == null) to = from != null && from.plusYears(1).isBefore(today) ? from.plusYears(1) : today;
        if (from == null) from = to.minusYears(1);
        if (!to.isAfter(from)) throw new IllegalArgumentException("to (" + to + ") must be after from (" + from + ")");
        if (ChronoUnit.DAYS.between(from, to) > MAX_DAYS) {
            throw new IllegalArgumentException("from/to spans " + ChronoUnit.DAYS.between(from, to) + " days; at most " + MAX_DAYS);
        }
        return new LocalDate[] {from, to};
    }

    /** The calendar parameters the inputs override, by the script's names; empty when none. */
    static Map<String, Object> params(Map<String, Object> inputs) {
        Map<String, Object> params = new LinkedHashMap<>();
        Object orbit = inputs.get("relativeOrbit");
        if (orbit instanceof Number n) params.put("relative_orbit", whole(n, "relativeOrbit", 1, 175));
        Object sowing = inputs.get("sowingToVisibleDays");
        if (sowing instanceof Number n) params.put("sowing_to_visible_days", whole(n, "sowingToVisibleDays", 0, 60));
        return params;
    }

    private static long whole(Number n, String name, long min, long max) {
        if (n.doubleValue() != Math.rint(n.doubleValue()) || n.longValue() < min || n.longValue() > max) {
            throw new IllegalArgumentException(name + " must be a whole number from " + min + " to " + max + ", not " + n);
        }
        return n.longValue();
    }

    static int waitSeconds(Object value) {
        if (!(value instanceof Number n)) return DEFAULT_WAIT_SECONDS;
        return (int) Math.max(0, Math.min(MAX_WAIT_SECONDS, n.longValue()));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> geometry(Object value) {
        if (!(value instanceof Map<?, ?> m) || !(m.get("type") instanceof String)) {
            throw new IllegalArgumentException("geometry is not a GeoJSON object; connect report-area.geometry");
        }
        return (Map<String, Object>) m;
    }

    private static LocalDate parse(Object value, String name) {
        if (value == null || value.toString().isBlank()) return null;
        try {
            return LocalDate.parse(value.toString().trim());
        } catch (Exception e) {
            throw new IllegalArgumentException(name + " must be a date as YYYY-MM-DD, not '" + value + "'");
        }
    }

    // --- host -----------------------------------------------------------------------------------

    /** A request for this field already waits in staging (the script deletes the file when it ends). */
    private static boolean isPending(Path file) throws IOException {
        if (!Files.isRegularFile(file)) return false;
        return Files.getLastModifiedTime(file).toInstant().isAfter(Instant.now().minus(STALE_AFTER));
    }

    /** Write then rename, so the script never reads half a file. */
    private void write(Path file, byte[] content) throws IOException {
        if (!Files.isDirectory(settings.stagingDir())) {
            throw new IOException("the lake staging " + settings.stagingDir() + " does not exist on this host (reports.cropcal.staging-dir)");
        }
        Path part = file.resolveSibling(file.getFileName() + ".part");
        Files.write(part, content);
        Files.move(part, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private void requireGranted(String projectId, String workflow) throws IOException {
        List<WorkflowRuns.GrantedWorkflow> granted = runs.granted(projectId);
        for (WorkflowRuns.GrantedWorkflow grant : granted) {
            if (!grant.workflow().equals(workflow)) continue;
            if (!grant.exists()) {
                throw new IllegalArgumentException("workflow '" + workflow + "' is granted to the reports app but project "
                        + projectId + " has no such workflow any more");
            }
            return;
        }
        String others = granted.stream().map(WorkflowRuns.GrantedWorkflow::workflow).collect(Collectors.joining(", "));
        throw new IllegalArgumentException("workflow '" + workflow + "' of project " + projectId
                + " is not granted to the reports app: the IS admin lists it in " + settings.grantsFile()
                + " (granted in this project: " + (others.isEmpty() ? "none" : others) + ")");
    }

    /** @return the final state, or null when the deadline passed first */
    private WorkflowRuns.RunState waitUntilDone(String projectId, String triggerId, Instant deadline)
            throws IOException, InterruptedException {
        while (true) {
            WorkflowRuns.RunState state = runs.get(projectId, triggerId);
            if (state.done()) return state;
            if (!Instant.now().plus(pollInterval).isBefore(deadline)) return null;
            Thread.sleep(pollInterval.toMillis());
        }
    }

    /** The status and error of the script's last IS_CROP_CALENDAR_RESULT line, or the end of its log. */
    private String failure(String projectId, String triggerId) {
        try {
            WorkflowRuns.OutputSlice slice = runs.output(projectId, triggerId, 0, 1);
            slice = runs.output(projectId, triggerId, Math.max(0, slice.size() - LOG_TAIL_BYTES), LOG_TAIL_BYTES);
            String log = slice.content();
            int at = log.lastIndexOf(RESULT_MARKER);
            if (at >= 0) {
                String line = log.substring(at + RESULT_MARKER.length()).lines().findFirst().orElse("");
                JsonNode result = json.readTree(line);
                return result.path("status").asText("?") + (result.path("error").isTextual() ? ", " + result.path("error").asText() : "");
            }
            return log.length() > 1_500 ? "..." + log.substring(log.length() - 1_500) : log;
        } catch (Exception e) {
            return "the run's output could not be read (" + e.getMessage() + ")";
        }
    }

    // --- values ---------------------------------------------------------------------------------

    private static LocalDate date(Map<String, Object> row, String column) {
        Object value = row.get(column);
        if (value instanceof LocalDate d) return d;
        if (value instanceof java.sql.Date d) return d.toLocalDate();
        if (value instanceof String s && !s.isBlank()) return LocalDate.parse(s.substring(0, 10));
        return null;
    }

    private static String iso(LocalDate date) {
        return date == null ? null : date.toString();
    }

    static String label(LocalDate date) {
        return date == null ? null : date.format(BR_DATE);
    }

    /** "dd/MM/yyyy a dd/MM/yyyy", one date when both ends are the same day, "não observado" without an interval. */
    static String interval(LocalDate from, LocalDate to) {
        if (from == null || to == null) return "não observado";
        return from.equals(to) ? label(from) : label(from) + " a " + label(to);
    }

    private static Double rounded(Object value, int digits) {
        if (!(value instanceof Number n)) return null;
        double scale = Math.pow(10, digits);
        return Math.round(n.doubleValue() * scale) / scale;
    }

    private static String camel(String snake) {
        StringBuilder out = new StringBuilder();
        for (String part : snake.split("_")) out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        return out.toString();
    }

    private static String dataUri(byte[] png) {
        return "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(png);
    }
}
