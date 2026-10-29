package com.infinitestack.reports.blocks.brweather;

import static com.infinitestack.reports.blocks.brweather.BrWeatherTables.CHIRPS_DAY;
import static com.infinitestack.reports.blocks.brweather.BrWeatherTables.requirement;
import static com.infinitestack.reports.blocks.brweather.BrWeatherTables.round;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.infinitestack.reports.blocks.sathub.SatelliteHubLatestImageBlock;
import com.infinitestack.reports.blocks.sathub.imagery.CogReader;
import com.infinitestack.reports.blocks.sathub.imagery.HttpCogReader;
import com.infinitestack.reports.blocks.sathub.imagery.LocalFileRangeFetcher;
import com.infinitestack.reports.engine.DataType;
import com.infinitestack.reports.engine.block.BlockDefinition;
import com.infinitestack.reports.engine.block.BlockDocs;
import com.infinitestack.reports.engine.block.BlockPort;
import com.infinitestack.reports.engine.block.BlockQueries;
import com.infinitestack.reports.engine.block.ExecutionMetadata;
import com.infinitestack.reports.engine.block.ReportBlock;

/**
 * {@code brweather-rainfall}: the daily rain over an area from CHIRPS v3 (0.05 degree, about 5.5 km).
 * BR Weather keeps one GeoTIFF clip of Brazil per day in the lake and only their catalog in gold
 * ({@code path}, extent, size); this Block reads, from each day's clip on the host's disk, only the
 * pixels of the area (the same reader as the Sentinel-2 COGs: float32, floating point predictor) and
 * averages the pixels whose centre is inside it.
 *
 * <p>Only files under {@code reports.brweather.chirps-dir} are read: the path comes from a table, and
 * the Block must not become a way to read any file of the host.
 */
@Component
public class BrWeatherRainfallBlock implements ReportBlock {

    static final int DEFAULT_DAYS = 30;
    static final int MAX_DAYS = 366;
    static final double RAINY_DAY_MM = 1.0;
    static final double HEAVY_RAIN_MM = 50.0;
    static final int RX_DAYS = 5;
    static final int CHART_WIDTH = 900;
    static final int CHART_HEIGHT = 260;

    private final ChirpsClips clips;
    private final DailyBarChart chart = new DailyBarChart();

    @Autowired
    public BrWeatherRainfallBlock(BlockQueries queries,
                                  @Value("${reports.brweather.chirps-dir:/opt/infinitestack/var/lake/bronze/br-weather-agent-app/chirps}") String root) {
        this(queries, new HttpCogReader(new LocalFileRangeFetcher()), Path.of(root));
    }

    BrWeatherRainfallBlock(BlockQueries queries, CogReader reader, Path root) {
        this.clips = new ChirpsClips(queries, reader, root);
    }

    @Override
    public BlockDefinition definition() {
        return new BlockDefinition("brweather-rainfall", "Daily rainfall (CHIRPS)",
                "CHIRPS v3 daily rain over an area: total, rainy days, longest dry spell and a bar chart",
                "1.1.0",
                List.of(
                        BlockPort.required("geometry", DataType.GEOMETRY,
                                "area, GeoJSON in SIRGAS 2000 (lon/lat); connect car-property-geometry.geometry"),
                        BlockPort.optional("from", DataType.DATE, "first day; default 'days' days before 'to'"),
                        BlockPort.optional("to", DataType.DATE, "last day; default the latest day loaded"),
                        BlockPort.optional("days", DataType.NUMBER, "length of the period when 'from' is empty; default 30, at most 366")),
                List.of(
                        BlockPort.required("found", DataType.BOOLEAN, "there is rain data for the area in the period"),
                        BlockPort.optional("message", DataType.STRING, "why nothing was found"),
                        BlockPort.optional("from", DataType.DATE, "first day of the period"),
                        BlockPort.optional("to", DataType.DATE, "last day of the period"),
                        BlockPort.optional("latestDay", DataType.DATE, "latest day BR Weather loaded"),
                        BlockPort.required("pixels", DataType.NUMBER, "CHIRPS pixels averaged (0.05 degree each)"),
                        BlockPort.required("wholePixels", DataType.BOOLEAN,
                                "false when the area is smaller than a pixel and the pixels of the box around it were used"),
                        BlockPort.required("days", DataType.ARRAY, "one per day with data, oldest first: {date, rainMm (2 decimals), preliminary}"),
                        BlockPort.required("daysWithData", DataType.NUMBER, "days with a value"),
                        BlockPort.required("missingDays", DataType.NUMBER, "days of the period without a clip (not loaded yet or file missing)"),
                        BlockPort.optional("totalMm", DataType.NUMBER, "rain of the period, mm"),
                        BlockPort.optional("meanDailyMm", DataType.NUMBER, "mean rain per day with data, mm"),
                        BlockPort.optional("maxDayMm", DataType.NUMBER, "rainiest day, mm"),
                        BlockPort.optional("maxDayDate", DataType.DATE, "date of the rainiest day"),
                        BlockPort.required("rainyDays", DataType.NUMBER, "days with 1 mm or more"),
                        BlockPort.required("longestDrySpell", DataType.NUMBER, "longest run of consecutive days under 1 mm"),
                        BlockPort.required("preliminaryDays", DataType.NUMBER, "days still from the preliminary series (revised later)"),
                        BlockPort.required("heavyRainDays", DataType.NUMBER, "days with 50 mm or more"),
                        BlockPort.optional("rx1dayMm", DataType.NUMBER, "highest 1-day rain (Rx1day), mm"),
                        BlockPort.optional("rx5dayMm", DataType.NUMBER,
                                "highest rain in 5 consecutive days with data (Rx5day), mm; null when no 5 consecutive days have data"),
                        BlockPort.required("drySpells10", DataType.NUMBER, "runs of 10 or more consecutive days under 1 mm"),
                        BlockPort.required("drySpells15", DataType.NUMBER, "runs of 15 or more consecutive days under 1 mm"),
                        BlockPort.required("chart", DataType.STRING, "PNG bar chart of the daily rain, as a data:image/png;base64 URL")),
                List.of(requirement(CHIRPS_DAY, ChirpsClips.COLUMNS)),
                Duration.ofSeconds(60));
    }

    @Override
    public BlockDocs docs() {
        return new BlockDocs("BR Weather",
                "Shows how much it rained over an area day by day, from CHIRPS v3 (satellite and stations, about 5.5 km): "
                        + "total of the period, rainy days, the rainiest day and the longest dry spell, with a bar chart.",
                List.of(
                        "Takes the period from 'from' to 'to', or the last 'days' days up to the latest day BR Weather loaded.",
                        "For each day, opens the CHIRPS clip of Brazil that BR Weather keeps in the lake and reads only the pixels of the area.",
                        "Averages the pixels whose centre is inside the area; an area smaller than a pixel takes the pixels of its bounding box.",
                        "Sums up the period and draws the daily bars.",
                        "Extremes (ETCCDI style): days of 50 mm or more, the wettest day (Rx1day) and 5 consecutive days (Rx5day), "
                                + "and the dry spells (runs of days under 1 mm) of 10 and 15 days or more."),
                List.of(
                        "geometry: connect car-property-geometry.geometry.",
                        "from / to: report inputs to follow a season; empty for the last 30 days.",
                        "chart goes straight into the template: <img src=\"{{<node>.chart}}\">."),
                List.of(
                        "A pixel is about 5.5 km: a small field takes the rain of its pixel, which also covers the neighbours.",
                        "The most recent days come from the preliminary series and are replaced by the final one when it comes out (preliminary).",
                        "The clips are files in the lake of the host (BR Weather); reports reads them from reports.brweather.chirps-dir "
                                + "(REPORTS_CHIRPS_DIR), the same directory as BRWEATHER_CHIRPS_DIR. A clip outside it is not read.",
                        "Reads one file per day: a year takes a few seconds.",
                        "A day without data breaks a dry spell and a 5-day window; a spell cut by the start or end of the period counts with "
                                + "the days inside it."),
                "CHIRPS v3 daily (Climate Hazards Center, UCSB), clipped and cataloged by BR Weather in gold.br_weather_agent_app_chirps_day",
                "{{#<node>.found}}<img src=\"{{<node>.chart}}\"> Chuva de {{<node>.from}} a {{<node>.to}}: {{<node>.totalMm}} mm"
                        + " em {{<node>.rainyDays}} dias{{/<node>.found}}{{^<node>.found}}{{<node>.message}}{{/<node>.found}}");
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> execute(Map<String, Object> inputs, ExecutionMetadata metadata) throws Exception {
        if (!(inputs.get("geometry") instanceof Map<?, ?> geometry)) throw new IllegalArgumentException("geometry is required");
        List<List<double[][]>> polygons = SatelliteHubLatestImageBlock.polygons((Map<String, Object>) geometry);
        LocalDate from = BrWeatherDailyBlock.date(inputs.get("from"));
        LocalDate to = BrWeatherDailyBlock.date(inputs.get("to"));
        int days = inputs.get("days") instanceof Number n ? Math.max(1, Math.min(MAX_DAYS, n.intValue())) : DEFAULT_DAYS;

        LocalDate latest = clips.latest();
        if (latest == null) return notFound("BR Weather has no CHIRPS day loaded yet", null);
        LocalDate end = to != null ? to : latest;
        LocalDate start = from != null ? from : end.minusDays(days - 1L);
        if (start.isAfter(end)) throw new IllegalArgumentException("from (" + start + ") is after to (" + end + ")");
        if (ChronoUnit.DAYS.between(start, end) >= MAX_DAYS) throw new IllegalArgumentException("the period is longer than " + MAX_DAYS + " days");

        ChirpsClips.Period period = clips.read(polygons, start, end);
        List<Map<String, Object>> series = period.series();

        Map<String, Object> out = summary(series);
        out.put("from", start.toString());
        out.put("to", end.toString());
        out.put("latestDay", latest.toString());
        out.put("pixels", period.pixels());
        out.put("wholePixels", period.wholePixels());
        out.put("missingDays", period.missingDays());
        out.put("chart", chart(series, start, end));
        if (series.isEmpty()) {
            out.put("found", false);
            out.put("message", period.catalogDays() == 0
                    ? "no CHIRPS day loaded between " + start + " and " + end + " (latest: " + latest + ")"
                    : "the area is outside the CHIRPS clips or their files are missing in " + clips.root());
        }
        return ordered(out);
    }

    static Map<String, Object> summary(List<Map<String, Object>> series) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("found", !series.isEmpty());
        out.put("message", null);
        out.put("days", series);
        out.put("daysWithData", series.size());
        double total = 0;
        Map<String, Object> wettest = null;
        int rainy = 0;
        int dry = 0;
        int longestDry = 0;
        int preliminary = 0;
        LocalDate previous = null;
        for (Map<String, Object> day : series) {
            double rain = (Double) day.get("rainMm");
            LocalDate date = LocalDate.parse((String) day.get("date"));
            total += rain;
            if (wettest == null || rain > (Double) wettest.get("rainMm")) wettest = day;
            // a gap in the data breaks a dry spell: it is not known to be dry
            if (previous != null && ChronoUnit.DAYS.between(previous, date) > 1) dry = 0;
            if (rain >= RAINY_DAY_MM) {
                rainy++;
                dry = 0;
            } else {
                dry++;
                longestDry = Math.max(longestDry, dry);
            }
            if (Boolean.TRUE.equals(day.get("preliminary"))) preliminary++;
            previous = date;
        }
        out.put("totalMm", series.isEmpty() ? null : round(total, 1));
        out.put("meanDailyMm", series.isEmpty() ? null : round(total / series.size(), 2));
        out.put("maxDayMm", wettest == null ? null : wettest.get("rainMm"));
        out.put("maxDayDate", wettest == null ? null : wettest.get("date"));
        out.put("rainyDays", rainy);
        out.put("longestDrySpell", longestDry);
        out.put("preliminaryDays", preliminary);
        extremes(series, out);
        return out;
    }

    /**
     * heavyRainDays, rx1dayMm, rx5dayMm, drySpells10 and drySpells15. Consecutive means consecutive
     * calendar days with data: a day without data breaks a dry spell and a 5-day window.
     */
    static void extremes(List<Map<String, Object>> series, Map<String, Object> out) {
        int heavy = 0;
        Double rx1 = null;
        Double rx5 = null;
        int spells10 = 0;
        int spells15 = 0;
        int dry = 0;
        LocalDate previous = null;
        List<Double> run = new ArrayList<>(); // rain of the current run of consecutive days with data
        for (Map<String, Object> day : series) {
            double rain = (Double) day.get("rainMm");
            LocalDate date = LocalDate.parse((String) day.get("date"));
            if (previous != null && ChronoUnit.DAYS.between(previous, date) > 1) {
                spells10 += dry >= 10 ? 1 : 0;
                spells15 += dry >= 15 ? 1 : 0;
                dry = 0;
                run.clear();
            }
            if (rain >= HEAVY_RAIN_MM) heavy++;
            if (rx1 == null || rain > rx1) rx1 = rain;
            run.add(rain);
            if (run.size() >= RX_DAYS) {
                double sum = 0;
                for (int i = run.size() - RX_DAYS; i < run.size(); i++) sum += run.get(i);
                if (rx5 == null || sum > rx5) rx5 = sum;
            }
            if (rain < RAINY_DAY_MM) {
                dry++;
            } else {
                spells10 += dry >= 10 ? 1 : 0;
                spells15 += dry >= 15 ? 1 : 0;
                dry = 0;
            }
            previous = date;
        }
        spells10 += dry >= 10 ? 1 : 0;
        spells15 += dry >= 15 ? 1 : 0;
        out.put("heavyRainDays", heavy);
        out.put("rx1dayMm", rx1);
        out.put("rx5dayMm", round(rx5, 1));
        out.put("drySpells10", spells10);
        out.put("drySpells15", spells15);
    }

    private String chart(List<Map<String, Object>> series, LocalDate start, LocalDate end) {
        Map<String, Double> byDate = new LinkedHashMap<>();
        for (Map<String, Object> day : series) byDate.put((String) day.get("date"), (Double) day.get("rainMm"));
        List<DailyBarChart.Bar> bars = new ArrayList<>();
        if (!series.isEmpty()) {
            for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) bars.add(new DailyBarChart.Bar(d, byDate.get(d.toString())));
        }
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(chart.render(bars, CHART_WIDTH, CHART_HEIGHT));
    }

    private Map<String, Object> notFound(String message, String latest) {
        Map<String, Object> out = summary(List.of());
        out.put("message", message);
        out.put("latestDay", latest);
        out.put("pixels", 0);
        out.put("wholePixels", true);
        out.put("missingDays", 0);
        out.put("chart", chart(List.of(), null, null));
        return ordered(out);
    }

    private Map<String, Object> ordered(Map<String, Object> values) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (BlockPort port : definition().outputs()) out.put(port.name(), values.get(port.name()));
        return out;
    }
}
