package com.infinitestack.reports.blocks.brweather;

import static com.infinitestack.reports.blocks.brweather.BrWeatherTables.CHIRPS_DAY;
import static com.infinitestack.reports.blocks.brweather.BrWeatherTables.Q_CHIRPS_DAY;
import static com.infinitestack.reports.blocks.brweather.BrWeatherTables.requirement;
import static com.infinitestack.reports.blocks.brweather.BrWeatherTables.round;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
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
import com.infinitestack.reports.blocks.sathub.imagery.PolygonMask;
import com.infinitestack.reports.blocks.sathub.imagery.Raster;
import com.infinitestack.reports.blocks.sathub.imagery.RasterGrid;
import com.infinitestack.reports.blocks.sathub.imagery.Resampling;
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
    /** Pixels of the box of the area (200 x 200, about 1,100 km a side): keeps a whole state out. */
    static final int MAX_PIXELS = 40_000;
    static final double RAINY_DAY_MM = 1.0;
    static final int CHART_WIDTH = 900;
    static final int CHART_HEIGHT = 260;
    static final Duration QUERY_TIMEOUT = Duration.ofSeconds(10);

    static final String LATEST_SQL = "SELECT max(day) FROM " + Q_CHIRPS_DAY + " WHERE valid_to IS NULL";
    static final String DAYS_SQL = "SELECT day, variant, path, width, height, west, south, east, north, crs FROM " + Q_CHIRPS_DAY
            + " WHERE valid_to IS NULL AND day BETWEEN ? AND ? ORDER BY day";

    private final BlockQueries queries;
    private final CogReader reader;
    private final Path root;
    private final DailyBarChart chart = new DailyBarChart();

    @Autowired
    public BrWeatherRainfallBlock(BlockQueries queries,
                                  @Value("${reports.brweather.chirps-dir:/opt/infinitestack/var/lake/bronze/br-weather-agent-app/chirps}") String root) {
        this(queries, new HttpCogReader(new LocalFileRangeFetcher()), Path.of(root));
    }

    BrWeatherRainfallBlock(BlockQueries queries, CogReader reader, Path root) {
        this.queries = queries;
        this.reader = reader;
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public BlockDefinition definition() {
        return new BlockDefinition("brweather-rainfall", "Daily rainfall (CHIRPS)",
                "CHIRPS v3 daily rain over an area: total, rainy days, longest dry spell and a bar chart",
                "1.0.0",
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
                        BlockPort.required("chart", DataType.STRING, "PNG bar chart of the daily rain, as a data:image/png;base64 URL")),
                List.of(requirement(CHIRPS_DAY, "day", "variant", "path", "width", "height", "west", "south", "east", "north", "crs", "valid_to")),
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
                        "Sums up the period and draws the daily bars."),
                List.of(
                        "geometry: connect car-property-geometry.geometry.",
                        "from / to: report inputs to follow a season; empty for the last 30 days.",
                        "chart goes straight into the template: <img src=\"{{<node>.chart}}\">."),
                List.of(
                        "A pixel is about 5.5 km: a small field takes the rain of its pixel, which also covers the neighbours.",
                        "The most recent days come from the preliminary series and are replaced by the final one when it comes out (preliminary).",
                        "The clips are files in the lake of the host (BR Weather); reports reads them from reports.brweather.chirps-dir "
                                + "(REPORTS_CHIRPS_DIR), the same directory as BRWEATHER_CHIRPS_DIR. A clip outside it is not read.",
                        "Reads one file per day: a year takes a few seconds."),
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

        Object latestValue = queries.read(QUERY_TIMEOUT, jdbc -> jdbc.queryForObject(LATEST_SQL, Object.class));
        if (latestValue == null) return notFound("BR Weather has no CHIRPS day loaded yet", null);
        LocalDate latest = LocalDate.parse(latestValue.toString());
        LocalDate end = to != null ? to : latest;
        LocalDate start = from != null ? from : end.minusDays(days - 1L);
        if (start.isAfter(end)) throw new IllegalArgumentException("from (" + start + ") is after to (" + end + ")");
        if (ChronoUnit.DAYS.between(start, end) >= MAX_DAYS) throw new IllegalArgumentException("the period is longer than " + MAX_DAYS + " days");

        List<Map<String, Object>> rows = queries.read(QUERY_TIMEOUT,
                jdbc -> jdbc.queryForList(DAYS_SQL, java.sql.Date.valueOf(start), java.sql.Date.valueOf(end)));

        RasterGrid grid = null;
        boolean[] use = null;
        boolean wholePixels = true;
        List<Map<String, Object>> series = new ArrayList<>();
        int missing = (int) ChronoUnit.DAYS.between(start, end) + 1 - rows.size();
        for (Map<String, Object> row : rows) {
            Path file = allowed(row.get("path").toString());
            if (!Files.isRegularFile(file)) {
                missing++;
                continue;
            }
            if (grid == null) {
                grid = grid(row, polygons);
                PolygonMask mask = PolygonMask.of(grid, polygons);
                wholePixels = mask.insideCount() > 0;
                use = wholePixels ? mask.inside() : all(grid);
            }
            Raster raster = reader.read(file.toString(), grid, Resampling.NEAREST);
            Double rain = mean(raster.values(), use);
            if (rain == null) {
                missing++;
                continue;
            }
            Map<String, Object> day = new LinkedHashMap<>();
            day.put("date", row.get("day").toString());
            day.put("rainMm", round(rain, 2));
            day.put("preliminary", "prelim".equals(row.get("variant")));
            series.add(day);
        }

        Map<String, Object> out = summary(series);
        out.put("from", start.toString());
        out.put("to", end.toString());
        out.put("latestDay", latest.toString());
        out.put("pixels", use == null ? 0 : count(use));
        out.put("wholePixels", wholePixels);
        out.put("missingDays", missing);
        out.put("chart", chart(series, start, end));
        if (series.isEmpty()) {
            out.put("found", false);
            out.put("message", rows.isEmpty()
                    ? "no CHIRPS day loaded between " + start + " and " + end + " (latest: " + latest + ")"
                    : "the area is outside the CHIRPS clips or their files are missing in " + root);
        }
        return ordered(out);
    }

    /** The clip's pixel lattice over the box of the area (at least one pixel). */
    static RasterGrid grid(Map<String, Object> row, List<List<double[][]>> polygons) {
        if (!String.valueOf(row.get("crs")).toUpperCase().endsWith("4326")) {
            throw new IllegalStateException("CHIRPS clip in " + row.get("crs") + "; only EPSG:4326 is supported");
        }
        double west = ((Number) row.get("west")).doubleValue();
        double north = ((Number) row.get("north")).doubleValue();
        double pixel = (((Number) row.get("east")).doubleValue() - west) / ((Number) row.get("width")).intValue();
        double[] box = SatelliteHubLatestImageBlock.bbox(polygons);
        double minX = west + Math.floor((box[0] - west) / pixel) * pixel;
        double maxY = north - Math.floor((north - box[3]) / pixel) * pixel;
        int width = Math.max(1, (int) Math.ceil((box[2] - minX) / pixel - 1e-9));
        int height = Math.max(1, (int) Math.ceil((maxY - box[1]) / pixel - 1e-9));
        if ((long) width * height > MAX_PIXELS) {
            throw new IllegalArgumentException("the area spans " + width + " x " + height + " CHIRPS pixels; at most " + MAX_PIXELS);
        }
        return new RasterGrid(width, height, minX, maxY, pixel, 4326);
    }

    /** The path from the table, refused when it is not under the configured lake directory. */
    Path allowed(String path) {
        Path file = Path.of(path).toAbsolutePath().normalize();
        if (!file.startsWith(root)) {
            throw new IllegalStateException("CHIRPS clip " + path + " is outside reports.brweather.chirps-dir (" + root + ")");
        }
        return file;
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
        return out;
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

    private static Double mean(float[] values, boolean[] use) {
        double sum = 0;
        int n = 0;
        for (int i = 0; i < values.length; i++) {
            if (use[i] && !Float.isNaN(values[i])) {
                sum += values[i];
                n++;
            }
        }
        return n == 0 ? null : sum / n;
    }

    private static boolean[] all(RasterGrid grid) {
        boolean[] out = new boolean[grid.width() * grid.height()];
        Arrays.fill(out, true);
        return out;
    }

    private static int count(boolean[] use) {
        int n = 0;
        for (boolean b : use) if (b) n++;
        return n;
    }
}
