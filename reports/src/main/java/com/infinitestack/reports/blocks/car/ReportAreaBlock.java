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
import com.infinitestack.reports.engine.DataType;
import com.infinitestack.reports.engine.block.BlockDefinition;
import com.infinitestack.reports.engine.block.BlockDocs;
import com.infinitestack.reports.engine.block.BlockPort;
import com.infinitestack.reports.engine.block.BlockQueries;
import com.infinitestack.reports.engine.block.ExecutionMetadata;
import com.infinitestack.reports.engine.block.ReportBlock;

/**
 * {@code report-area}: the area a report is about, from a CAR property code or from a polygon the
 * user sends, so one ready-made report serves both. Gives the same outputs as
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

    public ReportAreaBlock(BlockQueries queries) {
        this.queries = queries;
    }

    @Override
    public BlockDefinition definition() {
        return new BlockDefinition("report-area", "Report area (CAR or polygon)",
                "The area of the report, from a CAR property code or a GeoJSON polygon: geometry, area, inner point and bounding box",
                "1.0.0",
                List.of(
                        BlockPort.optional("codImovel", DataType.STRING, "SICAR property code; give this or 'geometry'"),
                        BlockPort.optional("geometry", DataType.GEOMETRY,
                                "GeoJSON Polygon or MultiPolygon in lon/lat (SIRGAS 2000 or WGS 84); a Feature or FeatureCollection also works"),
                        BlockPort.optional("name", DataType.STRING, "name to show for a polygon (e.g. Fazenda Boa Vista)")),
                List.of(
                        BlockPort.required("source", DataType.STRING, "car or polygon"),
                        BlockPort.required("isCar", DataType.BOOLEAN, "the area is a CAR property"),
                        BlockPort.optional("codImovel", DataType.STRING,
                                "the CAR code, null for a polygon; connect it to the CAR Blocks marked optional in the Flow"),
                        BlockPort.required("label", DataType.STRING, "how to call the area: the CAR code, the name given or 'Área informada'"),
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
                "Starts a report that works both for a CAR property and for any polygon. It turns either one into "
                        + "the geometry every spatial Block takes, and says which one it was.",
                List.of(
                        "With codImovel: reads the current geometry of the property, as car-property-geometry does.",
                        "With geometry: makes it valid, keeps only its polygons and merges overlapping parts; a Feature "
                                + "or a FeatureCollection is reduced to its geometries.",
                        "Computes area (geodesic), an inner point (ST_PointOnSurface), bounding box and vertex count.",
                        "Fails when both or neither are given, when the polygon has no area or when its coordinates are not degrees."),
                List.of(
                        "codImovel and geometry: two optional report inputs; the person running the report fills one.",
                        "geometry: connect it to every Block that analyses an area (BR Geo, BR Weather, Satellite Hub).",
                        "codImovel: connect it to the CAR Blocks (car-property, car-property-map...) and mark those nodes "
                                + "optional, so they are skipped for a polygon.",
                        "isCar: wrap the CAR sections of the template in {{#<node>.isCar}}...{{/<node>.isCar}}."),
                List.of(
                        "Coordinates must be longitude/latitude in degrees; SIRGAS 2000 and WGS 84 differ by less than a metre.",
                        "A CAR code on an instance without the car-agent-app data fails."),
                "SICAR (car-agent-app) for a CAR code; the polygon given by the user",
                "{{<node>.label}}: {{<node>.areaHa}} ha");
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> inputs, ExecutionMetadata metadata) {
        String codImovel = inputs.get("codImovel") instanceof String s && !s.isBlank() ? s.trim() : null;
        Object geometry = inputs.get("geometry");
        if (codImovel != null && geometry != null) {
            throw new IllegalArgumentException("give the CAR code or the polygon, not both");
        }
        if (codImovel == null && geometry == null) {
            throw new IllegalArgumentException("give a CAR code (codImovel) or a polygon (geometry)");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> area;
        if (codImovel != null) {
            area = car(codImovel);
            out.put("source", "car");
            out.put("isCar", true);
            out.put("codImovel", codImovel);
            out.put("label", codImovel);
        } else {
            area = polygon(geometry);
            String name = inputs.get("name") instanceof String s && !s.isBlank() ? s.trim() : null;
            out.put("source", "polygon");
            out.put("isCar", false);
            out.put("codImovel", null);
            out.put("label", name == null ? "Área informada" : name);
        }
        out.putAll(area);
        return out;
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
