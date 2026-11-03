package com.infinitestack.reports.blocks.cropcal;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import com.infinitestack.reports.engine.block.BlockQueries;
import com.infinitestack.reports.engine.block.DataRequirement;

/**
 * {@link CalendarResults} over the gold tables is-crop-calendar writes ({@code gold.is_crop_calendar_*},
 * created and registered by the script itself), through the Blocks' read-only pool.
 */
public class GoldCalendarResults implements CalendarResults {

    static final String SCHEMA = "gold";
    static final String RUN = "is_crop_calendar_run";
    static final String SEASON = "is_crop_calendar_season";
    static final String BLOCK = "is_crop_calendar_block";
    static final String SERIES = "is_crop_calendar_series";
    static final String STATE = "is_crop_calendar_state";

    /** Mirrors CROPCAL_SETTLE_DAYS of the script (default 7): a run that ended this long after "to" is final. */
    static final int SETTLE_DAYS = 7;
    static final Duration QUERY_TIMEOUT = Duration.ofSeconds(10);

    /** The season columns the Block reads (the schema of is_crop_calendar_season). */
    static final List<String> SEASON_COLUMNS = Stream.of(
            List.of("season_no", "blocks", "green_from", "emergence_from", "emergence_to", "planting_from", "planting_to",
                    "senescence_from", "senescence_to", "harvest_from", "harvest_to", "peak_evi", "peak_evi_on", "peak_cire",
                    "harvest_s1_share"),
            percentiles("planting"), percentiles("senescence"), percentiles("harvest"),
            days("cycle"), days("drying"), days("fallow")).flatMap(List::stream).toList();

    static final String RUNS_SQL = "SELECT DISTINCT ON (status) run_id::text AS run_id, status, error, trigger_id,"
            + " to_char(finished_at AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"') AS finished_at,"
            + " extract(epoch FROM now() - started_at)::float8 AS age_seconds,"
            + " extract(epoch FROM now() - finished_at)::float8 AS finished_seconds_ago,"
            + " coalesce(finished_at > (date_to + make_interval(days => " + SETTLE_DAYS + ")), false) AS settled"
            + " FROM " + SCHEMA + "." + RUN + " WHERE request_sha = ?"
            + " ORDER BY status, coalesce(finished_at, started_at) DESC NULLS LAST";

    static final String RUN_SQL = "SELECT run_id::text AS run_id, status, error, aoi_area_ha::float8 AS aoi_area_ha, date_from, date_to,"
            + " s2_dates, s2_clear_dates, s1_passes, orbit, blocks, seasons,"
            + " to_char(finished_at AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"') AS finished_at, ST_AsGeoJSON(aoi, 7) AS aoi_geojson"
            + " FROM " + SCHEMA + "." + RUN + " WHERE run_id = CAST(? AS uuid)";

    static final String SEASONS_SQL = "SELECT " + String.join(", ", SEASON_COLUMNS) + " FROM " + SCHEMA + "." + SEASON
            + " WHERE run_id = CAST(? AS uuid) ORDER BY season_no";

    static final String SERIES_SQL = "SELECT source, date, evi, cire, vh_db FROM " + SCHEMA + "." + SERIES
            + " WHERE run_id = CAST(? AS uuid) AND (source = 's1' OR in_field_series) ORDER BY date, source";

    static final String STATE_SQL = "SELECT date, state, area_pct FROM " + SCHEMA + "." + STATE
            + " WHERE run_id = CAST(? AS uuid) ORDER BY date, state";

    static final String BLOCKS_SQL = "SELECT block_id, ST_AsGeoJSON(geom, 7) AS geojson, planting_from, planting_to,"
            + " harvest_from, harvest_to FROM " + SCHEMA + "." + BLOCK
            + " WHERE run_id = CAST(? AS uuid) AND season_no = ? ORDER BY block_id LIMIT ?";

    /** Every table and column the SQL above reads (BLOCKS.md section 1). */
    static final List<DataRequirement> REQUIREMENTS = List.of(
            DataRequirement.table(SCHEMA, RUN, "run_id", "request_sha", "status", "error", "trigger_id", "aoi", "aoi_area_ha",
                    "date_from", "date_to", "s2_dates", "s2_clear_dates", "s1_passes", "orbit", "blocks", "seasons",
                    "started_at", "finished_at"),
            new DataRequirement(SCHEMA, SEASON, Stream.concat(Stream.of("run_id"), SEASON_COLUMNS.stream()).toList(), null),
            DataRequirement.table(SCHEMA, BLOCK, "run_id", "season_no", "block_id", "geom", "planting_from", "planting_to",
                    "harvest_from", "harvest_to"),
            DataRequirement.table(SCHEMA, SERIES, "run_id", "source", "date", "evi", "cire", "in_field_series", "vh_db"),
            DataRequirement.table(SCHEMA, STATE, "run_id", "date", "state", "area_pct"));

    private final BlockQueries queries;

    public GoldCalendarResults(BlockQueries queries) {
        this.queries = queries;
    }

    @Override
    public List<RunSummary> runs(String requestSha) {
        return queries.read(QUERY_TIMEOUT, jdbc -> jdbc.query(RUNS_SQL, (rs, n) -> new RunSummary(
                rs.getString("run_id"), rs.getString("status"), rs.getString("error"), rs.getString("trigger_id"),
                rs.getString("finished_at"), number(rs.getObject("age_seconds")), number(rs.getObject("finished_seconds_ago")),
                rs.getBoolean("settled")), requestSha));
    }

    @Override
    public Optional<RunDetail> run(String runId) {
        return queries.read(QUERY_TIMEOUT, jdbc -> jdbc.query(RUN_SQL, (rs, n) -> new RunDetail(
                rs.getString("run_id"), rs.getString("status"), rs.getString("error"), number(rs.getObject("aoi_area_ha")),
                date(rs, "date_from"), date(rs, "date_to"), whole(rs.getObject("s2_dates")), whole(rs.getObject("s2_clear_dates")),
                whole(rs.getObject("s1_passes")), whole(rs.getObject("orbit")), whole(rs.getObject("blocks")),
                whole(rs.getObject("seasons")), rs.getString("finished_at"), rs.getString("aoi_geojson")), runId))
                .stream().findFirst();
    }

    @Override
    public List<Map<String, Object>> seasons(String runId) {
        return queries.read(QUERY_TIMEOUT, jdbc -> jdbc.query(SEASONS_SQL, (rs, n) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            for (String column : SEASON_COLUMNS) {
                Object value = rs.getObject(column);
                row.put(column, value instanceof Date d ? d.toLocalDate()
                        : value instanceof Integer i ? i
                        : value instanceof Number num ? (Object) num.doubleValue() : value);
            }
            return row;
        }, runId));
    }

    @Override
    public List<SeriesPoint> series(String runId) {
        return queries.read(QUERY_TIMEOUT, jdbc -> jdbc.query(SERIES_SQL, (rs, n) -> new SeriesPoint(rs.getString("source"),
                date(rs, "date"), number(rs.getObject("evi")), number(rs.getObject("cire")), number(rs.getObject("vh_db"))), runId));
    }

    @Override
    public List<StatePoint> states(String runId) {
        return queries.read(QUERY_TIMEOUT, jdbc -> jdbc.query(STATE_SQL, (rs, n) -> new StatePoint(date(rs, "date"),
                rs.getString("state"), rs.getDouble("area_pct")), runId));
    }

    @Override
    public List<BlockCell> blocks(String runId, int seasonNo, int limit) {
        return queries.read(QUERY_TIMEOUT, jdbc -> jdbc.query(BLOCKS_SQL, (rs, n) -> new BlockCell(rs.getInt("block_id"),
                rs.getString("geojson"), date(rs, "planting_from"), date(rs, "planting_to"), date(rs, "harvest_from"),
                date(rs, "harvest_to")), runId, seasonNo, limit));
    }

    private static List<String> percentiles(String name) {
        return List.of(name + "_p10", name + "_p50", name + "_p90", name + "_uncertainty_days", name + "_blocks");
    }

    private static List<String> days(String name) {
        return List.of(name + "_blocks", name + "_min_days", name + "_max_days", name + "_mid_days",
                name + "_mid_p10_days", name + "_mid_p90_days");
    }

    private static LocalDate date(ResultSet rs, String column) throws SQLException {
        Date value = rs.getDate(column);
        return value == null ? null : value.toLocalDate();
    }

    private static Double number(Object value) {
        return value instanceof Number n ? n.doubleValue() : null;
    }

    private static Integer whole(Object value) {
        return value instanceof Number n ? n.intValue() : null;
    }
}
