package com.infinitestack.reports.blocks.brweather;

import static com.infinitestack.reports.blocks.brweather.BrWeatherTables.POWER_CELL;
import static com.infinitestack.reports.blocks.brweather.BrWeatherTables.POWER_DAILY;
import static com.infinitestack.reports.blocks.brweather.BrWeatherTables.Q_POWER_DAILY;
import static com.infinitestack.reports.blocks.brweather.BrWeatherTables.requirement;
import static com.infinitestack.reports.blocks.brweather.BrWeatherTables.round;

import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.stereotype.Component;

import com.infinitestack.reports.engine.DataType;
import com.infinitestack.reports.engine.block.BlockDefinition;
import com.infinitestack.reports.engine.block.BlockDocs;
import com.infinitestack.reports.engine.block.BlockPort;
import com.infinitestack.reports.engine.block.BlockQueries;
import com.infinitestack.reports.engine.block.ExecutionMetadata;
import com.infinitestack.reports.engine.block.ReportBlock;

/**
 * {@code brweather-temperature-anomaly}: the temperature of a period over an area against the same
 * calendar window in every complete year of NASA POWER that BR Weather loaded (from 2000 to the last
 * closed year). Only SQL over the daily table, restricted to the cells of the area and, for each year,
 * to the days of the window, so each cell is an index range scan on (lat, lon, day) per year.
 */
@Component
public class BrWeatherTemperatureAnomalyBlock implements ReportBlock {

    static final int DEFAULT_DAYS = 30;
    static final int MAX_DAYS = 366;
    /** First year of the BR Weather series (BRWEATHER_FIRST_YEAR). */
    static final int FIRST_YEAR = 2000;
    /** A year enters the climatology when it has at least this share of the window's days. */
    static final double MIN_COVERAGE = 0.9;
    static final double HOT_PERCENTILE = 90;
    static final double COLD_PERCENTILE = 10;
    static final Duration QUERY_TIMEOUT = Duration.ofSeconds(40);

    /**
     * Weighted area means per window year and day. {@code %s}: the VALUES rows of the cells, then
     * of the windows. The LATERAL subquery with OFFSET 0 is not flattened, so each cell and window is
     * an index range scan on the current-row index (lat, lon, day): with a plain join the planner
     * took a sequential scan of the whole table (measured on 3.1 million rows: 3 to 15 s against
     * 37 ms for 4 cells x 30 days and 1.4 s for 60 cells x 366 days).
     */
    static final String CLIMATOLOGY_SQL = "WITH cells(lat, lon, weight) AS (VALUES %s),"
            + " w(y, f, t) AS (VALUES %s)"
            + " SELECT w.y, d.day,"
            + " sum(d.t2m * c.weight) / nullif(sum(CASE WHEN d.t2m IS NOT NULL THEN c.weight END), 0) AS t2m,"
            + " sum(d.t2m_max * c.weight) / nullif(sum(CASE WHEN d.t2m_max IS NOT NULL THEN c.weight END), 0) AS t2m_max,"
            + " sum(d.t2m_min * c.weight) / nullif(sum(CASE WHEN d.t2m_min IS NOT NULL THEN c.weight END), 0) AS t2m_min"
            + " FROM cells c CROSS JOIN w CROSS JOIN LATERAL (SELECT x.day, x.t2m, x.t2m_max, x.t2m_min FROM " + Q_POWER_DAILY + " x"
            + " WHERE x.lat = c.lat AND x.lon = c.lon AND x.day BETWEEN w.f AND w.t AND x.valid_to IS NULL OFFSET 0) d"
            + " GROUP BY w.y, d.day ORDER BY w.y, d.day";

    /** A climatology window: the period shifted to another year, labelled by the year it starts in. */
    record Window(int year, LocalDate from, LocalDate to) {}

    private final BlockQueries queries;

    public BrWeatherTemperatureAnomalyBlock(BlockQueries queries) {
        this.queries = queries;
    }

    @Override
    public BlockDefinition definition() {
        return new BlockDefinition("brweather-temperature-anomaly", "Temperature anomaly (NASA POWER)",
                "Mean, maximum and minimum temperature of a period over an area against the same days of the year since 2000,"
                        + " with hot days and cold nights against the climatology",
                "1.0.0",
                List.of(
                        BlockPort.required("geometry", DataType.GEOMETRY,
                                "area, GeoJSON in SIRGAS 2000 (SRID 4674); connect car-property-geometry.geometry"),
                        BlockPort.optional("from", DataType.DATE, "first day; default 'days' days before 'to'"),
                        BlockPort.optional("to", DataType.DATE, "last day; default the latest day loaded for the area"),
                        BlockPort.optional("days", DataType.NUMBER, "length of the period when 'from' is empty; default 30, at most 366")),
                List.of(
                        BlockPort.required("found", DataType.BOOLEAN, "the period and a climatology were found"),
                        BlockPort.optional("message", DataType.STRING, "why not"),
                        BlockPort.optional("from", DataType.DATE, "first day of the period"),
                        BlockPort.optional("to", DataType.DATE, "last day of the period"),
                        BlockPort.optional("latestDay", DataType.DATE, "latest day BR Weather loaded for the area"),
                        BlockPort.required("cells", DataType.ARRAY, "POWER cells used: {lat, lon, elevationM, weightPercent}"),
                        BlockPort.required("nearestCell", DataType.BOOLEAN, "the area touches no cell and the nearest one was used"),
                        BlockPort.required("daysWithData", DataType.NUMBER, "days of the period with temperature"),
                        BlockPort.optional("meanT2m", DataType.NUMBER, "mean temperature of the period, C"),
                        BlockPort.optional("climatologyT2m", DataType.NUMBER, "mean temperature of the same window over the years used, C"),
                        BlockPort.optional("anomalyT2m", DataType.NUMBER, "meanT2m - climatologyT2m, C"),
                        BlockPort.optional("meanT2mMax", DataType.NUMBER, "mean of the daily maximum of the period, C"),
                        BlockPort.optional("climatologyT2mMax", DataType.NUMBER, "mean daily maximum of the window over the years used, C"),
                        BlockPort.optional("anomalyT2mMax", DataType.NUMBER, "meanT2mMax - climatologyT2mMax, C"),
                        BlockPort.optional("meanT2mMin", DataType.NUMBER, "mean of the daily minimum of the period, C"),
                        BlockPort.optional("climatologyT2mMin", DataType.NUMBER, "mean daily minimum of the window over the years used, C"),
                        BlockPort.optional("anomalyT2mMin", DataType.NUMBER, "meanT2mMin - climatologyT2mMin, C"),
                        BlockPort.required("hotDays", DataType.NUMBER,
                                "days of the period whose maximum is above the 90th percentile of the daily maximum of the window in the years used"),
                        BlockPort.optional("hotDayThresholdC", DataType.NUMBER, "that 90th percentile, C"),
                        BlockPort.required("coldNights", DataType.NUMBER,
                                "days of the period whose minimum is below the 10th percentile of the daily minimum of the window in the years used"),
                        BlockPort.optional("coldNightThresholdC", DataType.NUMBER, "that 10th percentile, C"),
                        BlockPort.required("yearsUsed", DataType.NUMBER, "complete years in the climatology"),
                        BlockPort.optional("firstYear", DataType.NUMBER, "first year of the climatology"),
                        BlockPort.optional("lastYear", DataType.NUMBER, "last year of the climatology"),
                        BlockPort.required("perYear", DataType.ARRAY,
                                "one per year used, oldest first: {year, meanT2m, meanT2mMax, meanT2mMin, current} (year the window starts in;"
                                        + " current = the window is the period itself)")),
                List.of(
                        requirement(POWER_CELL, "lat", "lon", "elevation_m", "geom", "valid_to"),
                        requirement(POWER_DAILY, "lat", "lon", "day", "t2m", "t2m_max", "t2m_min", "rh2m", "ws2m",
                                "allsky_sfc_sw_dwn", "prectotcorr", "valid_to")),
                Duration.ofSeconds(60));
    }

    @Override
    public BlockDocs docs() {
        return new BlockDocs("BR Weather",
                "Tells whether a period was hotter or colder than usual over an area: its mean, maximum and minimum temperature "
                        + "against the same days of the year in every complete year since 2000 (NASA POWER), with the hot days and "
                        + "cold nights beyond the usual extremes.",
                List.of(
                        "Finds the NASA POWER cells of the area and their weights, as brweather-daily-weather.",
                        "Takes the period from 'from' to 'to', or the last 'days' days up to the latest day loaded.",
                        "Shifts the period to each year from 2000 to the last closed year (the year before the latest day loaded) and "
                                + "reads, for the cells of the area, only the days of each shifted window.",
                        "A year enters the climatology when it has at least 90% of the window's days; the climatology is the mean of "
                                + "the years, each year weighing the same.",
                        "Hot days: maximum above the 90th percentile of all the daily maxima of the window in those years; cold nights: "
                                + "minimum below the 10th percentile of the minima."),
                List.of(
                        "geometry: connect car-property-geometry.geometry.",
                        "from / to: the season or the event to compare; empty for the last 30 days.",
                        "perYear feeds a small table or bar list in the template ({{#<node>.perYear}}...{{/<node>.perYear}})."),
                List.of(
                        "A cell is tens of kilometres wide: it describes the region, not the field.",
                        "The climatology covers the years BR Weather loaded (2000 on), not a 30-year WMO normal; when the period is in a "
                                + "closed year, that year is part of its own climatology (current = true in perYear).",
                        "Percentiles come from the whole window, not from a window around each calendar day: in a long period they "
                                + "mix seasons, so hot days pile up in the warm months.",
                        "29 February shifts to 28 February in a common year."),
                "NASA POWER (NASA Langley, community AG), loaded by BR Weather into gold.br_weather_agent_app_nasa_power_daily",
                "{{#<node>.found}}De {{<node>.from}} a {{<node>.to}}: média {{<node>.meanT2m}} C, {{<node>.anomalyT2m}} C em relação"
                        + " a {{<node>.firstYear}}-{{<node>.lastYear}} ({{<node>.yearsUsed}} anos){{/<node>.found}}"
                        + "{{^<node>.found}}{{<node>.message}}{{/<node>.found}}");
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> inputs, ExecutionMetadata metadata) {
        String geoJson = BrWeatherTables.geoJson(inputs.get("geometry"));
        LocalDate from = BrWeatherDailyBlock.date(inputs.get("from"));
        LocalDate to = BrWeatherDailyBlock.date(inputs.get("to"));
        int days = inputs.get("days") instanceof Number n ? Math.max(1, Math.min(MAX_DAYS, n.intValue())) : DEFAULT_DAYS;

        return queries.read(QUERY_TIMEOUT, jdbc -> {
            Map<String, Object> out = new HashMap<>();
            PowerArea area = PowerArea.find(jdbc, geoJson);
            if (area == null) return notFound(out, "the area is outside the NASA POWER cells BR Weather loaded (Brazil)");
            out.put("cells", area.cells);
            out.put("nearestCell", area.nearest);
            LocalDate latest = area.latest(jdbc);
            if (latest == null) return notFound(out, "BR Weather has no NASA POWER day for the area yet");
            out.put("latestDay", latest.toString());
            LocalDate end = to != null ? to : latest;
            LocalDate start = from != null ? from : end.minusDays(days - 1L);
            if (start.isAfter(end)) throw new IllegalArgumentException("from (" + start + ") is after to (" + end + ")");
            if (ChronoUnit.DAYS.between(start, end) >= MAX_DAYS) throw new IllegalArgumentException("the period is longer than " + MAX_DAYS + " days");
            out.put("from", start.toString());
            out.put("to", end.toString());

            List<Map<String, Object>> period = area.daily(jdbc, start, end);
            List<Window> windows = windows(start, end, latest);
            if (windows.isEmpty()) return notFound(out, "no complete year since " + FIRST_YEAR + " for the window " + start + " to " + end);

            List<Object> args = new ArrayList<>(area.cellArgs);
            for (Window w : windows) {
                args.add(w.year());
                args.add(java.sql.Date.valueOf(w.from()));
                args.add(java.sql.Date.valueOf(w.to()));
            }
            String windowValues = String.join(", ", Collections.nCopies(windows.size(), "(?::int, ?::date, ?::date)"));
            List<Map<String, Object>> rows = jdbc.queryForList(String.format(CLIMATOLOGY_SQL, area.values, windowValues), args.toArray());

            out.putAll(compare(period, windows, rows, start));
            if (period.isEmpty()) return notFound(out, "no NASA POWER day for the area between " + start + " and " + end + " (latest loaded: " + latest + ")");
            if (((Integer) out.get("yearsUsed")) == 0) return notFound(out, "no year since " + FIRST_YEAR + " has " + Math.round(MIN_COVERAGE * 100)
                    + "% of the days of the window " + start + " to " + end);
            out.put("found", true);
            out.put("message", null);
            return ordered(out);
        });
    }

    /**
     * The period shifted to every year whose window lies within FIRST_YEAR-01-01 and the end of
     * the last closed year (the year before the latest day, or its year when the latest day is
     * 31 December), oldest first.
     */
    static List<Window> windows(LocalDate start, LocalDate end, LocalDate latest) {
        int lastClosed = latest.getMonthValue() == 12 && latest.getDayOfMonth() == 31 ? latest.getYear() : latest.getYear() - 1;
        LocalDate first = LocalDate.of(FIRST_YEAR, 1, 1);
        LocalDate last = LocalDate.of(lastClosed, 12, 31);
        List<Window> out = new ArrayList<>();
        for (int year = FIRST_YEAR; year <= lastClosed; year++) {
            long shift = year - start.getYear();
            LocalDate f = start.plusYears(shift);
            LocalDate t = end.plusYears(shift);
            if (!f.isBefore(first) && !t.isAfter(last)) out.add(new Window(year, f, t));
        }
        return out;
    }

    /** The summary of the period against the climatology rows ({y, day, t2m, t2m_max, t2m_min}). */
    static Map<String, Object> compare(List<Map<String, Object>> period, List<Window> windows, List<Map<String, Object>> rows,
                                       LocalDate start) {
        Map<Integer, Window> byYear = new HashMap<>();
        for (Window w : windows) byYear.put(w.year(), w);
        Map<Integer, List<Map<String, Object>>> grouped = new TreeMap<>();
        for (Map<String, Object> row : rows) grouped.computeIfAbsent(((Number) row.get("y")).intValue(), y -> new ArrayList<>()).add(row);

        List<Map<String, Object>> perYear = new ArrayList<>();
        List<Double> maxima = new ArrayList<>();
        List<Double> minima = new ArrayList<>();
        double[] climatology = new double[3];
        int[] climatologyYears = new int[3];
        for (Map.Entry<Integer, List<Map<String, Object>>> entry : grouped.entrySet()) {
            Window w = byYear.get(entry.getKey());
            long length = ChronoUnit.DAYS.between(w.from(), w.to()) + 1;
            List<Map<String, Object>> yearRows = entry.getValue();
            if (yearRows.size() < MIN_COVERAGE * length) continue;
            Double t = average(yearRows, "t2m");
            Double tMax = average(yearRows, "t2m_max");
            Double tMin = average(yearRows, "t2m_min");
            Double[] means = {t, tMax, tMin};
            for (int i = 0; i < 3; i++) {
                if (means[i] != null) {
                    climatology[i] += means[i];
                    climatologyYears[i]++;
                }
            }
            for (Map<String, Object> row : yearRows) {
                if (row.get("t2m_max") instanceof Number v) maxima.add(v.doubleValue());
                if (row.get("t2m_min") instanceof Number v) minima.add(v.doubleValue());
            }
            Map<String, Object> year = new LinkedHashMap<>();
            year.put("year", w.year());
            year.put("meanT2m", round(t, 2));
            year.put("meanT2mMax", round(tMax, 2));
            year.put("meanT2mMin", round(tMin, 2));
            year.put("current", w.from().equals(start));
            perYear.add(year);
        }

        Map<String, Object> out = new HashMap<>();
        out.put("daysWithData", (int) period.stream().filter(d -> d.get("t2m") != null || d.get("t2mMax") != null || d.get("t2mMin") != null).count());
        String[][] names = {{"t2m", "T2m"}, {"t2mMax", "T2mMax"}, {"t2mMin", "T2mMin"}};
        for (int i = 0; i < 3; i++) {
            Double mean = meanOf(period, names[i][0]);
            Double clim = climatologyYears[i] == 0 ? null : climatology[i] / climatologyYears[i];
            out.put("mean" + names[i][1], round(mean, 2));
            out.put("climatology" + names[i][1], round(clim, 2));
            out.put("anomaly" + names[i][1], mean == null || clim == null ? null : round(mean - clim, 2));
        }
        Double hot = percentile(maxima, HOT_PERCENTILE);
        Double cold = percentile(minima, COLD_PERCENTILE);
        int hotDays = 0;
        int coldNights = 0;
        for (Map<String, Object> day : period) {
            if (hot != null && day.get("t2mMax") instanceof Double v && v > hot) hotDays++;
            if (cold != null && day.get("t2mMin") instanceof Double v && v < cold) coldNights++;
        }
        out.put("hotDays", hotDays);
        out.put("hotDayThresholdC", round(hot, 2));
        out.put("coldNights", coldNights);
        out.put("coldNightThresholdC", round(cold, 2));
        out.put("yearsUsed", perYear.size());
        out.put("firstYear", perYear.isEmpty() ? null : perYear.get(0).get("year"));
        out.put("lastYear", perYear.isEmpty() ? null : perYear.get(perYear.size() - 1).get("year"));
        out.put("perYear", perYear);
        return out;
    }

    /** Linear interpolation between closest ranks (as PostgreSQL percentile_cont); null for no value. */
    static Double percentile(List<Double> values, double percent) {
        if (values.isEmpty()) return null;
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        double rank = percent / 100 * (sorted.size() - 1);
        int low = (int) Math.floor(rank);
        int high = (int) Math.ceil(rank);
        return sorted.get(low) + (sorted.get(high) - sorted.get(low)) * (rank - low);
    }

    private static Double average(List<Map<String, Object>> rows, String column) {
        double sum = 0;
        int n = 0;
        for (Map<String, Object> row : rows) {
            if (row.get(column) instanceof Number v) {
                sum += v.doubleValue();
                n++;
            }
        }
        return n == 0 ? null : sum / n;
    }

    private static Double meanOf(List<Map<String, Object>> series, String name) {
        double sum = 0;
        int n = 0;
        for (Map<String, Object> day : series) {
            if (day.get(name) instanceof Double v) {
                sum += v;
                n++;
            }
        }
        return n == 0 ? null : sum / n;
    }

    private Map<String, Object> notFound(Map<String, Object> partial, String message) {
        Map<String, Object> out = new HashMap<>(partial);
        out.put("found", false);
        out.put("message", message);
        out.putIfAbsent("cells", List.of());
        out.putIfAbsent("nearestCell", false);
        out.putIfAbsent("daysWithData", 0);
        out.putIfAbsent("hotDays", 0);
        out.putIfAbsent("coldNights", 0);
        out.putIfAbsent("yearsUsed", 0);
        out.putIfAbsent("perYear", List.of());
        return ordered(out);
    }

    private Map<String, Object> ordered(Map<String, Object> values) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (BlockPort port : definition().outputs()) out.put(port.name(), values.get(port.name()));
        return out;
    }
}
