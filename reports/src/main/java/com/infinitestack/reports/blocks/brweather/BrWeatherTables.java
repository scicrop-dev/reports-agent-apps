package com.infinitestack.reports.blocks.brweather;

import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.infinitestack.reports.engine.block.DataRequirement;

/**
 * The BR Weather tables (published by {@code apps/br-weather-agent-app} in {@code gold}) read by the
 * weather Blocks. Versioned like the BR Geo ones: the current row of a key has {@code valid_to IS NULL}.
 * Geometries are SIRGAS 2000 (4674).
 */
public final class BrWeatherTables {

    private BrWeatherTables() {}

    public static final String SCHEMA = "gold";

    public static final String POWER_CELL = "br_weather_agent_app_nasa_power_cell";
    public static final String POWER_DAILY = "br_weather_agent_app_nasa_power_daily";
    public static final String CHIRPS_DAY = "br_weather_agent_app_chirps_day";

    public static final String Q_POWER_CELL = SCHEMA + "." + POWER_CELL;
    public static final String Q_POWER_DAILY = SCHEMA + "." + POWER_DAILY;
    public static final String Q_CHIRPS_DAY = SCHEMA + "." + CHIRPS_DAY;

    /** The input area as a SQL CTE named {@code input} ({@code geom}, valid, SRID 4674); one parameter, the GeoJSON text. */
    static final String INPUT_CTE = "input AS (SELECT ST_CollectionExtract(ST_MakeValid(ST_SetSRID(ST_GeomFromGeoJSON(?), 4674)), 3) AS geom)";

    private static final ObjectMapper JSON = new ObjectMapper();

    public static DataRequirement requirement(String table, String... columns) {
        return DataRequirement.table(SCHEMA, table, columns);
    }

    static String geoJson(Object geometry) {
        if (!(geometry instanceof Map<?, ?>)) throw new IllegalArgumentException("geometry is not a GeoJSON object");
        try {
            return JSON.writeValueAsString(geometry);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("geometry is not valid GeoJSON", e);
        }
    }

    static Double round(Object value, int scale) {
        if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue())) return null;
        double factor = Math.pow(10, scale);
        return Math.round(n.doubleValue() * factor) / factor;
    }
}
