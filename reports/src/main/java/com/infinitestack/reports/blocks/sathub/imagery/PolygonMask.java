package com.infinitestack.reports.blocks.sathub.imagery;

import java.util.List;

/**
 * An area rasterized onto a grid: which pixel centres fall inside it (holes respected) and its
 * rings in pixel coordinates, to draw the outline. Rings come already projected to the grid CRS.
 *
 * <p>OWNER: agent 3 (rendering). Stub until implemented.
 */
public record PolygonMask(RasterGrid grid, boolean[] inside, List<double[][]> pixelRings) {

    /**
     * @param polygons each polygon is a list of rings (first outer, then holes), each ring a list of
     *                 {x, y} in the grid CRS
     */
    public static PolygonMask of(RasterGrid grid, List<List<double[][]>> polygons) {
        throw new UnsupportedOperationException("PolygonMask not implemented yet");
    }

    public int insideCount() {
        int n = 0;
        for (boolean b : inside) if (b) n++;
        return n;
    }
}
