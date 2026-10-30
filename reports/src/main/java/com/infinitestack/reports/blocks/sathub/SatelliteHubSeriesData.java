package com.infinitestack.reports.blocks.sathub;

import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * What the Satellite Hub series Blocks ({@code sathub-index-series}, {@code sathub-index-compare},
 * {@code sathub-index-anomaly}) read the same way: the input area (a geometry or one area by id), the
 * Satellite Hub areas inside it, the index definitions and the per-date series of an index combined
 * over those areas (mean weighted by valid pixels). One place, so the three Blocks agree on what a
 * point of the series is.
 */
final class SatelliteHubSeriesData {

    static final String SCHEMA = "gold";
    static final String AREAS = "satellite_hub_areas";
    static final String INDICES = "satellite_hub_analytics_indices";
    static final String VALUES = "satellite_hub_analytics_values";

    /** The collection of an index saved before Satellite Hub had several (its own default). */
    static final String LEGACY_COLLECTION = "sentinel-2-l2a";

    /** Satellite Hub collection id to the name shown in the report. */
    static final Map<String, String> COLLECTION_NAMES = Map.of(
            "sentinel-2-l2a", "Sentinel-2 L2A",
            "landsat-c2-l2", "Landsat 8/9 Collection 2 Level-2",
            "sentinel-1-rtc", "Sentinel-1 RTC (radar)");

    static final int MAX_AREAS = 50;
    static final int DEFAULT_DAYS = 365;
    static final double DEFAULT_MIN_VALID_PERCENT = 50;

    /** 'days' that reaches the start of any Satellite Hub history (Sentinel-2 began in 2015). */
    static final int WHOLE_HISTORY_DAYS = 36500;

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Index definitions by slug, enabled or not: a disabled index can still have the history it had. */
    static final String INDEX_COLUMNS = "id, slug, name, description, COALESCE(collection, '" + LEGACY_COLLECTION + "') AS collection,"
            + " enabled";

    /** The value range of an index (RVI is 0 to 4, the optical ones -1 to 1), for the Blocks that compare scales. */
    static final String SCALE_COLUMNS = ", clamp_min, clamp_max";

    static final String AREAS_BY_GEOMETRY_SQL = "WITH input AS ("
            + " SELECT ST_Transform(ST_CollectionExtract(ST_MakeValid(ST_SetSRID(ST_GeomFromGeoJSON(?), 4674)), 3), 4326) AS geom)"
            + " SELECT a.id, a.farm_name, a.block_name, a.parcel_name, a.area_ha,"
            + " ST_Area(ST_Intersection(ST_MakeValid(a.geom), input.geom)::geography) / 1e4 AS inside_ha"
            + " FROM input JOIN " + SCHEMA + "." + AREAS + " a ON a.geom && input.geom AND ST_Intersects(a.geom, input.geom)"
            + " ORDER BY inside_ha DESC, a.id LIMIT " + (MAX_AREAS + 1);

    static final String AREA_BY_ID_SQL = "SELECT a.id, a.farm_name, a.block_name, a.parcel_name, a.area_ha,"
            + " a.area_ha AS inside_ha FROM " + SCHEMA + "." + AREAS + " a WHERE a.id = ?";

    /** {@code %s} is the list of area id placeholders. */
    static final String SERIES_SQL = "WITH v AS ("
            + " SELECT v.* FROM " + SCHEMA + "." + VALUES + " v WHERE v.index_id = ? AND v.area_id IN (%s)"
            + " AND v.valid_pixels > 0 AND v.valid_area_pct >= ?),"
            + " last AS (SELECT max(acquired_at) AS at FROM v)"
            + " SELECT v.acquired_at::date AS day,"
            + " sum(v.mean * v.valid_pixels) / sum(v.valid_pixels) AS mean,"
            + " min(v.min_value) AS min_value, max(v.max_value) AS max_value,"
            + " sum(v.p10 * v.valid_pixels) / sum(v.valid_pixels) AS p10,"
            + " sum(v.p90 * v.valid_pixels) / sum(v.valid_pixels) AS p90,"
            + " avg(v.valid_area_pct) AS valid_pct, count(DISTINCT v.area_id) AS areas"
            + " FROM v, last"
            // A 'to' cuts the series at that day; a 'from' starts it there, else it goes 'days' back
            // from 'to' (or from the latest image when there is no 'to').
            + " WHERE (CAST(? AS date) IS NULL OR v.acquired_at < CAST(? AS date) + 1)"
            + " AND CASE WHEN CAST(? AS date) IS NOT NULL THEN v.acquired_at >= CAST(? AS date)"
            + " WHEN CAST(? AS date) IS NOT NULL THEN v.acquired_at >= CAST(? AS date) + 1 - make_interval(days => ?)"
            + " ELSE v.acquired_at > last.at - make_interval(days => ?) END"
            + " GROUP BY 1 ORDER BY 1";

    private SatelliteHubSeriesData() {}

    /** The inputs every series Block shares: where, when and how clear an image must be. */
    record Window(Object geometry, Long areaId, LocalDate from, LocalDate to, int days, double minValidPercent) {

        static Window of(Map<String, Object> inputs) {
            Object geometry = inputs.get("geometry");
            Long areaId = inputs.get("areaId") instanceof Number n ? n.longValue() : null;
            if (geometry == null && areaId == null) throw new IllegalArgumentException("give a geometry or an areaId");
            int days = inputs.get("days") instanceof Number n ? Math.max(1, n.intValue()) : DEFAULT_DAYS;
            LocalDate from = inputs.get("from") instanceof String f && !f.isBlank() ? LocalDate.parse(f.trim()) : null;
            LocalDate to = inputs.get("to") instanceof String t && !t.isBlank() ? LocalDate.parse(t.trim()) : null;
            if (from != null && to != null && from.isAfter(to)) throw new IllegalArgumentException("'from' is after 'to'");
            double minValid = inputs.get("minValidPercent") instanceof Number n ? n.doubleValue() : DEFAULT_MIN_VALID_PERCENT;
            return new Window(geometry, areaId, from, to, days, minValid);
        }

        Window withTo(LocalDate newTo) {
            return new Window(geometry, areaId, from, newTo, days, minValidPercent);
        }

        /** The same area and image filter over the whole history up to 'to'. */
        Window wholeHistory() {
            return new Window(geometry, areaId, null, to, WHOLE_HISTORY_DAYS, minValidPercent);
        }
    }

    /** The Satellite Hub areas used, largest share first, and whether there were more than {@link #MAX_AREAS}. */
    record Areas(List<Map<String, Object>> items, boolean truncated) {

        List<Long> ids() {
            return items.stream().map(a -> (Long) a.get("id")).toList();
        }
    }

    /** One date of a combined series. */
    record Point(LocalDate date, Double mean, Double min, Double max, Double p10, Double p90, Double validAreaPercent, int areas) {}

    static Areas areas(JdbcTemplate jdbc, Window window) {
        List<Map<String, Object>> rows = window.geometry() != null
                ? jdbc.queryForList(AREAS_BY_GEOMETRY_SQL, geoJson(window.geometry()))
                : jdbc.queryForList(AREA_BY_ID_SQL, window.areaId());
        if (window.geometry() == null && rows.isEmpty()) {
            throw new IllegalArgumentException("Satellite Hub area " + window.areaId() + " not found");
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : rows.subList(0, Math.min(MAX_AREAS, rows.size()))) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", ((Number) row.get("id")).longValue());
            item.put("farmName", row.get("farm_name"));
            item.put("blockName", row.get("block_name"));
            item.put("parcelName", row.get("parcel_name"));
            item.put("areaHa", round(row.get("area_ha"), 4));
            item.put("insideHa", round(row.get("inside_ha"), 4));
            items.add(item);
        }
        return new Areas(items, rows.size() > MAX_AREAS);
    }

    /**
     * Index definitions of the given slugs, in no particular order (absent slugs are simply missing).
     * {@code withScale} also reads clamp_min and clamp_max.
     */
    static List<Map<String, Object>> indices(JdbcTemplate jdbc, List<String> slugs, boolean withScale) {
        if (slugs.isEmpty()) return List.of();
        String placeholders = String.join(", ", Collections.nCopies(slugs.size(), "?"));
        return jdbc.queryForList("SELECT " + INDEX_COLUMNS + (withScale ? SCALE_COLUMNS : "") + " FROM " + SCHEMA + "." + INDICES + " WHERE slug IN (" + placeholders + ")",
                slugs.toArray());
    }

    /** The index series combined over the areas, oldest first; empty without areas. */
    static List<Point> series(JdbcTemplate jdbc, Object indexId, List<Long> areaIds, Window window) {
        if (areaIds.isEmpty()) return List.of();
        List<Object> args = new ArrayList<>();
        args.add(indexId);
        args.addAll(areaIds);
        args.add(window.minValidPercent());
        Date toSql = window.to() == null ? null : Date.valueOf(window.to());
        Date fromSql = window.from() == null ? null : Date.valueOf(window.from());
        args.addAll(Arrays.asList(toSql, toSql, fromSql, fromSql, toSql, toSql, window.days(), window.days()));
        String placeholders = String.join(", ", Collections.nCopies(areaIds.size(), "?"));
        List<Point> out = new ArrayList<>();
        for (Map<String, Object> row : jdbc.queryForList(String.format(SERIES_SQL, placeholders), args.toArray())) {
            if (row.get("mean") == null) continue; // a value row without a mean says nothing about the date
            out.add(new Point(LocalDate.parse(row.get("day").toString()), round(row.get("mean"), 4), round(row.get("min_value"), 4),
                    round(row.get("max_value"), 4), round(row.get("p10"), 4), round(row.get("p90"), 4),
                    round(row.get("valid_pct"), 1), ((Number) row.get("areas")).intValue()));
        }
        return out;
    }

    /** Day of the latest image of any of the indices over the areas (images under minValidPercent ignored); null without any. */
    static LocalDate latestDate(JdbcTemplate jdbc, List<Object> indexIds, List<Long> areaIds, double minValidPercent) {
        if (indexIds.isEmpty() || areaIds.isEmpty()) return null;
        List<Object> args = new ArrayList<>(indexIds);
        args.addAll(areaIds);
        args.add(minValidPercent);
        String sql = "SELECT max(v.acquired_at)::date FROM " + SCHEMA + "." + VALUES + " v WHERE v.index_id IN ("
                + String.join(", ", Collections.nCopies(indexIds.size(), "?")) + ") AND v.area_id IN ("
                + String.join(", ", Collections.nCopies(areaIds.size(), "?")) + ") AND v.valid_pixels > 0 AND v.valid_area_pct >= ?"
                + " AND v.mean IS NOT NULL";
        Date day = jdbc.queryForObject(sql, Date.class, args.toArray());
        return day == null ? null : day.toLocalDate();
    }

    /** {slug, name, description, collection, collectionName, enabled} of an index row. */
    static Map<String, Object> indexInfo(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("slug", row.get("slug"));
        out.put("name", row.get("name"));
        out.put("description", row.get("description"));
        String collection = String.valueOf(row.get("collection"));
        out.put("collection", collection);
        out.put("collectionName", COLLECTION_NAMES.getOrDefault(collection, collection));
        out.put("enabled", !Boolean.FALSE.equals(row.get("enabled")));
        return out;
    }

    static String geoJson(Object geometry) {
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
