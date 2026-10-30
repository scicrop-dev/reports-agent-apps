package com.infinitestack.reports.blocks.cropcal;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What is-crop-calendar wrote to its gold tables ({@code gold.is_crop_calendar_*}). An interface so the
 * Block can be tested without a database ({@link GoldCalendarResults} is the real one).
 */
public interface CalendarResults {

    /** The latest run of each status for this request_sha (any params_hash); empty when never asked. */
    List<RunSummary> runs(String requestSha);

    Optional<RunDetail> run(String runId);

    /** The season rows of a run, by season_no: column name to value (LocalDate, Double, Integer or null). */
    List<Map<String, Object>> seasons(String runId);

    /** S2 dates of the field series (in_field_series) and every S1 pass, by date. */
    List<SeriesPoint> series(String runId);

    /** The share of the field in each state, per day. */
    List<StatePoint> states(String runId);

    /** The 100 m blocks of one season, at most {@code limit}. */
    List<BlockCell> blocks(String runId, int seasonNo, int limit);

    /**
     * @param ageSeconds          since the run started
     * @param finishedSecondsAgo  since it finished; null while RUNNING
     * @param settled             finished more than the settle days after its "to": no new image can change it
     */
    record RunSummary(String runId, String status, String error, String triggerId, String finishedAt,
                      Double ageSeconds, Double finishedSecondsAgo, boolean settled) {}

    record RunDetail(String runId, String status, String error, Double areaHa, LocalDate from, LocalDate to,
                     Integer s2Dates, Integer s2ClearDates, Integer s1Passes, Integer orbit, Integer blocks,
                     Integer seasons, String finishedAt, String aoiGeoJson) {}

    /** @param source s2 or s1 */
    record SeriesPoint(String source, LocalDate date, Double evi, Double cire, Double vhDb) {}

    record StatePoint(LocalDate date, String state, double areaPct) {}

    /** @param geoJson the block square, EPSG:4674 */
    record BlockCell(int blockId, String geoJson, LocalDate plantingFrom, LocalDate plantingTo,
                     LocalDate harvestFrom, LocalDate harvestTo) {}
}
