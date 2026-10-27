package com.infinitestack.reports.blocks.sathub.imagery;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * An area rasterized onto a grid: which pixel centres fall inside it (holes respected) and its
 * rings in pixel coordinates, to draw the outline. Rings come already projected to the grid CRS.
 *
 * <p>Rasterization is a scanline per row: the crossings of every ring edge with the row's centre y
 * are sorted and the spans between pairs are filled (even-odd rule, so holes stay empty). Each
 * polygon of a MultiPolygon is filled on its own and the results are OR-ed, so overlapping parts do
 * not cancel each other.
 */
public record PolygonMask(RasterGrid grid, boolean[] inside, List<double[][]> pixelRings) {

    /**
     * @param polygons each polygon is a list of rings (first outer, then holes), each ring a list of
     *                 {x, y} in the grid CRS
     */
    public static PolygonMask of(RasterGrid grid, List<List<double[][]>> polygons) {
        boolean[] inside = new boolean[grid.width() * grid.height()];
        List<double[][]> pixelRings = new ArrayList<>();
        if (polygons != null) {
            for (List<double[][]> polygon : polygons) {
                if (polygon == null || polygon.isEmpty()) continue;
                fill(grid, polygon, inside);
                for (double[][] ring : polygon) pixelRings.add(toPixels(grid, ring));
            }
        }
        return new PolygonMask(grid, inside, List.copyOf(pixelRings));
    }

    /** Even-odd fill of one polygon (outer ring + holes), OR-ed into {@code inside}. */
    private static void fill(RasterGrid grid, List<double[][]> rings, boolean[] inside) {
        int edges = 0;
        double minY = Double.POSITIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (double[][] ring : rings) {
            edges += ring.length;
            for (double[] p : ring) {
                minY = Math.min(minY, p[1]);
                maxY = Math.max(maxY, p[1]);
            }
        }
        if (edges == 0) return;
        // rows whose centre lies within the polygon's y extent
        int firstRow = Math.max(0, (int) Math.floor((grid.maxY() - maxY) / grid.pixelSize() - 0.5));
        int lastRow = Math.min(grid.height() - 1, (int) Math.ceil((grid.maxY() - minY) / grid.pixelSize() - 0.5));
        double[] crossings = new double[edges];
        for (int row = firstRow; row <= lastRow; row++) {
            double y = grid.y(row);
            int n = 0;
            for (double[][] ring : rings) {
                int len = ring.length;
                for (int i = 0; i < len; i++) {
                    double[] a = ring[i];
                    double[] b = ring[(i + 1) % len]; // closes the ring whether or not it repeats the first point
                    // half-open rule: a vertex on the scanline counts once
                    if ((a[1] <= y) != (b[1] <= y)) {
                        crossings[n++] = a[0] + (y - a[1]) / (b[1] - a[1]) * (b[0] - a[0]);
                    }
                }
            }
            Arrays.sort(crossings, 0, n);
            int offset = row * grid.width();
            for (int k = 0; k + 1 < n; k += 2) {
                // columns whose centre x is in [x0, x1)
                int c0 = Math.max(0, (int) Math.ceil((crossings[k] - grid.minX()) / grid.pixelSize() - 0.5));
                int c1 = Math.min(grid.width(),
                        (int) Math.ceil((crossings[k + 1] - grid.minX()) / grid.pixelSize() - 0.5));
                for (int col = c0; col < c1; col++) inside[offset + col] = true;
            }
        }
    }

    /** Ring in pixel coordinates (col, row as doubles; pixel (0, 0) spans 0..1 on both axes). */
    private static double[][] toPixels(RasterGrid grid, double[][] ring) {
        double[][] out = new double[ring.length][];
        for (int i = 0; i < ring.length; i++) {
            out[i] = new double[] {
                    (ring[i][0] - grid.minX()) / grid.pixelSize(),
                    (grid.maxY() - ring[i][1]) / grid.pixelSize()};
        }
        return out;
    }

    public int insideCount() {
        int n = 0;
        for (boolean b : inside) if (b) n++;
        return n;
    }
}
