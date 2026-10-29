package com.infinitestack.reports.blocks.brweather;

import static com.infinitestack.reports.blocks.brweather.BrWeatherTables.POWER_CELL;
import static com.infinitestack.reports.blocks.brweather.BrWeatherTables.POWER_DAILY;
import static com.infinitestack.reports.blocks.brweather.BrWeatherTables.requirement;
import static com.infinitestack.reports.blocks.brweather.BrWeatherTables.round;

import java.awt.Color;
import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.infinitestack.reports.blocks.sathub.IndexSeriesChart;
import com.infinitestack.reports.engine.DataType;
import com.infinitestack.reports.engine.block.BlockDefinition;
import com.infinitestack.reports.engine.block.BlockDocs;
import com.infinitestack.reports.engine.block.BlockPort;
import com.infinitestack.reports.engine.block.BlockQueries;
import com.infinitestack.reports.engine.block.ExecutionMetadata;
import com.infinitestack.reports.engine.block.ReportBlock;

/**
 * {@code brweather-daily-weather}: the daily weather of an area from NASA POWER (temperature,
 * humidity, wind, solar radiation), as BR Weather loads it: the 0.5 x 0.625 degree MERRA-2 cells that
 * touch the area, averaged with the share of the area in each cell as weight, plus a summary of the
 * period and a temperature chart.
 */
@Component
public class BrWeatherDailyBlock implements ReportBlock {

    static final int DEFAULT_DAYS = 30;
    static final int MAX_DAYS = 366;
    static final double GDD_BASE = 10;
    static final double HOT_DAY = 35;
    static final double COLD_NIGHT = 3;
    static final int CHART_WIDTH = 900;
    static final int CHART_HEIGHT = 300;
    static final Duration QUERY_TIMEOUT = Duration.ofSeconds(25);

    private final BlockQueries queries;
    private final IndexSeriesChart chart = new IndexSeriesChart(new Color(0xC62828), "%.0f");

    public BrWeatherDailyBlock(BlockQueries queries) {
        this.queries = queries;
    }

    @Override
    public BlockDefinition definition() {
        return new BlockDefinition("brweather-daily-weather", "Daily weather (NASA POWER)",
                "NASA POWER daily temperature, humidity, wind and radiation over an area, with a summary of the period and a temperature chart",
                "1.1.0",
                List.of(
                        BlockPort.required("geometry", DataType.GEOMETRY,
                                "area, GeoJSON in SIRGAS 2000 (SRID 4674); connect car-property-geometry.geometry"),
                        BlockPort.optional("from", DataType.DATE, "first day; default 'days' days before 'to'"),
                        BlockPort.optional("to", DataType.DATE, "last day; default the latest day loaded for the area"),
                        BlockPort.optional("days", DataType.NUMBER, "length of the period when 'from' is empty; default 30, at most 366")),
                List.of(
                        BlockPort.required("found", DataType.BOOLEAN, "there is weather for the area in the period"),
                        BlockPort.optional("message", DataType.STRING, "why nothing was found"),
                        BlockPort.optional("from", DataType.DATE, "first day of the period"),
                        BlockPort.optional("to", DataType.DATE, "last day of the period"),
                        BlockPort.optional("latestDay", DataType.DATE, "latest day BR Weather loaded for the area"),
                        BlockPort.required("cells", DataType.ARRAY, "POWER cells used: {lat, lon, elevationM, weightPercent} (centre of the cell)"),
                        BlockPort.required("nearestCell", DataType.BOOLEAN, "the area touches no cell and the nearest one was used"),
                        BlockPort.required("days", DataType.ARRAY,
                                "one per day, oldest first: {date, t2m, t2mMax, t2mMin, rh2m, ws2m, radiation, precipitation, et0Mm}"
                                        + " (C, C, C, %, m/s, MJ/m2/day, mm, mm); et0Mm null when an input of the day is missing"),
                        BlockPort.required("daysWithData", DataType.NUMBER, "days in the series"),
                        BlockPort.optional("meanT2m", DataType.NUMBER, "mean temperature of the period, C"),
                        BlockPort.optional("maxT2m", DataType.NUMBER, "highest daily maximum, C"),
                        BlockPort.optional("maxT2mDate", DataType.DATE, "day of the highest maximum"),
                        BlockPort.optional("minT2m", DataType.NUMBER, "lowest daily minimum, C"),
                        BlockPort.optional("minT2mDate", DataType.DATE, "day of the lowest minimum"),
                        BlockPort.optional("meanRh2m", DataType.NUMBER, "mean relative humidity, %"),
                        BlockPort.optional("meanWs2m", DataType.NUMBER, "mean wind speed at 2 m, m/s"),
                        BlockPort.optional("meanRadiation", DataType.NUMBER, "mean daily solar radiation, MJ/m2/day"),
                        BlockPort.optional("powerPrecipitationMm", DataType.NUMBER,
                                "rain of the period by MERRA-2 (PRECTOTCORR), mm; a check only, use brweather-rainfall (CHIRPS)"),
                        BlockPort.optional("degreeDays", DataType.NUMBER, "growing degree days, base 10 C, sum of max(0, mean - 10)"),
                        BlockPort.required("hotDays", DataType.NUMBER, "days with maximum of 35 C or more"),
                        BlockPort.required("coldNights", DataType.NUMBER, "days with minimum of 3 C or less (frost risk)"),
                        BlockPort.optional("totalEt0Mm", DataType.NUMBER,
                                "reference evapotranspiration (FAO-56 Penman-Monteith) summed over the days that have it, mm"),
                        BlockPort.optional("meanEt0Mm", DataType.NUMBER, "mean daily ET0 over the days that have it, mm/day"),
                        BlockPort.required("et0Days", DataType.NUMBER, "days with ET0 (the last days may lack radiation)"),
                        BlockPort.required("chart", DataType.STRING,
                                "PNG chart of the daily mean temperature with the minimum to maximum band, as a data:image/png;base64 URL")),
                List.of(
                        requirement(POWER_CELL, "lat", "lon", "elevation_m", "geom", "valid_to"),
                        requirement(POWER_DAILY, "lat", "lon", "day", "t2m", "t2m_max", "t2m_min", "rh2m", "ws2m",
                                "allsky_sfc_sw_dwn", "prectotcorr", "valid_to")),
                Duration.ofSeconds(30));
    }

    @Override
    public BlockDocs docs() {
        return new BlockDocs("BR Weather",
                "Shows the weather of an area day by day (temperature, humidity, wind, solar radiation) from NASA POWER, "
                        + "with a summary of the period: means, extremes, degree days, hot days, cold nights and the reference evapotranspiration (ET0).",
                List.of(
                        "Finds the NASA POWER cells (0.5 x 0.625 degree, about 55 x 65 km) that touch the area; an area on no cell takes the nearest one.",
                        "Weights each cell by the part of the area inside it.",
                        "Takes the period from 'from' to 'to', or the last 'days' days up to the latest day BR Weather loaded.",
                        "Averages each day over the cells, sums up the period and draws the temperature chart.",
                        "ET0 of each day: FAO-56 Penman-Monteith (grass reference) from the day's maximum and minimum temperature, "
                                + "mean relative humidity, wind at 2 m and solar radiation, with the weighted latitude and elevation of the cells."),
                List.of(
                        "geometry: connect car-property-geometry.geometry.",
                        "from / to: report inputs to follow a season (sowing to harvest); leave them empty for the last 30 days.",
                        "chart goes straight into the template: <img src=\"{{<node>.chart}}\">."),
                List.of(
                        "A cell is tens of kilometres wide: it describes the region of the area, not the field (relief and water bodies are smoothed out).",
                        "NASA POWER is a model reanalysis (MERRA-2, radiation from CERES), not a station; the last days are revised for about a month.",
                        "Days are in local solar time. Radiation of the most recent days may still be missing.",
                        "Rain here is MERRA-2 and only a check: the rain of a report comes from brweather-rainfall (CHIRPS).",
                        "ET0 uses the daily mean humidity (FAO-56 eq. 19, POWER has no daily RH max/min) and is null on a day without "
                                + "one of its inputs (usually the radiation of the last 1 or 2 days); totals and means count only the days with ET0 (et0Days)."),
                "NASA POWER (NASA Langley, community AG), loaded by BR Weather into gold.br_weather_agent_app_nasa_power_daily",
                "{{#<node>.found}}<img src=\"{{<node>.chart}}\"> De {{<node>.from}} a {{<node>.to}}: média {{<node>.meanT2m}} C,"
                        + " máxima {{<node>.maxT2m}} C em {{<node>.maxT2mDate}}{{/<node>.found}}");
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> inputs, ExecutionMetadata metadata) {
        String geoJson = BrWeatherTables.geoJson(inputs.get("geometry"));
        LocalDate from = date(inputs.get("from"));
        LocalDate to = date(inputs.get("to"));
        int days = inputs.get("days") instanceof Number n ? Math.max(1, Math.min(MAX_DAYS, n.intValue())) : DEFAULT_DAYS;

        return queries.read(QUERY_TIMEOUT, jdbc -> {
            PowerArea area = PowerArea.find(jdbc, geoJson);
            if (area == null) return notFound("the area is outside the NASA POWER cells BR Weather loaded (Brazil)", List.of(), false);

            LocalDate latest = area.latest(jdbc);
            if (latest == null) return notFound("BR Weather has no NASA POWER day for the area yet", area.cells, area.nearest);
            LocalDate end = to != null ? to : latest;
            LocalDate start = from != null ? from : end.minusDays(days - 1L);
            if (start.isAfter(end)) throw new IllegalArgumentException("from (" + start + ") is after to (" + end + ")");
            if (ChronoUnit.DAYS.between(start, end) >= MAX_DAYS) throw new IllegalArgumentException("the period is longer than " + MAX_DAYS + " days");

            List<Map<String, Object>> series = area.daily(jdbc, start, end);
            for (Map<String, Object> day : series) day.put("et0Mm", area.penmanMonteith(day));

            Map<String, Object> out = summary(series);
            out.put("from", start.toString());
            out.put("to", end.toString());
            out.put("latestDay", latest.toString());
            out.put("cells", area.cells);
            out.put("nearestCell", area.nearest);
            out.put("chart", chart(series));
            if (series.isEmpty()) {
                out.put("found", false);
                out.put("message", "no NASA POWER day for the area between " + start + " and " + end + " (latest loaded: " + latest + ")");
            }
            return ordered(out);
        });
    }

    /** found, the series and its summary. */
    static Map<String, Object> summary(List<Map<String, Object>> series) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("found", !series.isEmpty());
        out.put("message", null);
        out.put("days", series);
        out.put("daysWithData", series.size());
        out.put("meanT2m", mean(series, "t2m"));
        Map<String, Object> hottest = extreme(series, "t2mMax", true);
        Map<String, Object> coldest = extreme(series, "t2mMin", false);
        out.put("maxT2m", hottest == null ? null : hottest.get("t2mMax"));
        out.put("maxT2mDate", hottest == null ? null : hottest.get("date"));
        out.put("minT2m", coldest == null ? null : coldest.get("t2mMin"));
        out.put("minT2mDate", coldest == null ? null : coldest.get("date"));
        out.put("meanRh2m", mean(series, "rh2m"));
        out.put("meanWs2m", mean(series, "ws2m"));
        out.put("meanRadiation", mean(series, "radiation"));
        Double rain = null;
        double degreeDays = 0;
        boolean anyMean = false;
        int hot = 0;
        int cold = 0;
        for (Map<String, Object> day : series) {
            if (day.get("precipitation") instanceof Double p) rain = (rain == null ? 0 : rain) + p;
            if (day.get("t2m") instanceof Double t) {
                degreeDays += Math.max(0, t - GDD_BASE);
                anyMean = true;
            }
            if (day.get("t2mMax") instanceof Double t && t >= HOT_DAY) hot++;
            if (day.get("t2mMin") instanceof Double t && t <= COLD_NIGHT) cold++;
        }
        out.put("powerPrecipitationMm", round(rain, 1));
        out.put("degreeDays", anyMean ? round(degreeDays, 1) : null);
        out.put("hotDays", hot);
        out.put("coldNights", cold);
        double et0 = 0;
        int et0Days = 0;
        for (Map<String, Object> day : series) {
            if (day.get("et0Mm") instanceof Double e) {
                et0 += e;
                et0Days++;
            }
        }
        out.put("totalEt0Mm", et0Days == 0 ? null : round(et0, 1));
        out.put("meanEt0Mm", et0Days == 0 ? null : round(et0 / et0Days, 2));
        out.put("et0Days", et0Days);
        return out;
    }

    private String chart(List<Map<String, Object>> series) {
        List<IndexSeriesChart.Point> points = new ArrayList<>();
        for (Map<String, Object> day : series) {
            if (day.get("t2m") instanceof Double mean && day.get("t2mMin") instanceof Double min && day.get("t2mMax") instanceof Double max) {
                points.add(new IndexSeriesChart.Point(LocalDate.parse((String) day.get("date")), mean, min, max));
            }
        }
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(chart.render(points, CHART_WIDTH, CHART_HEIGHT));
    }

    private Map<String, Object> notFound(String message, List<Map<String, Object>> cells, boolean nearest) {
        Map<String, Object> out = summary(List.of());
        out.put("message", message);
        out.put("from", null);
        out.put("to", null);
        out.put("latestDay", null);
        out.put("cells", cells);
        out.put("nearestCell", nearest);
        out.put("chart", chart(List.of()));
        return ordered(out);
    }

    /** The outputs in the order the definition declares them. */
    private Map<String, Object> ordered(Map<String, Object> values) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (BlockPort port : definition().outputs()) out.put(port.name(), values.get(port.name()));
        return out;
    }

    private static Double mean(List<Map<String, Object>> series, String name) {
        double sum = 0;
        int n = 0;
        for (Map<String, Object> day : series) {
            if (day.get(name) instanceof Double v) {
                sum += v;
                n++;
            }
        }
        return n == 0 ? null : round(sum / n, 2);
    }

    private static Map<String, Object> extreme(List<Map<String, Object>> series, String name, boolean highest) {
        Map<String, Object> best = null;
        for (Map<String, Object> day : series) {
            if (!(day.get(name) instanceof Double v)) continue;
            if (best == null || (highest ? v > (Double) best.get(name) : v < (Double) best.get(name))) best = day;
        }
        return best;
    }

    static LocalDate date(Object value) {
        if (value == null || value.toString().isBlank()) return null;
        return LocalDate.parse(value.toString().substring(0, Math.min(10, value.toString().length())));
    }

}
