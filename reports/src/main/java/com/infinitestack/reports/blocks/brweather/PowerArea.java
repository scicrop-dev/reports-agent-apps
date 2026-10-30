package com.infinitestack.reports.blocks.brweather;

import static com.infinitestack.reports.blocks.brweather.BrWeatherTables.INPUT_CTE;
import static com.infinitestack.reports.blocks.brweather.BrWeatherTables.Q_POWER_CELL;
import static com.infinitestack.reports.blocks.brweather.BrWeatherTables.Q_POWER_DAILY;
import static com.infinitestack.reports.blocks.brweather.BrWeatherTables.round;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The NASA POWER cells of an area and their weights, shared by the POWER Blocks: the 0.5 x 0.625
 * degree MERRA-2 cells that touch the area, each weighted by the part of the area inside it (an area
 * on no cell takes the nearest one up to {@link #NEAREST_DEGREES}). Reads the daily table as
 * weighted means per day, a missing value left out of the mean of its day (never counted as zero).
 */
final class PowerArea {

    static final int MAX_CELLS = 60;
    /** Degrees: an area between cells (a point on a border) takes the nearest cell up to this far. */
    static final double NEAREST_DEGREES = 1.0;

    static final String CELLS_SQL = "WITH " + INPUT_CTE + ","
            + " hit AS (SELECT c.lat, c.lon, c.elevation_m,"
            + " ST_Area(ST_Intersection(ST_MakeValid(c.geom), input.geom)::geography) AS weight"
            + " FROM " + Q_POWER_CELL + " c, input WHERE c.valid_to IS NULL AND ST_Intersects(c.geom, input.geom))"
            + " SELECT lat, lon, elevation_m, weight, false AS nearest FROM hit"
            + " UNION ALL SELECT * FROM (SELECT c.lat, c.lon, c.elevation_m, 1.0::double precision, true"
            + " FROM " + Q_POWER_CELL + " c, input WHERE c.valid_to IS NULL AND NOT EXISTS (SELECT 1 FROM hit)"
            + " AND ST_DWithin(c.geom, input.geom, " + NEAREST_DEGREES + ")"
            + " ORDER BY c.geom <-> ST_Centroid(input.geom) LIMIT 1) n"
            + " ORDER BY weight DESC, lat, lon";

    /** Columns of the daily table and the output name of each. */
    static final Map<String, String> PARAMETERS = linked(
            "t2m", "t2m", "t2m_max", "t2mMax", "t2m_min", "t2mMin", "rh2m", "rh2m", "ws2m", "ws2m",
            "allsky_sfc_sw_dwn", "radiation", "prectotcorr", "precipitation");

    /** {@code %s}: the VALUES rows of the cells, then the weighted means. */
    static final String DAILY_SQL = "WITH cells(lat, lon, weight) AS (VALUES %s)"
            + " SELECT d.day%s FROM " + Q_POWER_DAILY + " d JOIN cells c ON d.lat = c.lat AND d.lon = c.lon"
            + " WHERE d.valid_to IS NULL AND d.day BETWEEN ? AND ? GROUP BY d.day ORDER BY d.day";

    static final String LATEST_SQL = "WITH cells(lat, lon, weight) AS (VALUES %s)"
            + " SELECT max(d.day) FROM " + Q_POWER_DAILY + " d JOIN cells c ON d.lat = c.lat AND d.lon = c.lon WHERE d.valid_to IS NULL";

    /** Output form of the cells: {lat, lon, elevationM, weightPercent}. */
    final List<Map<String, Object>> cells;
    final boolean nearest;
    /** lat, lon, weight of each cell, in the order of {@link #values}. */
    final List<Object> cellArgs;
    /** The VALUES rows of the cells, one {@code (?, ?, ?)} per cell. */
    final String values;
    /** Weighted mean latitude of the cell centres (for the radiation of ET0). */
    final double latitude;
    /** Weighted mean elevation of the cells, m; null when no cell has one. */
    final Double elevationM;

    private PowerArea(List<Map<String, Object>> cells, boolean nearest, List<Object> cellArgs, String values,
                      double latitude, Double elevationM) {
        this.cells = cells;
        this.nearest = nearest;
        this.cellArgs = cellArgs;
        this.values = values;
        this.latitude = latitude;
        this.elevationM = elevationM;
    }

    /** The cells of the area, or null when the area is outside the cells BR Weather loaded. */
    static PowerArea find(JdbcTemplate jdbc, String geoJson) {
        List<Map<String, Object>> cellRows = jdbc.queryForList(CELLS_SQL, geoJson);
        if (cellRows.isEmpty()) return null;
        if (cellRows.size() > MAX_CELLS) {
            throw new IllegalArgumentException("the area touches " + cellRows.size() + " NASA POWER cells; at most " + MAX_CELLS);
        }
        double total = cellRows.stream().mapToDouble(r -> ((Number) r.get("weight")).doubleValue()).sum();
        boolean nearest = Boolean.TRUE.equals(cellRows.get(0).get("nearest"));
        List<Map<String, Object>> cells = new ArrayList<>();
        List<Object> cellArgs = new ArrayList<>();
        double latitude = 0;
        double elevation = 0;
        double elevationWeight = 0;
        for (Map<String, Object> row : cellRows) {
            double weight = total > 0 ? ((Number) row.get("weight")).doubleValue() / total : 1.0 / cellRows.size();
            Map<String, Object> cell = new LinkedHashMap<>();
            cell.put("lat", round(row.get("lat"), 3));
            cell.put("lon", round(row.get("lon"), 3));
            cell.put("elevationM", round(row.get("elevation_m"), 1));
            cell.put("weightPercent", round(weight * 100, 2));
            cells.add(cell);
            cellArgs.add(row.get("lat"));
            cellArgs.add(row.get("lon"));
            cellArgs.add(weight);
            latitude += ((Number) row.get("lat")).doubleValue() * weight;
            if (row.get("elevation_m") instanceof Number e) {
                elevation += e.doubleValue() * weight;
                elevationWeight += weight;
            }
        }
        String values = String.join(", ", Collections.nCopies(cellRows.size(), "(?::numeric, ?::numeric, ?::double precision)"));
        return new PowerArea(cells, nearest, cellArgs, values, latitude, elevationWeight > 0 ? elevation / elevationWeight : null);
    }

    /** Latest day loaded for the cells, or null. */
    LocalDate latest(JdbcTemplate jdbc) {
        Object value = jdbc.queryForObject(String.format(LATEST_SQL, values), Object.class, cellArgs.toArray());
        return value == null ? null : LocalDate.parse(value.toString());
    }

    /**
     * One map per day with data, oldest first: {date, t2m, t2mMax, t2mMin, rh2m, ws2m, radiation,
     * precipitation}, each the weighted mean rounded to 2 decimals (null when no cell has it).
     */
    List<Map<String, Object>> daily(JdbcTemplate jdbc, LocalDate start, LocalDate end) {
        StringBuilder means = new StringBuilder();
        for (String column : PARAMETERS.keySet()) {
            means.append(", sum(d.").append(column).append(" * c.weight) / nullif(sum(CASE WHEN d.").append(column)
                    .append(" IS NOT NULL THEN c.weight END), 0) AS ").append(column);
        }
        List<Object> args = new ArrayList<>(cellArgs);
        args.add(java.sql.Date.valueOf(start));
        args.add(java.sql.Date.valueOf(end));
        List<Map<String, Object>> series = new ArrayList<>();
        for (Map<String, Object> row : jdbc.queryForList(String.format(DAILY_SQL, values, means), args.toArray())) {
            Map<String, Object> day = new LinkedHashMap<>();
            day.put("date", row.get("day").toString());
            PARAMETERS.forEach((column, name) -> day.put(name, round(row.get(column), 2)));
            series.add(day);
        }
        return series;
    }

    /**
     * FAO-56 Penman-Monteith ET0 of a day of {@link #daily} (mm/day, 2 decimals), from the area's
     * means: maximum and minimum temperature, mean relative humidity, wind at 2 m and solar radiation,
     * with the weighted latitude and elevation of the cells (sea level when no cell has an
     * elevation). Null when one of those is missing (often the radiation of the last days).
     */
    Double penmanMonteith(Map<String, Object> day) {
        if (!(day.get("t2mMax") instanceof Double tMax) || !(day.get("t2mMin") instanceof Double tMin)
                || !(day.get("rh2m") instanceof Double rh) || !(day.get("ws2m") instanceof Double u2)
                || !(day.get("radiation") instanceof Double rs)) {
            return null;
        }
        LocalDate date = LocalDate.parse((String) day.get("date"));
        return round(Et0.penmanMonteithRhMean(tMax, tMin, rh, u2, rs, elevationM == null ? 0 : elevationM, latitude,
                date.getDayOfYear()), 2);
    }

    /** Hargreaves ET0 of a day of {@link #daily} (mm/day, 2 decimals); null without both temperatures. */
    Double hargreaves(Map<String, Object> day) {
        if (!(day.get("t2mMax") instanceof Double tMax) || !(day.get("t2mMin") instanceof Double tMin)) return null;
        return round(Et0.hargreaves(tMax, tMin, latitude, LocalDate.parse((String) day.get("date")).getDayOfYear()), 2);
    }

    private static Map<String, String> linked(String... pairs) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) out.put(pairs[i], pairs[i + 1]);
        return out;
    }
}
