package com.infinitestack.reports.blocks.brweather;

import static com.infinitestack.reports.blocks.brweather.BrWeatherTables.Q_CHIRPS_DAY;
import static com.infinitestack.reports.blocks.brweather.BrWeatherTables.round;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.infinitestack.reports.blocks.sathub.SatelliteHubLatestImageBlock;
import com.infinitestack.reports.blocks.sathub.imagery.CogReader;
import com.infinitestack.reports.blocks.sathub.imagery.PolygonMask;
import com.infinitestack.reports.blocks.sathub.imagery.Raster;
import com.infinitestack.reports.blocks.sathub.imagery.RasterGrid;
import com.infinitestack.reports.blocks.sathub.imagery.Resampling;
import com.infinitestack.reports.engine.block.BlockQueries;

/**
 * The daily CHIRPS rain of an area, read from the clips BR Weather keeps in the lake (one GeoTIFF of
 * Brazil per day, cataloged in {@code chirps_day}): for each day, only the pixels of the area's box
 * are read, and the pixels whose centre is inside the area are averaged (an area smaller than a pixel
 * takes the pixels of its box). Shared by the CHIRPS Blocks.
 *
 * <p>Only files under the configured lake directory are read: the path comes from a table, and the
 * Block must not become a way to read any file of the host.
 */
final class ChirpsClips {

    /** Pixels of the box of the area (200 x 200, about 1,100 km a side): keeps a whole state out. */
    static final int MAX_PIXELS = 40_000;
    static final Duration QUERY_TIMEOUT = Duration.ofSeconds(10);

    static final String LATEST_SQL = "SELECT max(day) FROM " + Q_CHIRPS_DAY + " WHERE valid_to IS NULL";
    static final String DAYS_SQL = "SELECT day, variant, path, width, height, west, south, east, north, crs FROM " + Q_CHIRPS_DAY
            + " WHERE valid_to IS NULL AND day BETWEEN ? AND ? ORDER BY day";

    /** The columns the reads use (for the Blocks' DataRequirement). */
    static final String[] COLUMNS = {"day", "variant", "path", "width", "height", "west", "south", "east", "north", "crs", "valid_to"};

    /**
     * What was read for a period.
     *
     * @param series      one per day with a value, oldest first: {date, rainMm (2 decimals), preliminary}
     * @param catalogDays days of the period in the catalog
     * @param missingDays days of the period without a value (not in the catalog, file missing, no pixel)
     * @param pixels      pixels averaged
     * @param wholePixels false when the area is smaller than a pixel and its box was used
     */
    record Period(List<Map<String, Object>> series, int catalogDays, int missingDays, int pixels, boolean wholePixels) {}

    private final BlockQueries queries;
    private final CogReader reader;
    private final Path root;

    ChirpsClips(BlockQueries queries, CogReader reader, Path root) {
        this.queries = queries;
        this.reader = reader;
        this.root = root.toAbsolutePath().normalize();
    }

    Path root() {
        return root;
    }

    /** Latest day in the catalog, or null. */
    LocalDate latest() {
        Object value = queries.read(QUERY_TIMEOUT, jdbc -> jdbc.queryForObject(LATEST_SQL, Object.class));
        return value == null ? null : LocalDate.parse(value.toString());
    }

    /** The rain of each day from {@code start} to {@code end} (both included) over the polygons. */
    Period read(List<List<double[][]>> polygons, LocalDate start, LocalDate end) throws IOException {
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
        return new Period(series, rows.size(), missing, use == null ? 0 : count(use), wholePixels);
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
