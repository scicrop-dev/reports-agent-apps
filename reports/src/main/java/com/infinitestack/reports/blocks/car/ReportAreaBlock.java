package com.infinitestack.reports.blocks.car;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.infinitestack.reports.blocks.gis.GisAreas;
import com.infinitestack.reports.engine.DataType;
import com.infinitestack.reports.engine.block.BlockDefinition;
import com.infinitestack.reports.engine.block.BlockDocs;
import com.infinitestack.reports.engine.block.BlockPort;
import com.infinitestack.reports.engine.block.BlockQueries;
import com.infinitestack.reports.engine.block.ExecutionMetadata;
import com.infinitestack.reports.engine.block.ReportBlock;

/**
 * {@code report-area}: the area a report is about, from a CAR property code, from a polygon the
 * user sends or from a GIS dataset of the Data Library (or one of its features), so one ready-made
 * report serves all three. Gives the same outputs as
 * {@code car-property-geometry} plus which of the two it was, for the template to show the CAR
 * sections only for a property. A polygon is cleaned (made valid, polygons only, parts merged).
 *
 * <p>No data requirement: with a polygon it only needs PostGIS. A CAR code on an instance without
 * the CAR data fails with a message saying so.
 */
@Component
public class ReportAreaBlock implements ReportBlock {

    private static final ObjectMapper JSON = new ObjectMapper();

    static final String POLYGON_SQL = "WITH g AS (SELECT ST_UnaryUnion(ST_CollectionExtract(ST_MakeValid("
            + "ST_SetSRID(ST_GeomFromGeoJSON(?), 4674)), 3)) AS geom)"
            + " SELECT ST_IsEmpty(geom) AS empty, ST_AsGeoJSON(geom, 6) AS geojson,"
            + " (ST_Area(geom::geography) / 1e4)::numeric AS area_ha,"
            + " ST_Y(ST_PointOnSurface(geom)) AS lat, ST_X(ST_PointOnSurface(geom)) AS lon,"
            + " ST_XMin(geom) AS min_lon, ST_YMin(geom) AS min_lat, ST_XMax(geom) AS max_lon, ST_YMax(geom) AS max_lat,"
            + " ST_NPoints(geom) AS vertices FROM g";

    private final BlockQueries queries;
    private final GisAreas gis;

    public ReportAreaBlock(BlockQueries queries, GisAreas gis) {
        this.queries = queries;
        this.gis = gis;
    }

    @Override
    public BlockDefinition definition() {
        return new BlockDefinition("report-area", "Report area (CAR, polygon or GIS)",
                "The area of the report, from a CAR property code, a GeoJSON polygon or a GIS dataset of the Data Library:"
                        + " geometry, area, inner point and bounding box",
                "1.1.0",
                List.of(
                        BlockPort.optional("codImovel", DataType.STRING, "SICAR property code; give this or 'geometry'"),
                        BlockPort.optional("geometry", DataType.GEOMETRY,
                                "GeoJSON Polygon or MultiPolygon in lon/lat (SIRGAS 2000 or WGS 84); a Feature or FeatureCollection also works"),
                        BlockPort.optional("name", DataType.STRING, "name to show for a polygon or GIS area (e.g. Fazenda Boa Vista)"),
                        BlockPort.optional("gisDataset", DataType.STRING,
                                "GIS dataset of the Data Library (id or name); its polygons are the area. Give this, codImovel or geometry"),
                        BlockPort.optional("gisFeature", DataType.NUMBER, "only this feature (geo_data_id) of the GIS dataset")),
                List.of(
                        BlockPort.required("source", DataType.STRING, "car, polygon or gis"),
                        BlockPort.required("isCar", DataType.BOOLEAN, "the area is a CAR property"),
                        BlockPort.required("isGis", DataType.BOOLEAN, "the area comes from a GIS dataset of the Data Library"),
                        BlockPort.optional("codImovel", DataType.STRING,
                                "the CAR code, null for a polygon; connect it to the CAR Blocks marked optional in the Flow"),
                        BlockPort.required("label", DataType.STRING,
                                "how to call the area: the CAR code, the name given, the GIS feature or dataset name, or 'Área informada'"),
                        BlockPort.optional("gisDatasetId", DataType.NUMBER, "GIS dataset id (geo_id), null unless source is gis"),
                        BlockPort.optional("gisDatasetName", DataType.STRING, "GIS dataset name, null unless source is gis"),
                        BlockPort.optional("gisFeatureId", DataType.NUMBER, "GIS feature id when one was picked"),
                        BlockPort.optional("gisPolygons", DataType.NUMBER, "how many GIS polygons were merged into the area"),
                        BlockPort.required("geometry", DataType.GEOMETRY, "GeoJSON geometry, SRID 4674"),
                        BlockPort.required("areaHa", DataType.NUMBER, "computed area, hectares"),
                        BlockPort.required("centroidLat", DataType.NUMBER, "latitude of a point inside the area"),
                        BlockPort.required("centroidLon", DataType.NUMBER, "longitude of a point inside the area"),
                        BlockPort.required("bbox", DataType.OBJECT, "{minLon, minLat, maxLon, maxLat}"),
                        BlockPort.required("vertices", DataType.NUMBER, "number of vertices")),
                List.of(),
                null);
    }

    @Override
    public BlockDocs docs() {
        return new BlockDocs("Área",
                "Starts a report that works for a CAR property, for any polygon and for a GIS dataset of the Data Library. "
                        + "It turns any of them into the geometry every spatial Block takes, and says which one it was.",
                List.of(
                        "With codImovel: reads the current geometry of the property, as car-property-geometry does.",
                        "With geometry: makes it valid, keeps only its polygons and merges overlapping parts; a Feature "
                                + "or a FeatureCollection is reduced to its geometries.",
                        "With gisDataset: reads the polygons of that dataset (bronze.is_geo_ref_data), or of the one feature "
                                + "given in gisFeature, and treats them as the polygon; points and lines are ignored.",
                        "Computes area (geodesic), an inner point (ST_PointOnSurface), bounding box and vertex count.",
                        "Fails when more than one or none of codImovel, geometry and gisDataset are given, when the polygon has "
                                + "no area or when its coordinates are not degrees."),
                List.of(
                        "codImovel, geometry and gisDataset (+ gisFeature): optional report inputs; the person running the report fills one. "
                                + "Give the gisDataset input the picker gis-dataset and gisFeature the picker gis-feature, so the run form "
                                + "lists the datasets and features of the Data Library.",
                        "geometry: connect it to every Block that analyses an area (BR Geo, BR Weather, Satellite Hub).",
                        "codImovel: connect it to the CAR Blocks (car-property, car-property-map...) and mark those nodes "
                                + "optional, so they are skipped for a polygon.",
                        "isCar: wrap the CAR sections of the template in {{#<node>.isCar}}...{{/<node>.isCar}}."),
                List.of(
                        "Coordinates must be longitude/latitude in degrees; SIRGAS 2000 and WGS 84 differ by less than a metre.",
                        "A CAR code on an instance without the car-agent-app data fails.",
                        "A GIS dataset with more than " + GisAreas.MAX_FEATURES + " polygons needs one feature picked.",
                        "GIS data is stored in WGS 84 (EPSG:4326) and read as SIRGAS 2000, like a polygon given by the user."),
                "SICAR (car-agent-app) for a CAR code; the polygon given by the user; the GIS tab of the Data Library (bronze.is_geo_ref*)",
                "{{<node>.label}}: {{<node>.areaHa}} ha");
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> inputs, ExecutionMetadata metadata) {
        String codImovel = inputs.get("codImovel") instanceof String s && !s.isBlank() ? s.trim() : null;
        Object geometry = inputs.get("geometry");
        String gisDataset = inputs.get("gisDataset") == null || inputs.get("gisDataset").toString().isBlank()
                ? null : inputs.get("gisDataset").toString().trim();
        Integer gisFeature = inputs.get("gisFeature") instanceof Number n ? n.intValue() : null;
        int given = (codImovel != null ? 1 : 0) + (geometry != null ? 1 : 0) + (gisDataset != null ? 1 : 0);
        if (given > 1) {
            throw new IllegalArgumentException("give only one of: the CAR code, the polygon or the GIS dataset");
        }
        if (given == 0) {
            throw new IllegalArgumentException(gisFeature != null
                    ? "gisFeature needs the GIS dataset (gisDataset)"
                    : "give a CAR code (codImovel), a polygon (geometry) or a GIS dataset (gisDataset)");
        }
        if (gisFeature != null && gisDataset == null) throw new IllegalArgumentException("gisFeature needs the GIS dataset (gisDataset)");
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> area;
        if (codImovel != null) {
            area = car(codImovel);
            out.put("source", "car");
            out.put("isCar", true);
            out.put("isGis", false);
            out.put("codImovel", codImovel);
            out.put("label", codImovel);
            putGis(out, null);
        } else if (gisDataset != null) {
            Map<String, Object> gisArea = gis.area(gisDataset, gisFeature);
            area = polygon(readJson((String) gisArea.get("geoJson")));
            String name = inputs.get("name") instanceof String s && !s.isBlank() ? s.trim() : null;
            String featureLabel = (String) gisArea.get("featureLabel");
            out.put("source", "gis");
            out.put("isCar", false);
            out.put("isGis", true);
            out.put("codImovel", null);
            out.put("label", name != null ? name
                    : featureLabel != null ? featureLabel + " (" + gisArea.get("datasetName") + ")" : (String) gisArea.get("datasetName"));
            putGis(out, gisArea);
        } else {
            area = polygon(geometry);
            String name = inputs.get("name") instanceof String s && !s.isBlank() ? s.trim() : null;
            out.put("source", "polygon");
            out.put("isCar", false);
            out.put("isGis", false);
            out.put("codImovel", null);
            out.put("label", name == null ? "Área informada" : name);
            putGis(out, null);
        }
        out.putAll(area);
        return out;
    }

    private static void putGis(Map<String, Object> out, Map<String, Object> gisArea) {
        out.put("gisDatasetId", gisArea == null ? null : gisArea.get("datasetId"));
        out.put("gisDatasetName", gisArea == null ? null : gisArea.get("datasetName"));
        out.put("gisFeatureId", gisArea == null ? null : gisArea.get("featureId"));
        out.put("gisPolygons", gisArea == null ? null : gisArea.get("polygons"));
    }

    private static Object readJson(String json) {
        try {
            return JSON.readValue(json, Map.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("the GIS geometry is not valid GeoJSON", e);
        }
    }

    private Map<String, Object> car(String codImovel) {
        List<Map<String, Object>> rows;
        try {
            rows = queries.read(CarValues.QUERY_TIMEOUT, jdbc -> jdbc.query(CarPropertyGeometryBlock.SQL, (rs, n) -> row(
                    rs.getString("geojson"), rs.getBigDecimal("area_ha"), rs.getBigDecimal("lat"), rs.getBigDecimal("lon"),
                    rs.getBigDecimal("min_lon"), rs.getBigDecimal("min_lat"), rs.getBigDecimal("max_lon"), rs.getBigDecimal("max_lat"),
                    rs.getInt("vertices")), codImovel));
        } catch (DataAccessException e) {
            throw new IllegalStateException("cannot read the CAR data (car-agent-app) for " + codImovel + ": "
                    + e.getMostSpecificCause().getMessage(), e);
        }
        if (rows.isEmpty()) throw CarValues.notFound(codImovel);
        return rows.get(0);
    }

    private Map<String, Object> polygon(Object geometry) {
        String geoJson = geometryOf(geometry);
        Map<String, Object> result;
        try {
            result = queries.read(CarValues.QUERY_TIMEOUT, jdbc -> jdbc.queryForObject(POLYGON_SQL, (rs, n) -> {
                if (rs.getBoolean("empty")) throw new IllegalArgumentException("the polygon has no area (send a Polygon or MultiPolygon)");
                BigDecimal minLon = rs.getBigDecimal("min_lon");
                BigDecimal minLat = rs.getBigDecimal("min_lat");
                BigDecimal maxLon = rs.getBigDecimal("max_lon");
                BigDecimal maxLat = rs.getBigDecimal("max_lat");
                if (minLon.doubleValue() < -180 || maxLon.doubleValue() > 180 || minLat.doubleValue() < -90 || maxLat.doubleValue() > 90) {
                    throw new IllegalArgumentException("the polygon coordinates must be longitude/latitude in degrees");
                }
                return row(rs.getString("geojson"), rs.getBigDecimal("area_ha"), rs.getBigDecimal("lat"), rs.getBigDecimal("lon"),
                        minLon, minLat, maxLon, maxLat, rs.getInt("vertices"));
            }, geoJson));
        } catch (DataAccessException e) {
            throw new IllegalArgumentException("the polygon is not a valid GeoJSON geometry: " + e.getMostSpecificCause().getMessage(), e);
        }
        if (((Double) result.get("areaHa")) <= 0) throw new IllegalArgumentException("the polygon has no area");
        return result;
    }

    /** The geometry to give PostGIS: a Feature's geometry, a FeatureCollection's as a GeometryCollection. */
    @SuppressWarnings("unchecked")
    static String geometryOf(Object value) {
        if (!(value instanceof Map<?, ?> map)) throw new IllegalArgumentException("geometry is not a GeoJSON object");
        Object geometry = map;
        if ("Feature".equals(map.get("type"))) {
            geometry = map.get("geometry");
        } else if ("FeatureCollection".equals(map.get("type"))) {
            List<Object> parts = new ArrayList<>();
            if (map.get("features") instanceof List<?> features) {
                for (Object feature : features) {
                    if (feature instanceof Map<?, ?> f && f.get("geometry") instanceof Map<?, ?> g) parts.add(g);
                }
            }
            if (parts.isEmpty()) throw new IllegalArgumentException("the FeatureCollection has no geometry");
            geometry = Map.of("type", "GeometryCollection", "geometries", parts);
        }
        if (!(geometry instanceof Map<?, ?> g) || !(g.get("type") instanceof String)) {
            throw new IllegalArgumentException("geometry is not a GeoJSON geometry");
        }
        try {
            return JSON.writeValueAsString(geometry);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("geometry is not valid GeoJSON", e);
        }
    }

    private static Map<String, Object> row(String geojson, BigDecimal areaHa, BigDecimal lat, BigDecimal lon,
                                           BigDecimal minLon, BigDecimal minLat, BigDecimal maxLon, BigDecimal maxLat, int vertices) {
        Map<String, Object> bbox = new LinkedHashMap<>();
        bbox.put("minLon", CarValues.round(minLon, 6));
        bbox.put("minLat", CarValues.round(minLat, 6));
        bbox.put("maxLon", CarValues.round(maxLon, 6));
        bbox.put("maxLat", CarValues.round(maxLat, 6));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("geometry", CarPropertyGeometryBlock.parseGeoJson(geojson));
        out.put("areaHa", CarValues.hectares(areaHa));
        out.put("centroidLat", CarValues.round(lat, 6));
        out.put("centroidLon", CarValues.round(lon, 6));
        out.put("bbox", bbox);
        out.put("vertices", vertices);
        return out;
    }
}
