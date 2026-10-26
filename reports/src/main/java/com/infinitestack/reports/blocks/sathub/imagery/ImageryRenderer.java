package com.infinitestack.reports.blocks.sathub.imagery;

/**
 * Draws the PNGs of the latest-image Block, sized as the grid: true color (red/green/blue reflectance
 * with a percentile stretch over the area, gamma), and one index map per index (palette over
 * [min, max], clouds and nodata in grey, outside the area dimmed). Both draw the area outline. PNG
 * encoding uses {@code PropertyMapRenderer.png}. Labels, if any, must survive a JRE without fonts.
 *
 * <p>OWNER: agent 3 (rendering). Stub until implemented.
 */
public class ImageryRenderer {

    /**
     * @param red   reflectance (0..1, NaN = nodata) on the grid, same for green and blue
     * @param clear pixels without cloud/shadow (from SCL); null = all clear
     */
    public byte[] trueColor(float[] red, float[] green, float[] blue, boolean[] clear, PolygonMask area) {
        throw new UnsupportedOperationException("ImageryRenderer not implemented yet");
    }

    /**
     * @param values index values on the grid (NaN = no value)
     * @param clear  pixels without cloud/shadow; null = all clear
     */
    public byte[] indexMap(float[] values, boolean[] clear, PolygonMask area, Palette palette, double min, double max) {
        throw new UnsupportedOperationException("ImageryRenderer not implemented yet");
    }
}
