package com.infinitestack.reports.blocks.sathub.imagery;

/**
 * WGS 84 / UTM conversions (EPSG 326zz north, 327zz south), the CRS of Sentinel-2 tiles. SIRGAS 2000
 * and WGS 84 differ by centimetres, below a 10 m pixel, so CAR geometries (4674) are used as WGS 84.
 * Transverse Mercator with the Krueger series (sub-millimetre inside a zone).
 *
 * <p>OWNER: agent 1 (COG reader + UTM). Stub until implemented.
 */
public final class Utm {

    private Utm() {}

    /** {x, y} in metres of (lon, lat) in degrees, in the UTM zone of {@code epsg}. */
    public static double[] forward(double lon, double lat, int epsg) {
        throw new UnsupportedOperationException("Utm not implemented yet");
    }

    /** {lon, lat} in degrees of (x, y) in the UTM zone of {@code epsg}. */
    public static double[] inverse(double x, double y, int epsg) {
        throw new UnsupportedOperationException("Utm not implemented yet");
    }

    /** True when {@code epsg} is a WGS 84 UTM zone (32601-32660, 32701-32760). */
    public static boolean isUtm(int epsg) {
        return (epsg >= 32601 && epsg <= 32660) || (epsg >= 32701 && epsg <= 32760);
    }
}
