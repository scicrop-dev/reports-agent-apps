package com.infinitestack.reports.blocks.brweather;

/**
 * Daily reference evapotranspiration (ET0, grass reference) following FAO Irrigation and Drainage
 * Paper 56 (Allen et al., 1998). Pure functions, no I/O.
 *
 * <p>{@link #penmanMonteith} is equation 6 with the daily steps of chapter 3 (example 18): mean
 * temperature as the mean of the daily maximum and minimum (eq. 9 for the saturation vapour
 * pressure, eq. 13 for its slope), atmospheric pressure from the elevation (eq. 7), extraterrestrial
 * radiation from the latitude and the day of the year (eq. 21-25), clear-sky radiation with the
 * elevation (eq. 37), net radiation with albedo 0.23 (eq. 38-40) and soil heat flux 0 for a day.
 *
 * <p>{@link #hargreaves} (eq. 52) needs only the temperatures; FAO-56 recommends it when the radiation,
 * humidity or wind are missing.
 */
public final class Et0 {

    private Et0() {}

    /** Solar constant, MJ m-2 min-1. */
    static final double GSC = 0.0820;
    /** Stefan-Boltzmann constant, MJ K-4 m-2 day-1. */
    static final double SIGMA = 4.903e-9;
    static final double ALBEDO = 0.23;

    /**
     * ET0 in mm/day.
     *
     * @param tMax       daily maximum air temperature at 2 m, C
     * @param tMin       daily minimum air temperature at 2 m, C
     * @param ea         actual vapour pressure, kPa
     * @param u2         wind speed at 2 m, m/s
     * @param rs         incoming solar radiation, MJ m-2 day-1
     * @param elevationM elevation above sea level, m
     * @param latitude   decimal degrees, negative south
     * @param dayOfYear  1 to 366
     */
    public static double penmanMonteith(double tMax, double tMin, double ea, double u2, double rs,
                                        double elevationM, double latitude, int dayOfYear) {
        double tMean = (tMax + tMin) / 2;
        double es = (saturationVapourPressure(tMax) + saturationVapourPressure(tMin)) / 2;
        double delta = 4098 * saturationVapourPressure(tMean) / Math.pow(tMean + 237.3, 2);
        double pressure = 101.3 * Math.pow((293 - 0.0065 * elevationM) / 293, 5.26);
        double gamma = 0.665e-3 * pressure;

        double ra = extraterrestrialRadiation(latitude, dayOfYear);
        double rso = (0.75 + 2e-5 * elevationM) * ra;
        double rns = (1 - ALBEDO) * rs;
        double relative = rso > 0 ? Math.min(1.0, rs / rso) : 1.0;
        double rnl = SIGMA * (Math.pow(tMax + 273.16, 4) + Math.pow(tMin + 273.16, 4)) / 2
                * (0.34 - 0.14 * Math.sqrt(Math.max(0, ea))) * (1.35 * relative - 0.35);
        double rn = rns - rnl;
        double g = 0;

        double numerator = 0.408 * delta * (rn - g) + gamma * 900 / (tMean + 273) * u2 * (es - ea);
        double denominator = delta + gamma * (1 + 0.34 * u2);
        return Math.max(0, numerator / denominator);
    }

    /**
     * ET0 in mm/day with the daily mean relative humidity (FAO-56 eq. 19: ea from RHmean), the
     * humidity NASA POWER gives (RH2M).
     */
    public static double penmanMonteithRhMean(double tMax, double tMin, double rhMean, double u2, double rs,
                                              double elevationM, double latitude, int dayOfYear) {
        double es = (saturationVapourPressure(tMax) + saturationVapourPressure(tMin)) / 2;
        double ea = Math.max(0, Math.min(100, rhMean)) / 100 * es;
        return penmanMonteith(tMax, tMin, ea, u2, rs, elevationM, latitude, dayOfYear);
    }

    /** Hargreaves-Samani ET0, mm/day (FAO-56 eq. 52), from the temperatures and Ra only. */
    public static double hargreaves(double tMax, double tMin, double latitude, int dayOfYear) {
        double tMean = (tMax + tMin) / 2;
        double ra = extraterrestrialRadiation(latitude, dayOfYear);
        return Math.max(0, 0.0023 * (tMean + 17.8) * Math.sqrt(Math.max(0, tMax - tMin)) * 0.408 * ra);
    }

    /** Saturation vapour pressure at temperature t (C), kPa (eq. 11). */
    static double saturationVapourPressure(double t) {
        return 0.6108 * Math.exp(17.27 * t / (t + 237.3));
    }

    /** Extraterrestrial radiation for daily periods, MJ m-2 day-1 (eq. 21-25). */
    static double extraterrestrialRadiation(double latitude, int dayOfYear) {
        double phi = Math.toRadians(latitude);
        double dr = 1 + 0.033 * Math.cos(2 * Math.PI / 365 * dayOfYear);
        double declination = 0.409 * Math.sin(2 * Math.PI / 365 * dayOfYear - 1.39);
        double ws = Math.acos(Math.max(-1, Math.min(1, -Math.tan(phi) * Math.tan(declination))));
        return 24 * 60 / Math.PI * GSC * dr
                * (ws * Math.sin(phi) * Math.sin(declination) + Math.cos(phi) * Math.cos(declination) * Math.sin(ws));
    }
}
