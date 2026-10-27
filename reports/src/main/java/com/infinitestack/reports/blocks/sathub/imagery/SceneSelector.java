package com.infinitestack.reports.blocks.sathub.imagery;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Picks the scene to use for an area among search results (newest first): the newest scene whose
 * footprint covers the whole area; when none covers it all, the newest with the largest covered
 * share. A Sentinel-2 tile boundary can cut a property, and the same date comes as two items.
 *
 * <p>Coverage is estimated by sampling: a regular grid of points over the area bbox, kept where
 * inside the area (even-odd rule, so holes count as outside), and the share of those points inside
 * each footprint. Plain lon/lat is enough at property scale. "Whole" means at least
 * {@link #FULL_COVERAGE_PERCENT}, so a footprint edge running along the property border still counts.
 */
public final class SceneSelector {

    /** The chosen scene and the share of the area (0-100) inside its footprint. */
    public record Choice(SceneItem scene, double coveragePercent) {}

    static final double FULL_COVERAGE_PERCENT = 99.5;
    /** Grid side of the first sampling pass (~2,500 points over the bbox). */
    static final int GRID_SIDE = 50;
    /** A thin or sparse area leaves few points inside the bbox grid; densify until this many. */
    static final int MIN_SAMPLES = 500;
    static final int MAX_GRID_SIDE = 400;

    private SceneSelector() {}

    /** @param areaLonLat GeoJSON Polygon or MultiPolygon in lon/lat */
    public static Optional<Choice> choose(List<SceneItem> candidates, Map<String, Object> areaLonLat) {
        if (candidates == null || candidates.isEmpty()) return Optional.empty();
        List<double[]> samples = samplePoints(polygons(areaLonLat));
        if (samples.isEmpty()) return Optional.empty();

        Choice best = null;
        for (SceneItem scene : candidates) {
            double coverage = coveragePercent(samples, scene.footprint());
            if (coverage >= FULL_COVERAGE_PERCENT) return Optional.of(new Choice(scene, coverage));
            // Strictly greater: on a tie the earlier (newer) candidate stays.
            if (coverage > 0 && (best == null || coverage > best.coveragePercent())) best = new Choice(scene, coverage);
        }
        return Optional.ofNullable(best);
    }

    /** Share (0-100) of the area inside a GeoJSON footprint; 0 when the footprint is missing. */
    static double coveragePercent(Map<String, Object> areaLonLat, Map<String, Object> footprint) {
        List<double[]> samples = samplePoints(polygons(areaLonLat));
        return samples.isEmpty() ? 0 : coveragePercent(samples, footprint);
    }

    private static double coveragePercent(List<double[]> samples, Map<String, Object> footprint) {
        if (footprint == null) return 0;
        List<List<double[][]>> polygons = polygons(footprint);
        if (polygons.isEmpty()) return 0;
        int inside = 0;
        for (double[] p : samples) {
            if (contains(polygons, p[0], p[1])) inside++;
        }
        return 100.0 * inside / samples.size();
    }

    /** Grid cell centres over the bbox that fall inside the area, densified for thin areas. */
    static List<double[]> samplePoints(List<List<double[][]>> area) {
        if (area.isEmpty()) return List.of();
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        for (List<double[][]> polygon : area) {
            for (double[] p : polygon.get(0)) {
                minX = Math.min(minX, p[0]);
                maxX = Math.max(maxX, p[0]);
                minY = Math.min(minY, p[1]);
                maxY = Math.max(maxY, p[1]);
            }
        }
        if (!(maxX > minX) || !(maxY > minY)) return List.of();
        List<double[]> samples = new ArrayList<>();
        for (int side = GRID_SIDE; side <= MAX_GRID_SIDE; side *= 2) {
            samples = new ArrayList<>();
            double dx = (maxX - minX) / side, dy = (maxY - minY) / side;
            for (int i = 0; i < side; i++) {
                double y = minY + (i + 0.5) * dy;
                for (int j = 0; j < side; j++) {
                    double x = minX + (j + 0.5) * dx;
                    if (contains(area, x, y)) samples.add(new double[] {x, y});
                }
            }
            if (samples.size() >= MIN_SAMPLES) break;
        }
        return samples;
    }

    /** Even-odd test over every ring of each polygon (holes flip back to outside). */
    static boolean contains(List<List<double[][]>> polygons, double x, double y) {
        for (List<double[][]> polygon : polygons) {
            boolean inside = false;
            for (double[][] ring : polygon) {
                for (int i = 0, j = ring.length - 1; i < ring.length; j = i++) {
                    double xi = ring[i][0], yi = ring[i][1], xj = ring[j][0], yj = ring[j][1];
                    if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside;
                }
            }
            if (inside) return true;
        }
        return false;
    }

    /** GeoJSON Polygon / MultiPolygon to polygons of rings of {lon, lat}; anything else is empty. */
    @SuppressWarnings("unchecked")
    static List<List<double[][]>> polygons(Map<String, Object> geometry) {
        if (geometry == null || !(geometry.get("coordinates") instanceof List<?> coordinates)) return List.of();
        Object type = geometry.get("type");
        List<List<double[][]>> out = new ArrayList<>();
        if ("Polygon".equals(type)) {
            addPolygon(out, (List<Object>) coordinates);
        } else if ("MultiPolygon".equals(type)) {
            for (Object polygon : coordinates) addPolygon(out, (List<Object>) polygon);
        }
        return out;
    }

    private static void addPolygon(List<List<double[][]>> out, List<Object> rings) {
        List<double[][]> polygon = new ArrayList<>();
        for (Object ring : rings) {
            List<?> points = (List<?>) ring;
            double[][] coords = new double[points.size()][];
            for (int i = 0; i < coords.length; i++) {
                List<?> p = (List<?>) points.get(i);
                coords[i] = new double[] {((Number) p.get(0)).doubleValue(), ((Number) p.get(1)).doubleValue()};
            }
            if (coords.length >= 3) polygon.add(coords);
        }
        if (!polygon.isEmpty()) out.add(polygon);
    }
}
