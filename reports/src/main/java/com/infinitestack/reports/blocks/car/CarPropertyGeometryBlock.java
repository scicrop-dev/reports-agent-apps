package com.infinitestack.reports.blocks.car;

import static com.infinitestack.reports.blocks.car.CarTables.*;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.infinitestack.reports.engine.DataType;
import com.infinitestack.reports.engine.block.BlockDefinition;
import com.infinitestack.reports.engine.block.BlockDocs;
import com.infinitestack.reports.engine.block.BlockPort;
import com.infinitestack.reports.engine.block.BlockQueries;
import com.infinitestack.reports.engine.block.ExecutionMetadata;
import com.infinitestack.reports.engine.block.ReportBlock;

/**
 * {@code car-property-geometry}: the current geometry of a CAR property as GeoJSON (SIRGAS 2000,
 * SRID 4674), with its computed area, a point inside it, its bounding box and vertex count.
 * The point uses {@code ST_PointOnSurface}, not the centroid, so it always falls inside the polygon.
 */
@Component
public class CarPropertyGeometryBlock implements ReportBlock {

    private static final ObjectMapper JSON = new ObjectMapper();

    static final String SQL = "SELECT ST_AsGeoJSON(g.geom, 6) AS geojson,"
            + " COALESCE(g.area_calc_ha, (ST_Area(g.geom::geography) / 1e4)::numeric) AS area_ha,"
            + " ST_Y(ST_PointOnSurface(g.geom)) AS lat, ST_X(ST_PointOnSurface(g.geom)) AS lon,"
            + " ST_XMin(g.geom) AS min_lon, ST_YMin(g.geom) AS min_lat,"
            + " ST_XMax(g.geom) AS max_lon, ST_YMax(g.geom) AS max_lat,"
            + " ST_NPoints(g.geom) AS vertices"
            + " FROM " + Q_IMOVEL + " i"
            + " JOIN " + Q_IMOVEL_VERSAO + " v ON v.imovel_id = i.id AND v.valido_ate IS NULL"
            + " JOIN " + Q_IMOVEL_GEOMETRIA + " g ON g.id = v.geometria_id"
            + " WHERE i.cod_imovel = ?";

    private final BlockQueries queries;

    public CarPropertyGeometryBlock(BlockQueries queries) {
        this.queries = queries;
    }

    @Override
    public BlockDefinition definition() {
        return new BlockDefinition("car-property-geometry", "CAR property geometry",
                "Current geometry of a CAR property as GeoJSON, with area, inner point, bounding box and vertex count",
                "1.0.0",
                List.of(CarValues.COD_IMOVEL_INPUT),
                List.of(
                        BlockPort.required("geometry", DataType.GEOMETRY, "GeoJSON geometry, SRID 4674"),
                        BlockPort.required("areaHa", DataType.NUMBER, "computed area, hectares"),
                        BlockPort.required("centroidLat", DataType.NUMBER, "latitude of a point inside the property"),
                        BlockPort.required("centroidLon", DataType.NUMBER, "longitude of a point inside the property"),
                        BlockPort.required("bbox", DataType.OBJECT, "{minLon, minLat, maxLon, maxLat}"),
                        BlockPort.required("vertices", DataType.NUMBER, "number of vertices")),
                List.of(
                        requirement(IMOVEL, "id", "cod_imovel"),
                        requirement(IMOVEL_VERSAO, "imovel_id", "valido_ate", "geometria_id"),
                        requirement(IMOVEL_GEOMETRIA, "id", "geom", "area_calc_ha")),
                null);
    }

    @Override
    public BlockDocs docs() {
        return new BlockDocs("CAR",
                "Gives the boundary of a CAR property as a geometry, plus its computed area, a reference point "
                        + "inside it and its bounding box. It is the bridge from the CAR to every spatial Block.",
                List.of(
                        "Takes the geometry of the current registration version.",
                        "Returns it as GeoJSON in SIRGAS 2000 (SRID 4674), with 6 decimals.",
                        "Computes the reference point with ST_PointOnSurface, so it always falls inside the polygon."),
                List.of(
                        "codImovel: the same report input as car-property.",
                        "geometry: connect it to brgeo-biomes, brgeo-municipalities, brgeo-embargoes, brgeo-deter-alerts, "
                                + "brgeo-prodes and sathub-index-series, which all analyse an area."),
                List.of(
                        "The computed area can differ a little from the declared one (car-property.declaredAreaHa).",
                        "The geometry is the declared drawing; it may overlap neighbours (see car-overlaps)."),
                "SICAR, gold.car_agent_app_imovel_geometria",
                "Area calculada: {{<node>.areaHa}} ha - ponto {{<node>.centroidLat}}, {{<node>.centroidLon}}");
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> inputs, ExecutionMetadata metadata) {
        String codImovel = CarValues.codImovel(inputs);
        List<Map<String, Object>> rows = queries.read(CarValues.QUERY_TIMEOUT, jdbc -> jdbc.query(SQL, (rs, n) -> {
            Map<String, Object> bbox = new LinkedHashMap<>();
            bbox.put("minLon", coordinate(rs.getBigDecimal("min_lon")));
            bbox.put("minLat", coordinate(rs.getBigDecimal("min_lat")));
            bbox.put("maxLon", coordinate(rs.getBigDecimal("max_lon")));
            bbox.put("maxLat", coordinate(rs.getBigDecimal("max_lat")));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("geometry", parseGeoJson(rs.getString("geojson")));
            out.put("areaHa", CarValues.hectares(rs.getBigDecimal("area_ha")));
            out.put("centroidLat", coordinate(rs.getBigDecimal("lat")));
            out.put("centroidLon", coordinate(rs.getBigDecimal("lon")));
            out.put("bbox", bbox);
            out.put("vertices", rs.getInt("vertices"));
            return out;
        }, codImovel));
        if (rows.isEmpty()) throw CarValues.notFound(codImovel);
        return rows.get(0);
    }

    /** Coordinates with 6 decimals, the precision of the GeoJSON. */
    private static Double coordinate(BigDecimal value) {
        return CarValues.round(value, 6);
    }

    static Map<String, Object> parseGeoJson(String geojson) {
        try {
            return JSON.readValue(geojson, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("invalid GeoJSON from the database", e);
        }
    }
}
