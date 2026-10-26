package com.infinitestack.reports.blocks.sathub.imagery;

/**
 * A target grid in a projected CRS (a UTM zone of the Sentinel-2 scene): {@code width} x
 * {@code height} square pixels of {@code pixelSize} metres, whose top-left corner is
 * ({@code minX}, {@code maxY}). Row 0 is the northernmost row.
 */
public record RasterGrid(int width, int height, double minX, double maxY, double pixelSize, int epsg) {

    public double maxX() {
        return minX + width * pixelSize;
    }

    public double minY() {
        return maxY - height * pixelSize;
    }

    /** Projected x of the centre of column {@code col}. */
    public double x(int col) {
        return minX + (col + 0.5) * pixelSize;
    }

    /** Projected y of the centre of row {@code row}. */
    public double y(int row) {
        return maxY - (row + 0.5) * pixelSize;
    }
}
