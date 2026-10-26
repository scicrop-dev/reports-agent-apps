package com.infinitestack.reports.blocks.brgeo;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.infinitestack.reports.engine.DataType;
import com.infinitestack.reports.engine.block.BlockPort;

/**
 * Conventions shared by the BR Geo Blocks. Most of them receive an area as a GeoJSON geometry
 * (typically {@code car-property-geometry.geometry}) and measure, on the geography, how much of
 * each dataset falls inside it: hectares with 4 decimals, percentages with 2, dates as ISO strings.
 */
final class BrGeoValues {

    private BrGeoValues() {}

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Statement timeout: spatial intersections over a big area can take a few seconds. */
    static final Duration QUERY_TIMEOUT = Duration.ofSeconds(25);
    static final Duration BLOCK_TIMEOUT = Duration.ofSeconds(30);

    static final BlockPort GEOMETRY_INPUT = BlockPort.required("geometry", DataType.GEOMETRY,
            "area to analyse, GeoJSON in SIRGAS 2000 (SRID 4674); connect car-property-geometry.geometry");

    /**
     * The input area as a SQL CTE named {@code input} with {@code geom} (valid, SRID 4674) and
     * {@code area_ha}. Takes one parameter: the GeoJSON text from {@link #geoJson}.
     */
    static final String INPUT_CTE = "input AS (SELECT g.geom, ST_Area(g.geom::geography) / 1e4 AS area_ha"
            + " FROM (SELECT ST_CollectionExtract(ST_MakeValid(ST_SetSRID(ST_GeomFromGeoJSON(?), 4674)), 3) AS geom) g)";

    /** The geometry input as GeoJSON text, for {@code ST_GeomFromGeoJSON}. */
    static String geoJson(Map<String, Object> inputs) {
        Object geometry = inputs.get("geometry");
        if (!(geometry instanceof Map<?, ?>)) throw new IllegalArgumentException("geometry is not a GeoJSON object");
        try {
            return JSON.writeValueAsString(geometry);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("geometry is not valid GeoJSON", e);
        }
    }

    static Double hectares(Number value) {
        return value == null ? null : round(value.doubleValue(), 4);
    }

    /** {@code part} as a percentage of {@code whole}, 2 decimals; 0 when the whole is unknown or zero. */
    static double percent(double part, Double whole) {
        if (whole == null || whole <= 0) return 0.0;
        return round(part * 100.0 / whole, 2);
    }

    static double round(double value, int scale) {
        return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP).doubleValue();
    }

    /** A date (or timestamp) column as an ISO date string, or null. */
    static String isoDate(ResultSet rs, String column) throws SQLException {
        LocalDate date = rs.getObject(column, LocalDate.class);
        return date == null ? null : date.toString();
    }
}
