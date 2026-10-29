package com.infinitestack.reports.blocks.gis;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.infinitestack.reports.engine.block.BlockQueries;

/**
 * The GIS datasets of the Data Library (infinitestack-back#1248): {@code bronze.is_geo_ref} (one row per
 * uploaded or drawn dataset) and {@code bronze.is_geo_ref_data} (its features, EPSG:4326, any geometry
 * type). Lists them for the run form and gives the area of a dataset or of one of its features to
 * {@code report-area}.
 *
 * <p>The tables exist only on instances where someone used the GIS tab; without them the lists are empty
 * and the area fails with a message saying so. They are read through the read-only Block pool.
 */
@Component
public class GisAreas {

    public static final String SCHEMA = "bronze";
    public static final String REF = "is_geo_ref";
    public static final String DATA = "is_geo_ref_data";
    static final String Q_REF = SCHEMA + "." + REF;
    static final String Q_DATA = SCHEMA + "." + DATA;

    /** A dataset with more polygon features than this must be narrowed to one feature. */
    public static final int MAX_FEATURES = 5000;
    public static final int MAX_LISTED_FEATURES = 1000;
    static final Duration TIMEOUT = Duration.ofSeconds(30);

    /** Feature attributes tried, in order, to name a feature in the list. */
    static final List<String> LABEL_KEYS = List.of("name", "nome", "label", "title", "titulo", "fazenda", "farm",
            "talhao", "field", "parcel", "cod_imovel", "codigo", "code", "id");

    static final String TABLES_SQL = "SELECT to_regclass('" + Q_REF + "') IS NOT NULL AND to_regclass('" + Q_DATA + "') IS NOT NULL";

    static final String DATASETS_SQL = "SELECT r.geo_id, r.geo_name, r.geo_attributes::text AS attributes, r.geo_ts,"
            + " (SELECT count(*) FROM " + Q_DATA + " d WHERE d.geo_id = r.geo_id"
            + " AND GeometryType(d.geo_data) IN ('POLYGON', 'MULTIPOLYGON')) AS polygons"
            + " FROM " + Q_REF + " r ORDER BY lower(r.geo_name)";

    static final String DATASET_SQL = "SELECT geo_id, geo_name FROM " + Q_REF + " WHERE geo_id = ? OR lower(geo_name) = lower(?)"
            + " ORDER BY (geo_id = ?) DESC LIMIT 1";

    static final String FEATURES_SQL = "SELECT geo_data_id, GeometryType(geo_data) AS geometry_type,"
            + " geo_data_attributes::text AS attributes,"
            + " CASE WHEN GeometryType(geo_data) IN ('POLYGON', 'MULTIPOLYGON') THEN ST_Area(geo_data::geography) / 1e4 END AS area_ha"
            + " FROM " + Q_DATA + " WHERE geo_id = ? ORDER BY geo_data_id LIMIT ?";

    static final String POLYGON_COUNT_SQL = "SELECT count(*) FROM " + Q_DATA + " WHERE geo_id = ?"
            + " AND (?::int IS NULL OR geo_data_id = ?) AND GeometryType(geo_data) IN ('POLYGON', 'MULTIPOLYGON')";

    static final String FEATURE_SQL = "SELECT geo_data_attributes::text AS attributes FROM " + Q_DATA + " WHERE geo_id = ? AND geo_data_id = ?";

    /**
     * The polygons of a dataset (or of one feature) as one GeoJSON geometry. Stored in EPSG:4326 and
     * handed to report-area as lon/lat, which reads it as SIRGAS 2000 like any polygon (under a metre apart).
     */
    static final String GEOMETRY_SQL = "SELECT ST_AsGeoJSON(ST_Collect(geo_data), 9) FROM " + Q_DATA + " WHERE geo_id = ?"
            + " AND (?::int IS NULL OR geo_data_id = ?) AND GeometryType(geo_data) IN ('POLYGON', 'MULTIPOLYGON')";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final BlockQueries queries;

    public GisAreas(BlockQueries queries) {
        this.queries = queries;
    }

    /** The datasets, by name; {@code available} false when the GIS tables do not exist. */
    public Map<String, Object> datasets() {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!tablesExist()) {
            out.put("available", false);
            out.put("datasets", List.of());
            return out;
        }
        List<Map<String, Object>> list = queries.read(TIMEOUT, jdbc -> jdbc.query(DATASETS_SQL, (rs, n) -> {
            JsonNode a = attributes(rs.getString("attributes"));
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("id", rs.getInt("geo_id"));
            d.put("name", rs.getString("geo_name"));
            d.put("category", text(a.path("category").path("title")));
            d.put("subcategory", text(a.path("subcategory").path("title")));
            d.put("description", text(a.path("description")));
            d.put("featureCount", a.path("feature_count").isNumber() ? a.path("feature_count").asInt() : null);
            d.put("polygonCount", rs.getLong("polygons"));
            d.put("areaKm2", a.path("area_km2").isNumber() ? a.path("area_km2").asDouble() : null);
            d.put("createdAt", rs.getTimestamp("geo_ts") == null ? null : rs.getTimestamp("geo_ts").toLocalDateTime().toString());
            return d;
        }));
        out.put("available", true);
        out.put("datasets", list);
        return out;
    }

    /** The features of a dataset, with a label taken from their attributes, for the feature picker. */
    public Map<String, Object> features(String dataset) {
        Map<String, Object> ref = dataset(dataset);
        int geoId = (Integer) ref.get("id");
        List<Map<String, Object>> list = queries.read(TIMEOUT, jdbc -> jdbc.query(FEATURES_SQL, (rs, n) -> {
            Map<String, Object> f = new LinkedHashMap<>();
            int id = rs.getInt("geo_data_id");
            f.put("id", id);
            f.put("label", featureLabel(attributes(rs.getString("attributes")), id));
            f.put("geometryType", rs.getString("geometry_type"));
            f.put("polygon", rs.getObject("area_ha") != null);
            f.put("areaHa", rs.getObject("area_ha") == null ? null : Math.round(rs.getDouble("area_ha") * 100) / 100d);
            return f;
        }, geoId, MAX_LISTED_FEATURES + 1));
        boolean truncated = list.size() > MAX_LISTED_FEATURES;
        Map<String, Object> out = new LinkedHashMap<>(ref);
        out.put("features", truncated ? list.subList(0, MAX_LISTED_FEATURES) : list);
        out.put("truncated", truncated);
        return out;
    }

    /**
     * The polygons of a dataset or of one feature, as GeoJSON for report-area, with the dataset and the
     * feature label. Fails when the GIS tables do not exist, the dataset or feature is unknown, it has no
     * polygon, or it has more than {@link #MAX_FEATURES} polygons without a feature picked.
     */
    public Map<String, Object> area(String dataset, Integer featureId) {
        if (!tablesExist()) {
            throw new IllegalArgumentException("this instance has no GIS data (Data Library > GIS); upload or draw the area there first");
        }
        Map<String, Object> ref = dataset(dataset);
        int geoId = (Integer) ref.get("id");
        String name = (String) ref.get("name");
        return queries.read(TIMEOUT, jdbc -> {
            String featureLabel = null;
            if (featureId != null) {
                List<String> attrs = jdbc.queryForList(FEATURE_SQL, String.class, geoId, featureId);
                if (attrs.isEmpty()) throw new IllegalArgumentException("GIS dataset '" + name + "' has no feature " + featureId);
                featureLabel = featureLabel(attributes(attrs.get(0)), featureId);
            }
            Long polygons = jdbc.queryForObject(POLYGON_COUNT_SQL, Long.class, geoId, featureId, featureId);
            if (polygons == null || polygons == 0) {
                throw new IllegalArgumentException(featureId == null
                        ? "GIS dataset '" + name + "' has no polygon (only points or lines); pick a dataset with areas"
                        : "feature " + featureId + " of GIS dataset '" + name + "' is not a polygon");
            }
            if (polygons > MAX_FEATURES) {
                throw new IllegalArgumentException("GIS dataset '" + name + "' has " + polygons + " polygons, more than "
                        + MAX_FEATURES + "; pick one feature (gisFeature)");
            }
            String geoJson = jdbc.queryForObject(GEOMETRY_SQL, String.class, geoId, featureId, featureId);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("geoJson", geoJson);
            out.put("datasetId", geoId);
            out.put("datasetName", name);
            out.put("featureId", featureId);
            out.put("featureLabel", featureLabel);
            out.put("polygons", polygons);
            return out;
        });
    }

    private Map<String, Object> dataset(String dataset) {
        if (dataset == null || dataset.isBlank()) throw new IllegalArgumentException("no GIS dataset given");
        String key = dataset.trim();
        int id = key.matches("\\d{1,9}") ? Integer.parseInt(key) : -1;
        List<Map<String, Object>> rows;
        try {
            rows = queries.read(TIMEOUT, jdbc -> jdbc.query(DATASET_SQL, (rs, n) -> {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("id", rs.getInt("geo_id"));
                r.put("name", rs.getString("geo_name"));
                return r;
            }, id, key, id));
        } catch (DataAccessException e) {
            throw new IllegalArgumentException("cannot read the GIS datasets: " + e.getMostSpecificCause().getMessage(), e);
        }
        if (rows.isEmpty()) throw new IllegalArgumentException("GIS dataset '" + key + "' not found in the Data Library");
        return rows.get(0);
    }

    private boolean tablesExist() {
        return Boolean.TRUE.equals(queries.read(TIMEOUT, jdbc -> jdbc.queryForObject(TABLES_SQL, Boolean.class)));
    }

    /** The first non-blank attribute among {@link #LABEL_KEYS} (any case), else "Feature <id>". */
    static String featureLabel(JsonNode attributes, int id) {
        if (attributes != null && attributes.isObject()) {
            Map<String, String> byKey = new LinkedHashMap<>();
            attributes.fields().forEachRemaining(e -> {
                if (e.getValue().isValueNode() && !e.getValue().isNull() && !e.getValue().asText().isBlank()) {
                    byKey.putIfAbsent(e.getKey().toLowerCase(Locale.ROOT), e.getValue().asText().trim());
                }
            });
            for (String key : LABEL_KEYS) {
                String value = byKey.get(key);
                if (value != null) return value;
            }
        }
        return "Feature " + id;
    }

    private static JsonNode attributes(String json) {
        if (json == null) return JSON.createObjectNode();
        try {
            return JSON.readTree(json);
        } catch (Exception e) {
            return JSON.createObjectNode();
        }
    }

    private static String text(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() || node.asText().isBlank() ? null : node.asText();
    }
}
