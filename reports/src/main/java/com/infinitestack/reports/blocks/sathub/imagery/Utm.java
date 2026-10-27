package com.infinitestack.reports.blocks.sathub.imagery;

/**
 * WGS 84 / UTM conversions (EPSG 326zz north, 327zz south), the CRS of Sentinel-2 tiles. SIRGAS 2000
 * and WGS 84 differ by centimetres, below a 10 m pixel, so CAR geometries (4674) are used as WGS 84.
 * Transverse Mercator with the Krueger series to order n^6, in Karney's formulation (Karney 2011,
 * "Transverse Mercator with an accuracy of a few nanometers"): sub-millimetre inside a zone and still
 * well below a millimetre a few degrees outside it.
 */
public final class Utm {

    private static final double A = 6378137.0;
    private static final double F = 1 / 298.257223563;
    private static final double K0 = 0.9996;
    private static final double FALSE_EASTING = 500_000;
    private static final double FALSE_NORTHING_SOUTH = 10_000_000;

    private static final double E = Math.sqrt(F * (2 - F));
    private static final double E2 = F * (2 - F);
    private static final double N = F / (2 - F);
    /** Rectifying radius times the scale factor: metres per radian of the conformal sphere. */
    private static final double K0_RECT;
    /** Krueger coefficients, forward (alpha) and inverse (beta), index j = 1..6. */
    private static final double[] ALPHA;
    private static final double[] BETA;

    static {
        double n = N, n2 = n * n, n3 = n2 * n, n4 = n3 * n, n5 = n4 * n, n6 = n5 * n;
        K0_RECT = K0 * A / (1 + n) * (1 + n2 / 4 + n4 / 64 + n6 / 256);
        ALPHA = new double[] {0,
                n / 2 - 2 * n2 / 3 + 5 * n3 / 16 + 41 * n4 / 180 - 127 * n5 / 288 + 7891 * n6 / 37800,
                13 * n2 / 48 - 3 * n3 / 5 + 557 * n4 / 1440 + 281 * n5 / 630 - 1983433 * n6 / 1935360,
                61 * n3 / 240 - 103 * n4 / 140 + 15061 * n5 / 26880 + 167603 * n6 / 181440,
                49561 * n4 / 161280 - 179 * n5 / 168 + 6601661 * n6 / 7257600,
                34729 * n5 / 80640 - 3418889 * n6 / 1995840,
                212378941 * n6 / 319334400};
        BETA = new double[] {0,
                n / 2 - 2 * n2 / 3 + 37 * n3 / 96 - n4 / 360 - 81 * n5 / 512 + 96199 * n6 / 604800,
                n2 / 48 + n3 / 15 - 437 * n4 / 1440 + 46 * n5 / 105 - 1118711 * n6 / 3870720,
                17 * n3 / 480 - 37 * n4 / 840 - 209 * n5 / 4480 + 5569 * n6 / 90720,
                4397 * n4 / 161280 - 11 * n5 / 504 - 830251 * n6 / 7257600,
                4583 * n5 / 161280 - 108847 * n6 / 3991680,
                20648693 * n6 / 638668800};
    }

    private Utm() {}

    /** {x, y} in metres of (lon, lat) in degrees, in the UTM zone of {@code epsg}. */
    public static double[] forward(double lon, double lat, int epsg) {
        double lambda = Math.toRadians(normalizeLongitude(lon - centralMeridian(epsg)));
        double tau = Math.tan(Math.toRadians(lat));
        double tauPrime = conformalTan(tau);
        double cosLambda = Math.cos(lambda);
        // Gauss-Schreiber coordinates on the conformal sphere
        double xiPrime = Math.atan2(tauPrime, cosLambda);
        double etaPrime = asinh(Math.sin(lambda) / Math.hypot(tauPrime, cosLambda));
        double xi = xiPrime;
        double eta = etaPrime;
        for (int j = 1; j <= 6; j++) {
            xi += ALPHA[j] * Math.sin(2 * j * xiPrime) * Math.cosh(2 * j * etaPrime);
            eta += ALPHA[j] * Math.cos(2 * j * xiPrime) * Math.sinh(2 * j * etaPrime);
        }
        return new double[] {FALSE_EASTING + K0_RECT * eta, falseNorthing(epsg) + K0_RECT * xi};
    }

    /** {lon, lat} in degrees of (x, y) in the UTM zone of {@code epsg}. */
    public static double[] inverse(double x, double y, int epsg) {
        double xi = (y - falseNorthing(epsg)) / K0_RECT;
        double eta = (x - FALSE_EASTING) / K0_RECT;
        double xiPrime = xi;
        double etaPrime = eta;
        for (int j = 1; j <= 6; j++) {
            xiPrime -= BETA[j] * Math.sin(2 * j * xi) * Math.cosh(2 * j * eta);
            etaPrime -= BETA[j] * Math.cos(2 * j * xi) * Math.sinh(2 * j * eta);
        }
        double sinhEta = Math.sinh(etaPrime);
        double cosXi = Math.cos(xiPrime);
        double tauPrime = Math.sin(xiPrime) / Math.hypot(sinhEta, cosXi);
        double lambda = Math.atan2(sinhEta, cosXi);
        double lat = Math.toDegrees(Math.atan(geodeticTan(tauPrime)));
        double lon = normalizeLongitude(centralMeridian(epsg) + Math.toDegrees(lambda));
        return new double[] {lon, lat};
    }

    /** True when {@code epsg} is a WGS 84 UTM zone (32601-32660, 32701-32760). */
    public static boolean isUtm(int epsg) {
        return (epsg >= 32601 && epsg <= 32660) || (epsg >= 32701 && epsg <= 32760);
    }

    /** Tangent of the conformal latitude from the tangent of the geodetic latitude. */
    private static double conformalTan(double tau) {
        double sigma = Math.sinh(E * atanh(E * tau / Math.hypot(1, tau)));
        return tau * Math.hypot(1, sigma) - sigma * Math.hypot(1, tau);
    }

    /** Inverse of {@link #conformalTan} by Newton's method (converges in 2-3 steps). */
    private static double geodeticTan(double tauPrime) {
        double tau = tauPrime;
        for (int i = 0; i < 10; i++) {
            double tauPrimeI = conformalTan(tau);
            double delta = (tauPrime - tauPrimeI) / Math.hypot(1, tauPrimeI)
                    * (1 + (1 - E2) * tau * tau) / ((1 - E2) * Math.hypot(1, tau));
            tau += delta;
            if (Math.abs(delta) < 1e-14 * Math.max(1, Math.abs(tau))) break;
        }
        return tau;
    }

    private static double centralMeridian(int epsg) {
        if (!isUtm(epsg)) throw new IllegalArgumentException("not a WGS 84 UTM zone: EPSG:" + epsg);
        int zone = epsg % 100;
        return zone * 6 - 183;
    }

    private static double falseNorthing(int epsg) {
        if (!isUtm(epsg)) throw new IllegalArgumentException("not a WGS 84 UTM zone: EPSG:" + epsg);
        return epsg >= 32701 ? FALSE_NORTHING_SOUTH : 0;
    }

    private static double normalizeLongitude(double lon) {
        double r = ((lon + 180) % 360 + 360) % 360 - 180;
        return r == -180 && lon > 0 ? 180 : r;
    }

    private static double asinh(double v) {
        return Math.log(v + Math.sqrt(v * v + 1));
    }

    private static double atanh(double v) {
        return 0.5 * Math.log((1 + v) / (1 - v));
    }
}
