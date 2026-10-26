package com.infinitestack.reports.blocks.sathub;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.infinitestack.reports.blocks.sathub.imagery.CogReader;
import com.infinitestack.reports.blocks.sathub.imagery.ImageryRenderer;
import com.infinitestack.reports.blocks.sathub.imagery.IndexFormula;
import com.infinitestack.reports.blocks.sathub.imagery.IndexStats;
import com.infinitestack.reports.blocks.sathub.imagery.Palette;
import com.infinitestack.reports.blocks.sathub.imagery.PolygonMask;
import com.infinitestack.reports.blocks.sathub.imagery.Raster;
import com.infinitestack.reports.blocks.sathub.imagery.RasterGrid;
import com.infinitestack.reports.blocks.sathub.imagery.Resampling;
import com.infinitestack.reports.blocks.sathub.imagery.SceneAsset;
import com.infinitestack.reports.blocks.sathub.imagery.SceneItem;
import com.infinitestack.reports.blocks.sathub.imagery.SceneSearch;
import com.infinitestack.reports.blocks.sathub.imagery.SceneSelector;
import com.infinitestack.reports.blocks.sathub.imagery.Utm;
import com.infinitestack.reports.engine.DataType;
import com.infinitestack.reports.engine.block.BlockDefinition;
import com.infinitestack.reports.engine.block.BlockDocs;
import com.infinitestack.reports.engine.block.BlockPort;
import com.infinitestack.reports.engine.block.BlockQueries;
import com.infinitestack.reports.engine.block.DataRequirement;
import com.infinitestack.reports.engine.block.ExecutionMetadata;
import com.infinitestack.reports.engine.block.ReportBlock;

/**
 * {@code sathub-latest-image}: the latest cloud-free Sentinel-2 L2A image of an area, as PNGs (true
 * color and one map per index), with the index statistics over the area.
 *
 * <p>Satellite Hub only processes the areas registered in it, on a schedule, and stores statistics,
 * not images. So this Block goes to the same source Satellite Hub uses (Earth Search STAC + the
 * Sentinel-2 COGs on AWS), reads only the window of the area and applies Satellite Hub's own index
 * definitions ({@code gold.satellite_hub_analytics_indices}) with the same methodology: reflectance =
 * DN * scale (offset not applied), clouds masked with the SCL band (4, 5, 6, 7 = clear).
 */
@Component
public class SatelliteHubLatestImageBlock implements ReportBlock {

    static final String SCHEMA = "gold";
    static final String INDICES = "satellite_hub_analytics_indices";

    static final String INDICES_SQL = "SELECT slug, name, description, formula, mask_clouds, clamp_min, clamp_max,"
            + " display_min, display_max, enabled FROM " + SCHEMA + "." + INDICES + " ORDER BY slug";

    static final String DEFAULT_INDICES = "ndvi,evi,ndmi";
    static final double DEFAULT_MAX_CLOUD = 40;
    static final double DEFAULT_MAX_AREA_CLOUD = 30;
    static final int DEFAULT_MAX_AGE_DAYS = 120;
    static final int DEFAULT_SIZE_PX = 800;
    static final int MIN_SIZE_PX = 200;
    static final int MAX_SIZE_PX = 1600;
    static final int SEARCH_LIMIT = 20;
    static final int MAX_CANDIDATES = 4;
    static final int READ_THREADS = 6;
    static final double MIN_PIXEL_M = 10;
    static final double MIN_MARGIN_M = 100;
    static final int LEGEND_STEPS = 5;

    static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

    /** STAC asset names of the Sentinel-2 L2A bands a formula may use. */
    static final Set<String> BANDS = Set.of("coastal", "blue", "green", "red", "rededge1", "rededge2", "rededge3",
            "nir", "nir08", "nir09", "swir16", "swir22");

    /** Indices shown on [0, 1] when Satellite Hub has no display range; the rest on [-1, 1]. */
    static final Set<String> VEGETATION = Set.of("ndvi", "evi", "savi", "gndvi", "ndre");
    static final Set<String> WATER = Set.of("ndwi", "ndmi");

    private final BlockQueries queries;
    private final SceneSearch scenes;
    private final CogReader cogs;
    private final ImageryRenderer renderer = new ImageryRenderer();

    public SatelliteHubLatestImageBlock(BlockQueries queries, SceneSearch scenes, CogReader cogs) {
        this.queries = queries;
        this.scenes = scenes;
        this.cogs = cogs;
    }

    @Override
    public BlockDefinition definition() {
        return new BlockDefinition("sathub-latest-image", "Latest satellite image (Sentinel-2)",
                "Latest cloud-free Sentinel-2 image of an area as PNGs (true color and index maps), with the index statistics",
                "1.0.0",
                List.of(
                        BlockPort.required("geometry", DataType.GEOMETRY,
                                "area (GeoJSON Polygon or MultiPolygon, lon/lat SIRGAS 2000); connect car-property-geometry.geometry"),
                        BlockPort.optional("indices", DataType.STRING,
                                "comma-separated Satellite Hub index slugs; default " + DEFAULT_INDICES),
                        BlockPort.optional("maxCloudPercent", DataType.NUMBER, "scene cloud cover filter, percent; default 40"),
                        BlockPort.optional("maxAreaCloudPercent", DataType.NUMBER,
                                "maximum cloud share inside the area, percent; a cloudier scene is skipped for the next older one; default 30"),
                        BlockPort.optional("maxAgeDays", DataType.NUMBER, "how far back to look for scenes, in days; default 120"),
                        BlockPort.optional("sizePx", DataType.NUMBER, "longest side of the images in pixels, 200 to 1600; default 800")),
                List.of(
                        BlockPort.required("found", DataType.BOOLEAN, "a scene with few enough clouds over the area was found"),
                        BlockPort.optional("message", DataType.STRING, "why no image was found"),
                        BlockPort.optional("scene", DataType.OBJECT,
                                "{id, acquiredAt, date, platform, mgrsTile, cloudCoverPercent, stacUrl}"),
                        BlockPort.optional("acquiredDate", DataType.DATE, "acquisition date (America/Sao_Paulo)"),
                        BlockPort.optional("daysAgo", DataType.NUMBER, "days between the acquisition and today"),
                        BlockPort.optional("coveragePercent", DataType.NUMBER, "share of the area inside the scene footprint"),
                        BlockPort.optional("areaCloudPercent", DataType.NUMBER, "share of the area under cloud, shadow or without data"),
                        BlockPort.optional("pixelSizeM", DataType.NUMBER, "pixel size of the images, metres"),
                        BlockPort.optional("widthPx", DataType.NUMBER, "image width, pixels"),
                        BlockPort.optional("heightPx", DataType.NUMBER, "image height, pixels"),
                        BlockPort.optional("trueColor", DataType.STRING, "true color PNG as a data:image/png;base64 URL"),
                        BlockPort.required("indices", DataType.ARRAY,
                                "one per index, in the requested order (empty when not found): {slug, name, description, image, mean, min, "
                                        + "max, stddev, p10, p50, p90, validPixels, validPercent, rangeMin, rangeMax, legend [{color, value, label}]}"),
                        BlockPort.required("candidatesTried", DataType.NUMBER, "scenes whose clouds over the area were checked"),
                        BlockPort.required("skipped", DataType.ARRAY,
                                "scenes skipped for clouds over the area, newest first: {id, date, areaCloudPercent}")),
                List.of(DataRequirement.table(SCHEMA, INDICES, "slug", "name", "description", "formula", "mask_clouds",
                        "clamp_min", "clamp_max", "display_min", "display_max", "enabled")),
                Duration.ofSeconds(110));
    }

    @Override
    public BlockDocs docs() {
        return new BlockDocs("Satellite Hub",
                "Shows the latest Sentinel-2 image of an area (a CAR property, a field) that is clear enough over it: the "
                        + "true color picture and a map per index (NDVI, EVI, NDMI...), with the index statistics over the area. "
                        + "Works for any area, also one that is not registered in Satellite Hub.",
                List.of(
                        "Reads the index definitions (formula, cloud mask, value and display ranges) Satellite Hub keeps in gold.satellite_hub_analytics_indices.",
                        "Searches Earth Search (Element 84), the STAC catalog Satellite Hub uses, for Sentinel-2 L2A scenes over the area in the last maxAgeDays days with at most maxCloudPercent of clouds.",
                        "Picks the newest scene that covers the whole area (or the one that covers most of it) and reads only the window of the area from its SCL band.",
                        "Counts the share of the area under cloud, cloud shadow or without data (SCL other than 4 vegetation, 5 bare soil, 6 water, 7 unclassified); above maxAreaCloudPercent it tries the next older scene, up to 4.",
                        "Reads the bands the formulas need from the Cloud Optimized GeoTIFFs on AWS, in the scene's UTM zone, at 10 m or coarser for big areas (sizePx).",
                        "Computes each index per pixel as Satellite Hub does: reflectance = digital number x 0.0001 (offset not applied), clamped to the index range, clouds masked.",
                        "Draws the true color image and one map per index with the area outline, and computes mean, min, max, standard deviation and percentiles over the clear pixels inside the area."),
                List.of(
                        "geometry: connect car-property-geometry.geometry.",
                        "indices: a fixed value such as ndvi,evi,ndmi,ndre (the slugs Satellite Hub has enabled) or a report input.",
                        "Images go straight into the template: <img src=\"{{<node>.trueColor}}\"> and, inside {{#<node>.indices}}, <img src=\"{{image}}\">.",
                        "Test {{#<node>.found}} before showing the image and {{^<node>.found}} to print {{<node>.message}}: no image is not a failure."),
                List.of(
                        "It is the latest single acquisition, not a mosaic or a composite: a part of the area can be cloudy or outside the scene (coveragePercent).",
                        "The SCL cloud mask misses thin haze and some shadows, and can mark bright soil or buildings as cloud.",
                        "Pixels are 10 m (bands at 20 m are resampled); for big areas the pixel grows so the longest side fits sizePx.",
                        "Indices describe vegetation vigour and moisture; they do not identify the crop nor prove deforestation.",
                        "Needs outbound HTTPS from the host to earth-search.aws.element84.com and sentinel-cogs.s3.us-west-2.amazonaws.com; slower than the database Blocks (a few seconds to tens of seconds).",
                        "The index definitions come from Satellite Hub: the Block appears once Satellite Hub is installed and published them."),
                "Sentinel-2 L2A (ESA Copernicus) on AWS, through Earth Search (Element 84); index formulas from the Satellite Hub agent app "
                        + "(gold.satellite_hub_analytics_indices)",
                "{{#<node>.found}}<img src=\"{{<node>.trueColor}}\"> Imagem de {{<node>.acquiredDate}} ({{<node>.daysAgo}} dias)"
                        + "{{#<node>.indices}}<h3>{{name}}: média {{mean}}</h3><img src=\"{{image}}\">{{/<node>.indices}}{{/<node>.found}}"
                        + "{{^<node>.found}}{{<node>.message}}{{/<node>.found}}");
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> inputs, ExecutionMetadata metadata) throws Exception {
        Map<String, Object> geometry = geometry(inputs.get("geometry"));
        List<List<double[][]>> polygons = polygons(geometry);
        List<String> slugs = slugs(inputs.get("indices"));
        double maxCloud = number(inputs.get("maxCloudPercent"), DEFAULT_MAX_CLOUD);
        double maxAreaCloud = number(inputs.get("maxAreaCloudPercent"), DEFAULT_MAX_AREA_CLOUD);
        int maxAgeDays = (int) Math.max(1, number(inputs.get("maxAgeDays"), DEFAULT_MAX_AGE_DAYS));
        int sizePx = (int) Math.max(MIN_SIZE_PX, Math.min(MAX_SIZE_PX, number(inputs.get("sizePx"), DEFAULT_SIZE_PX)));

        List<IndexDefinition> indices = resolveIndices(slugs);
        LocalDate today = LocalDate.now(ZONE);

        List<SceneItem> found;
        try {
            found = scenes.search(bbox(polygons), today.minusDays(maxAgeDays), maxCloud, SEARCH_LIMIT);
        } catch (IOException e) {
            throw new IOException("could not search Sentinel-2 scenes on Earth Search: " + e.getMessage(), e);
        }
        if (found.isEmpty()) {
            return notFound("no Sentinel-2 scene with less than " + fmt(maxCloud) + "% clouds in the last " + maxAgeDays + " days", 0, List.of());
        }

        List<SceneItem> remaining = new ArrayList<>(found);
        List<Map<String, Object>> skipped = new ArrayList<>();
        int tried = 0;
        while (tried < MAX_CANDIDATES && !remaining.isEmpty()) {
            Optional<SceneSelector.Choice> choice = SceneSelector.choose(remaining, geometry);
            if (choice.isEmpty() || choice.get().coveragePercent() <= 0) break;
            SceneItem scene = choice.get().scene();
            remaining.remove(scene);
            tried++;

            RasterGrid grid = grid(polygons, scene.epsg(), sizePx);
            PolygonMask area = PolygonMask.of(grid, project(polygons, scene.epsg()));
            int inside = area.insideCount();
            if (inside == 0) throw new IllegalArgumentException("the area is smaller than one " + fmt(grid.pixelSize()) + " m pixel");

            boolean[] clear = clearPixels(read(scene, "scl", grid, Resampling.NEAREST));
            int cloudy = 0;
            for (int i = 0; i < clear.length; i++) if (area.inside()[i] && !clear[i]) cloudy++;
            double areaCloud = 100.0 * cloudy / inside;
            if (areaCloud > maxAreaCloud) {
                skipped.add(map("id", scene.id(), "date", date(scene).toString(), "areaCloudPercent", round(areaCloud, 1)));
                continue;
            }
            return image(scene, choice.get().coveragePercent(), areaCloud, grid, area, clear, indices, today, tried, skipped);
        }
        String message = tried == 0
                ? "no Sentinel-2 scene of the last " + maxAgeDays + " days covers the area"
                : "the " + tried + " most recent scenes had more than " + fmt(maxAreaCloud) + "% clouds over the area";
        return notFound(message, tried, skipped);
    }

    /** One index as Satellite Hub defines it, with the formula already parsed. */
    record IndexDefinition(String slug, String name, String description, IndexFormula formula, boolean maskClouds,
                           Double clampMin, Double clampMax, Double displayMin, Double displayMax) {}

    /**
     * Rows of {@code gold.satellite_hub_analytics_indices} (enabled or not). Package-private seam: the
     * unit test overrides it, since BlockQueries needs a real PostgreSQL and there is no in-memory database
     * on the test classpath.
     */
    List<Map<String, Object>> indexRows() {
        return queries.read(Duration.ofSeconds(10), jdbc -> jdbc.queryForList(INDICES_SQL));
    }

    private List<IndexDefinition> resolveIndices(List<String> slugs) {
        Map<String, Map<String, Object>> enabled = new LinkedHashMap<>();
        for (Map<String, Object> row : indexRows()) {
            if (Boolean.TRUE.equals(row.get("enabled")) && row.get("slug") != null) enabled.put(row.get("slug").toString(), row);
        }
        List<IndexDefinition> out = new ArrayList<>();
        for (String slug : slugs) {
            Map<String, Object> row = enabled.get(slug);
            if (row == null) {
                throw new IllegalArgumentException("Satellite Hub has no enabled index '" + slug + "'; available: "
                        + (enabled.isEmpty() ? "none" : String.join(", ", enabled.keySet())));
            }
            IndexFormula formula;
            try {
                formula = IndexFormula.parse(String.valueOf(row.get("formula")), BANDS);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("formula of index '" + slug + "' is not supported: " + e.getMessage(), e);
            }
            out.add(new IndexDefinition(slug, str(row.get("name")), str(row.get("description")), formula,
                    !Boolean.FALSE.equals(row.get("mask_clouds")), dbl(row.get("clamp_min")), dbl(row.get("clamp_max")),
                    dbl(row.get("display_min")), dbl(row.get("display_max"))));
        }
        return out;
    }

    /** Reads the bands of the accepted scene and builds the outputs. */
    private Map<String, Object> image(SceneItem scene, double coverage, double areaCloud, RasterGrid grid, PolygonMask area,
                                      boolean[] clear, List<IndexDefinition> indices, LocalDate today, int tried,
                                      List<Map<String, Object>> skipped) throws Exception {
        Set<String> bands = new LinkedHashSet<>(List.of("red", "green", "blue"));
        indices.forEach(index -> bands.addAll(index.formula().bands()));
        Map<String, float[]> reflectance = readReflectance(scene, bands, grid);

        List<Map<String, Object>> indexOut = new ArrayList<>();
        for (IndexDefinition index : indices) indexOut.add(index(index, reflectance, clear, area));

        LocalDate date = date(scene);
        Map<String, Object> sceneOut = map("id", scene.id(), "acquiredAt", scene.datetime().toString(), "date", date.toString(),
                "platform", scene.platform(), "mgrsTile", scene.mgrsTile(), "cloudCoverPercent", round(scene.cloudCover(), 1),
                "stacUrl", scene.selfHref());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("found", true);
        out.put("message", null);
        out.put("scene", sceneOut);
        out.put("acquiredDate", date.toString());
        out.put("daysAgo", ChronoUnit.DAYS.between(date, today));
        out.put("coveragePercent", round(coverage, 1));
        out.put("areaCloudPercent", round(areaCloud, 1));
        out.put("pixelSizeM", grid.pixelSize());
        out.put("widthPx", grid.width());
        out.put("heightPx", grid.height());
        out.put("trueColor", dataUri(renderer.trueColor(reflectance.get("red"), reflectance.get("green"), reflectance.get("blue"), clear, area)));
        out.put("indices", indexOut);
        out.put("candidatesTried", tried);
        out.put("skipped", skipped);
        return out;
    }

    /** One index: per-pixel values, statistics over the clear pixels inside the area, map and legend. */
    private Map<String, Object> index(IndexDefinition index, Map<String, float[]> reflectance, boolean[] clear, PolygonMask area) {
        int n = clear.length;
        float[] values = new float[n];
        int[] at = {0};
        ToDoubleFunction<String> band = name -> reflectance.get(name)[at[0]];
        boolean[] valid = new boolean[n];
        for (int i = 0; i < n; i++) {
            at[0] = i;
            double v = index.formula().evaluate(band);
            if (!Double.isNaN(v)) {
                if (index.clampMin() != null) v = Math.max(index.clampMin(), v);
                if (index.clampMax() != null) v = Math.min(index.clampMax(), v);
            }
            if (index.maskClouds() && !clear[i]) v = Double.NaN;
            values[i] = (float) v;
            valid[i] = area.inside()[i] && (clear[i] || !index.maskClouds());
        }
        IndexStats stats = IndexStats.of(values, valid);

        boolean vegetation = VEGETATION.contains(index.slug());
        double min = index.displayMin() != null ? index.displayMin() : vegetation ? 0 : -1;
        double max = index.displayMax() != null ? index.displayMax() : 1;
        Palette palette = WATER.contains(index.slug()) ? Palette.water() : Palette.vegetation();
        byte[] png = renderer.indexMap(values, index.maskClouds() ? clear : null, area, palette, min, max);

        int inside = area.insideCount();
        return map("slug", index.slug(), "name", index.name(), "description", index.description(), "image", dataUri(png),
                "mean", round(stats.mean(), 4), "min", round(stats.min(), 4), "max", round(stats.max(), 4),
                "stddev", round(stats.stddev(), 4), "p10", round(stats.p10(), 4), "p50", round(stats.p50(), 4),
                "p90", round(stats.p90(), 4), "validPixels", stats.validPixels(),
                "validPercent", round(inside == 0 ? 0 : 100.0 * stats.validPixels() / inside, 1),
                "rangeMin", min, "rangeMax", max, "legend", legend(palette, min, max, LEGEND_STEPS));
    }

    /** Reads the bands in parallel and turns digital numbers into reflectance (scale only, as Satellite Hub). */
    private Map<String, float[]> readReflectance(SceneItem scene, Set<String> bands, RasterGrid grid) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(READ_THREADS, bands.size()));
        try {
            Map<String, Future<Raster>> pending = new LinkedHashMap<>();
            for (String band : bands) pending.put(band, pool.submit(() -> read(scene, band, grid, Resampling.BILINEAR)));
            Map<String, float[]> out = new LinkedHashMap<>();
            for (Map.Entry<String, Future<Raster>> entry : pending.entrySet()) {
                Raster raster;
                try {
                    raster = entry.getValue().get();
                } catch (ExecutionException e) {
                    throw e.getCause() instanceof Exception cause ? cause : e;
                }
                double scale = scene.assets().get(entry.getKey()).scale();
                if (!(scale > 0)) scale = 0.0001;
                float[] values = raster.values();
                float[] reflectance = new float[values.length];
                for (int i = 0; i < values.length; i++) reflectance[i] = (float) (values[i] * scale);
                out.put(entry.getKey(), reflectance);
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    private Raster read(SceneItem scene, String band, RasterGrid grid, Resampling resampling) throws IOException {
        SceneAsset asset = scene.assets().get(band);
        if (asset == null || asset.href() == null) throw new IOException("scene " + scene.id() + " has no band '" + band + "'");
        try {
            return cogs.read(asset.href(), grid, resampling);
        } catch (IOException e) {
            throw new IOException("could not read band " + band + " of scene " + scene.id() + ": " + e.getMessage(), e);
        }
    }

    /** SCL 4 vegetation, 5 bare soil, 6 water, 7 unclassified are clear; the rest (and no data) is not. */
    static boolean[] clearPixels(Raster scl) {
        float[] values = scl.values();
        boolean[] clear = new boolean[values.length];
        for (int i = 0; i < values.length; i++) {
            float v = values[i];
            clear[i] = !Float.isNaN(v) && v >= 4 && v <= 7 && v == Math.rint(v);
        }
        return clear;
    }

    /**
     * The grid in the scene's UTM zone: the area's box plus a margin of 5% of its longest side (at
     * least 100 m), pixels of 10 m or a coarser multiple of 10 so the longest side fits {@code sizePx}.
     */
    static RasterGrid grid(List<List<double[][]>> polygons, int epsg, int sizePx) {
        if (!Utm.isUtm(epsg)) throw new IllegalArgumentException("scene CRS EPSG:" + epsg + " is not a UTM zone");
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        for (List<double[][]> polygon : project(polygons, epsg)) {
            for (double[][] ring : polygon) {
                for (double[] p : ring) {
                    minX = Math.min(minX, p[0]);
                    maxX = Math.max(maxX, p[0]);
                    minY = Math.min(minY, p[1]);
                    maxY = Math.max(maxY, p[1]);
                }
            }
        }
        double margin = Math.max(MIN_MARGIN_M, 0.05 * Math.max(maxX - minX, maxY - minY));
        minX -= margin;
        maxX += margin;
        minY -= margin;
        maxY += margin;
        double longest = Math.max(maxX - minX, maxY - minY);
        double pixel = Math.max(MIN_PIXEL_M, Math.ceil(longest / sizePx / 10) * 10);
        // Snap to the 10 m Sentinel-2 lattice, so at 10 m the grid pixels match the image pixels.
        minX = Math.floor(minX / 10) * 10;
        maxY = Math.ceil(maxY / 10) * 10;
        int width = (int) Math.ceil((maxX - minX) / pixel);
        int height = (int) Math.ceil((maxY - minY) / pixel);
        return new RasterGrid(width, height, minX, maxY, pixel, epsg);
    }

    static List<List<double[][]>> project(List<List<double[][]>> polygons, int epsg) {
        List<List<double[][]>> out = new ArrayList<>();
        for (List<double[][]> polygon : polygons) {
            List<double[][]> rings = new ArrayList<>();
            for (double[][] ring : polygon) {
                double[][] projected = new double[ring.length][];
                for (int i = 0; i < ring.length; i++) projected[i] = Utm.forward(ring[i][0], ring[i][1], epsg);
                rings.add(projected);
            }
            out.add(rings);
        }
        return out;
    }

    static double[] bbox(List<List<double[][]>> polygons) {
        double[] box = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
        for (List<double[][]> polygon : polygons) {
            for (double[] p : polygon.get(0)) {
                box[0] = Math.min(box[0], p[0]);
                box[1] = Math.min(box[1], p[1]);
                box[2] = Math.max(box[2], p[0]);
                box[3] = Math.max(box[3], p[1]);
            }
        }
        return box;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> geometry(Object value) {
        if (!(value instanceof Map<?, ?> m)) throw new IllegalArgumentException("geometry is required");
        return (Map<String, Object>) m;
    }

    /** Polygon or MultiPolygon GeoJSON as polygons of rings of {lon, lat}. */
    static List<List<double[][]>> polygons(Map<String, Object> geometry) {
        Object type = geometry.get("type");
        Object coordinates = geometry.get("coordinates");
        List<List<double[][]>> out = new ArrayList<>();
        if ("Polygon".equals(type)) {
            out.add(polygon(coordinates));
        } else if ("MultiPolygon".equals(type) && coordinates instanceof List<?> list) {
            for (Object polygon : list) out.add(polygon(polygon));
        } else {
            throw new IllegalArgumentException("geometry must be a GeoJSON Polygon or MultiPolygon, got " + type);
        }
        if (out.isEmpty()) throw new IllegalArgumentException("geometry has no polygon");
        return out;
    }

    private static List<double[][]> polygon(Object coordinates) {
        if (!(coordinates instanceof List<?> rings) || rings.isEmpty()) throw new IllegalArgumentException("polygon has no rings");
        List<double[][]> out = new ArrayList<>();
        for (Object ring : rings) {
            if (!(ring instanceof List<?> points) || points.size() < 4) throw new IllegalArgumentException("polygon ring has fewer than 4 points");
            double[][] coords = new double[points.size()][];
            for (int i = 0; i < points.size(); i++) {
                if (!(points.get(i) instanceof List<?> p) || p.size() < 2
                        || !(p.get(0) instanceof Number lon) || !(p.get(1) instanceof Number lat)) {
                    throw new IllegalArgumentException("polygon point is not [lon, lat]");
                }
                coords[i] = new double[] {lon.doubleValue(), lat.doubleValue()};
            }
            out.add(coords);
        }
        return out;
    }

    static List<String> slugs(Object value) {
        String text = value instanceof String s && !s.isBlank() ? s : DEFAULT_INDICES;
        List<String> out = Arrays.stream(text.split(",")).map(s -> s.trim().toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty()).distinct().collect(Collectors.toList());
        return out.isEmpty() ? List.of(DEFAULT_INDICES.split(",")) : out;
    }

    /** Legend stops of a palette over [min, max]: {color "#rrggbb", value, label}. */
    static List<Map<String, Object>> legend(Palette palette, double min, double max, int steps) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < steps; i++) {
            double t = steps == 1 ? 0 : (double) i / (steps - 1);
            double value = min + t * (max - min);
            out.add(map("color", String.format("#%06x", palette.colorAt(t) & 0xFFFFFF), "value", round(value, 4),
                    "label", String.format(Locale.ROOT, "%.2f", value)));
        }
        return out;
    }

    static String dataUri(byte[] png) {
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(png);
    }

    private static Map<String, Object> notFound(String message, int tried, List<Map<String, Object>> skipped) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("found", false);
        out.put("message", message);
        for (String name : List.of("scene", "acquiredDate", "daysAgo", "coveragePercent", "areaCloudPercent", "pixelSizeM",
                "widthPx", "heightPx", "trueColor")) {
            out.put(name, null);
        }
        out.put("indices", List.of());
        out.put("candidatesTried", tried);
        out.put("skipped", skipped);
        return out;
    }

    private static LocalDate date(SceneItem scene) {
        return scene.datetime().atZone(ZONE).toLocalDate();
    }

    private static double number(Object value, double fallback) {
        return value instanceof Number n && Double.isFinite(n.doubleValue()) ? n.doubleValue() : fallback;
    }

    private static Double dbl(Object value) {
        return value instanceof Number n ? n.doubleValue() : null;
    }

    private static String str(Object value) {
        return value == null ? null : value.toString();
    }

    private static String fmt(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    private static Double round(double value, int scale) {
        if (!Double.isFinite(value)) return null;
        double factor = Math.pow(10, scale);
        return Math.round(value * factor) / factor;
    }

    private static Map<String, Object> map(Object... keyValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) m.put((String) keyValues[i], keyValues[i + 1]);
        return m;
    }
}
