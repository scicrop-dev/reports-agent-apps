package com.infinitestack.reports.blocks.sathub.imagery;

/**
 * Samples of one band on a {@link RasterGrid}, row-major, row 0 first. Values are the raw digital
 * numbers of the COG as floats; nodata (0 in Sentinel-2 L2A) and pixels outside the image are
 * {@link Float#NaN}. Scaling to reflectance is the caller's job.
 */
public record Raster(RasterGrid grid, float[] values) {

    public Raster {
        if (values.length != grid.width() * grid.height()) {
            throw new IllegalArgumentException("values do not match the grid: " + values.length);
        }
    }

    public float get(int col, int row) {
        return values[row * grid.width() + col];
    }
}
