package com.infinitestack.reports.blocks.sathub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.infinitestack.reports.blocks.sathub.imagery.CogReader;
import com.infinitestack.reports.blocks.sathub.imagery.Raster;
import com.infinitestack.reports.blocks.sathub.imagery.RasterGrid;
import com.infinitestack.reports.blocks.sathub.imagery.Resampling;
import com.infinitestack.reports.blocks.sathub.imagery.SceneAsset;
import com.infinitestack.reports.blocks.sathub.imagery.SceneItem;
import com.infinitestack.reports.blocks.sathub.imagery.SceneSearch;
import com.infinitestack.reports.engine.block.BlockPort;

/**
 * {@link SatelliteHubLatestImageBlock} with a fake STAC search and a fake COG reader: the area's left
 * half is vegetation, the right half bare soil, with a cloud patch in the SCL. The newest scene is all
 * cloud, so the Block must skip it and use the next one.
 *
 * <p>Needs the real imagery helpers (Utm, PolygonMask, IndexFormula, IndexStats, Palette,
 * ImageryRenderer, SceneSelector). The index definitions come from {@link #rows()} through the
 * package-private {@code indexRows()} seam, as there is no in-memory database on the test classpath.
 */
class SatelliteHubLatestImageBlockTest {

    /** About 1.1 km x 1.1 km in Acre (UTM 19S). */
    static final Map<String, Object> AREA = polygon(-70.00, -8.00, -69.99, -7.99);
    static final Map<String, Object> FOOTPRINT = polygon(-70.5, -8.5, -69.5, -7.5);
    static final int EPSG = 32719;

    final List<String> reads = Collections.synchronizedList(new ArrayList<>());

    @Test
    void skipsTheCloudySceneAndMapsTheIndicesOfTheNextOne() throws Exception {
        Instant now = Instant.now();
        SceneItem cloudy = scene("S2B_CLOUDY", now.minus(2, ChronoUnit.DAYS), "cloudy");
        SceneItem clear = scene("S2A_CLEAR", now.minus(7, ChronoUnit.DAYS), "clear");
        Map<String, Object> out = run(block(List.of(cloudy, clear)), inputs("ndvi, evi,ndmi"));

        assertEquals(true, out.get("found"));
        assertNull(out.get("message"));
        assertEquals(2, out.get("candidatesTried"));
        List<?> skipped = (List<?>) out.get("skipped");
        assertEquals(1, skipped.size());
        assertEquals("S2B_CLOUDY", ((Map<?, ?>) skipped.get(0)).get("id"));
        assertEquals(100.0, ((Map<?, ?>) skipped.get(0)).get("areaCloudPercent"));

        Map<?, ?> scene = (Map<?, ?>) out.get("scene");
        assertEquals("S2A_CLEAR", scene.get("id"));
        assertEquals("19LFJ", scene.get("mgrsTile"));
        assertEquals(7L, ((Number) out.get("daysAgo")).longValue(), 1.0);
        assertEquals(10.0, out.get("pixelSizeM"));
        double cloud = (Double) out.get("areaCloudPercent");
        assertTrue(cloud > 0 && cloud < 30, "the cloud patch is a small share of the area: " + cloud);
        assertTrue(((String) out.get("trueColor")).startsWith("data:image/png;base64,"));

        List<?> indices = (List<?>) out.get("indices");
        assertEquals(List.of("ndvi", "evi", "ndmi"), indices.stream().map(i -> ((Map<?, ?>) i).get("slug")).toList());
        Map<?, ?> ndvi = (Map<?, ?>) indices.get(0);
        double mean = (Double) ndvi.get("mean");
        // left half (3500 / 4500 = 0.7778) and right half (500 / 4500 = 0.1111), about half each
        assertTrue(mean > 0.3 && mean < 0.6, "ndvi mean " + mean);
        assertEquals(0.7778, (Double) ndvi.get("max"), 0.001);
        assertEquals(0.1111, (Double) ndvi.get("min"), 0.001);
        assertTrue((Double) ndvi.get("validPercent") < 100, "the cloud is masked");
        assertEquals(0.0, ndvi.get("rangeMin"));
        assertEquals(1.0, ndvi.get("rangeMax"));
        assertEquals(5, ((List<?>) ndvi.get("legend")).size());
        assertTrue(((String) ndvi.get("image")).startsWith("data:image/png;base64,"));
        assertEquals(-1.0, ((Map<?, ?>) indices.get(2)).get("rangeMin"));

        assertTrue(reads.contains("S2B_CLOUDY/scl/NEAREST"));
        assertTrue(reads.contains("S2A_CLEAR/nir/BILINEAR"));
        assertFalse(reads.contains("S2B_CLOUDY/red/BILINEAR"), "bands are read only for the accepted scene");
    }

    @Test
    void reportsNotFoundWhenEveryCandidateIsCloudy() throws Exception {
        Instant now = Instant.now();
        List<SceneItem> scenes = new ArrayList<>();
        for (int i = 0; i < 6; i++) scenes.add(scene("S2_CLOUDY_" + i, now.minus(5L * i + 1, ChronoUnit.DAYS), "cloudy"));
        Map<String, Object> out = run(block(scenes), inputs(null));
        assertEquals(false, out.get("found"));
        assertEquals("the 4 most recent scenes had more than 30% clouds over the area", out.get("message"));
        assertEquals(4, out.get("candidatesTried"));
        assertEquals(4, ((List<?>) out.get("skipped")).size());
        assertEquals(List.of(), out.get("indices"));
    }

    @Test
    void reportsNotFoundWhenTheSearchIsEmpty() throws Exception {
        Map<String, Object> out = run(block(List.of()), inputs(null));
        assertEquals(false, out.get("found"));
        assertEquals("no Sentinel-2 scene with less than 40% clouds in the last 120 days", out.get("message"));
        assertEquals(0, out.get("candidatesTried"));
    }

    @Test
    void failsOnAnUnknownOrDisabledIndexListingTheAvailableOnes() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> block(List.of()).execute(inputs("ndvi,nbr"), null));
        assertTrue(e.getMessage().contains("'nbr'") && e.getMessage().contains("available: evi, ndmi, ndvi"), e.getMessage());
    }

    @Test
    void refusesAnIndexOfAnotherCollection() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> block(List.of()).execute(inputs("ndvi_landsat"), null));
        assertTrue(e.getMessage().contains("landsat-c2-l2 collection") && e.getMessage().contains("available: evi, ndmi, ndvi"),
                e.getMessage());
    }

    @Test
    void definitionDoesNotTouchTheDependencies() {
        assertEquals("sathub-latest-image", new SatelliteHubLatestImageBlock(null, null, null).definition().id());
        assertEquals("Satellite Hub", new SatelliteHubLatestImageBlock(null, null, null).docs().category());
    }

    // ---- fixtures

    SatelliteHubLatestImageBlock block(List<SceneItem> scenes) {
        SceneSearch search = (bbox, since, maxCloud, limit) -> {
            assertTrue(bbox[0] < bbox[2] && bbox[1] < bbox[3]);
            return scenes;
        };
        return new SatelliteHubLatestImageBlock(null, search, new FakeCogs()) {
            @Override
            List<Map<String, Object>> indexRows() {
                return rows();
            }
        };
    }

    static List<Map<String, Object>> rows() {
        return List.of(
                row("evi", "EVI", "2.5 * (nir - red) / (nir + 6 * red - 7.5 * blue + 1)", true),
                row("nbr", "NBR", "(nir - swir22) / (nir + swir22)", false),
                row("ndmi", "NDMI", "(nir - swir16) / (nir + swir16)", true),
                row("ndvi", "NDVI", "(nir - red) / (nir + red)", true),
                // A Landsat index whose bands are also Sentinel-2 asset names: it must not be read from Sentinel-2.
                row("ndvi_landsat", "NDVI (Landsat)", "(nir08 - red) / (nir08 + red)", true, "landsat-c2-l2"));
    }

    static Map<String, Object> row(String slug, String name, String formula, boolean enabled, String collection) {
        Map<String, Object> m = row(slug, name, formula, enabled);
        m.put("collection", collection);
        return m;
    }

    static Map<String, Object> row(String slug, String name, String formula, boolean enabled) {
        Map<String, Object> m = new HashMap<>();
        m.put("slug", slug);
        m.put("name", name);
        m.put("description", name + " index");
        m.put("formula", formula);
        m.put("collection", slug.equals("evi") ? null : "sentinel-2-l2a"); // null = Satellite Hub's legacy default, Sentinel-2
        m.put("mask_clouds", true);
        m.put("clamp_min", -1.0);
        m.put("clamp_max", 1.0);
        m.put("display_min", null);
        m.put("display_max", null);
        m.put("enabled", enabled);
        return m;
    }

    /** Vegetation on the left half, bare soil on the right; SCL: a cloud patch in the top-left corner, or all cloud. */
    final class FakeCogs implements CogReader {
        @Override
        public Raster read(String url, RasterGrid grid, Resampling resampling) {
            String[] parts = url.split("/"); // scene/kind/band
            reads.add(parts[0] + "/" + parts[2] + "/" + resampling);
            assertEquals(EPSG, grid.epsg());
            float[] values = new float[grid.width() * grid.height()];
            for (int row = 0; row < grid.height(); row++) {
                for (int col = 0; col < grid.width(); col++) {
                    boolean vegetation = col < grid.width() / 2;
                    values[row * grid.width() + col] = switch (parts[2]) {
                        case "scl" -> parts[1].equals("cloudy") ? 9
                                : row < grid.height() / 5 && col < grid.width() / 5 ? 8 : vegetation ? 4 : 5;
                        case "nir" -> vegetation ? 4000 : 2500;
                        case "red" -> vegetation ? 500 : 2000;
                        case "green" -> vegetation ? 900 : 1700;
                        case "blue" -> vegetation ? 400 : 1300;
                        case "swir16" -> vegetation ? 1800 : 3000;
                        default -> 1000;
                    };
                }
            }
            return new Raster(grid, values);
        }
    }

    static SceneItem scene(String id, Instant at, String kind) {
        Map<String, SceneAsset> assets = new LinkedHashMap<>();
        for (String band : List.of("blue", "green", "red", "nir", "swir16", "swir22", "scl")) {
            assets.put(band, new SceneAsset(id + "/" + kind + "/" + band, "scl".equals(band) ? 1 : 0.0001, -0.1, 10));
        }
        return new SceneItem(id, at, 12.5, EPSG, "sentinel-2a", "19LFJ", FOOTPRINT, assets,
                "https://earth-search.aws.element84.com/v1/collections/sentinel-2-l2a/items/" + id);
    }

    static Map<String, Object> inputs(String indices) {
        Map<String, Object> m = new HashMap<>();
        m.put("geometry", AREA);
        if (indices != null) m.put("indices", indices);
        return m;
    }

    static Map<String, Object> polygon(double minLon, double minLat, double maxLon, double maxLat) {
        List<List<Double>> ring = List.of(List.of(minLon, minLat), List.of(maxLon, minLat), List.of(maxLon, maxLat),
                List.of(minLon, maxLat), List.of(minLon, minLat));
        return Map.of("type", "Polygon", "coordinates", List.of(ring));
    }

    /** Runs the Block and checks the outputs against its ports, as the executor does. */
    static Map<String, Object> run(SatelliteHubLatestImageBlock block, Map<String, Object> inputs) throws Exception {
        Map<String, Object> out = block.execute(inputs, null);
        for (BlockPort port : block.definition().outputs()) {
            Object value = out.get(port.name());
            if (port.required()) assertNotNull(value, port.name());
            port.type().coerce(value);
        }
        assertEquals(block.definition().outputs().size(), out.size(), "only declared outputs");
        return out;
    }
}
