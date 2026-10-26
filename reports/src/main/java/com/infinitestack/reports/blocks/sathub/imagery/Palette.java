package com.infinitestack.reports.blocks.sathub.imagery;

import java.util.List;

/**
 * A color ramp in the Satellite Hub format ({@code [{"pos": 0.0, "color": "#785a3c"}, ...]}, pos in
 * 0..1), interpolated linearly in RGB. Built-in ramps for when Satellite Hub has none.
 *
 * <p>OWNER: agent 3 (rendering). Stub until implemented.
 */
public record Palette(String name, List<Stop> stops) {

    public record Stop(double pos, int rgb) {}

    /** RGB (0xRRGGBB) at t in 0..1 (clamped). */
    public int colorAt(double t) {
        throw new UnsupportedOperationException("Palette not implemented yet");
    }

    /** Parses the Satellite Hub stops JSON. */
    public static Palette parse(String name, String stopsJson) {
        throw new UnsupportedOperationException("Palette not implemented yet");
    }

    /** Brown-yellow-green ramp for vegetation indices (NDVI, EVI, SAVI, NDRE, GNDVI). */
    public static Palette vegetation() {
        throw new UnsupportedOperationException("Palette not implemented yet");
    }

    /** Brown-white-blue ramp for water and moisture indices (NDWI, NDMI). */
    public static Palette water() {
        throw new UnsupportedOperationException("Palette not implemented yet");
    }
}
