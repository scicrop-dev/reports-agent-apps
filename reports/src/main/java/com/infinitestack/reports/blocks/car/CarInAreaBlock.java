package com.infinitestack.reports.blocks.car;

import static com.infinitestack.reports.blocks.car.CarTables.*;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import com.infinitestack.reports.engine.DataType;
import com.infinitestack.reports.engine.block.BlockDefinition;
import com.infinitestack.reports.engine.block.BlockDocs;
import com.infinitestack.reports.engine.block.BlockPort;
import com.infinitestack.reports.engine.block.BlockQueries;
import com.infinitestack.reports.engine.block.ExecutionMetadata;
import com.infinitestack.reports.engine.block.ReportBlock;

/**
 * {@code car-in-area}: the current CAR properties that cut a given area (any polygon, e.g. the
 * one of {@code report-area}), how much of the area each one covers and how much of the area is
 * covered by some CAR at all.
 *
 * <p>The polygon is cleaned like {@code report-area} does (valid, polygons only, parts merged).
 * Candidates come from the GiST index of {@code imovel_geometria} ({@code &&} then
 * {@code ST_Intersects}) and are kept only when the geometry is the current version of its property.
 * Overlaps are geodesic hectares; pieces under {@code minOverlapHa} (edge touches) are ignored. The
 * coverage unions the pieces ({@code ST_Union}), so land claimed by two properties counts once,
 * unlike {@code totalOverlapHa}.
 */
@Component
public class CarInAreaBlock implements ReportBlock {

    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 500;
    static final double DEFAULT_MIN_OVERLAP_HA = 0.01;
    static final Duration TIMEOUT = Duration.ofSeconds(30);
    /** Statement timeout, a little under the Block's own so the database stops before the Block gives up. */
    static final Duration QUERY_TIMEOUT = Duration.ofSeconds(25);

    /** Parameters: the GeoJSON, the minimum overlap (ha) and the row limit. One row with nulls when nothing cuts the area. */
    static final String SQL = "WITH a AS (SELECT ST_UnaryUnion(ST_CollectionExtract(ST_MakeValid("
            + "ST_SetSRID(ST_GeomFromGeoJSON(?), 4674)), 3)) AS geom),"
            + " info AS (SELECT ST_IsEmpty(geom) AS empty, ST_Area(geom::geography) / 1e4 AS area_ha,"
            + " ST_XMin(geom) AS min_lon, ST_YMin(geom) AS min_lat, ST_XMax(geom) AS max_lon, ST_YMax(geom) AS max_lat FROM a),"
            + " hits AS MATERIALIZED ("
            + " SELECT v.cod_imovel, i.uf, m.nome AS municipio, s.descricao AS status_label, t.descricao AS tipo_label,"
            + " v.num_area, COALESCE(g.area_calc_ha, (ST_Area(g.geom::geography) / 1e4)::numeric) AS property_ha,"
            + " ST_CollectionExtract(ST_Intersection(ST_MakeValid(g.geom), a.geom), 3) AS piece"
            + " FROM a"
            + " JOIN " + Q_IMOVEL_GEOMETRIA + " g ON g.geom && a.geom AND ST_Intersects(g.geom, a.geom)"
            + " JOIN " + Q_IMOVEL_VERSAO + " v ON v.imovel_id = g.imovel_id AND v.geometria_id = g.id AND v.valido_ate IS NULL"
            + " JOIN " + Q_IMOVEL + " i ON i.id = g.imovel_id"
            + " LEFT JOIN " + Q_MUNICIPIO + " m ON m.cod_ibge = i.cod_municipio"
            + " LEFT JOIN " + Q_STATUS_CADASTRO + " s ON s.codigo = v.ind_status"
            + " LEFT JOIN " + Q_TIPO_IMOVEL + " t ON t.codigo = v.ind_tipo),"
            + " kept AS MATERIALIZED (SELECT * FROM (SELECT hits.*, ST_Area(piece::geography) / 1e4 AS overlap_ha FROM hits) x"
            + " WHERE overlap_ha >= ?),"
            + " cov AS (SELECT ST_Area(ST_Union(piece)::geography) / 1e4 AS covered_ha FROM kept)"
            + " SELECT info.*, cov.covered_ha, k.cod_imovel, k.uf, k.municipio, k.status_label, k.tipo_label, k.num_area,"
            + " k.property_ha, k.overlap_ha,"
            + " COUNT(k.cod_imovel) OVER () AS total, SUM(k.overlap_ha) OVER () AS total_overlap_ha"
            + " FROM info CROSS JOIN cov LEFT JOIN kept k ON true"
            + " ORDER BY k.overlap_ha DESC NULLS LAST, k.cod_imovel"
            + " LIMIT ?";

    private final BlockQueries queries;

    public CarInAreaBlock(BlockQueries queries) {
        this.queries = queries;
    }

    @Override
    public BlockDefinition definition() {
        return new BlockDefinition("car-in-area", "CAR properties in an area",
                "Current CAR properties that cut a polygon, with the overlap of each and the share of the area covered by CAR",
                "1.0.0",
                List.of(
                        BlockPort.required("geometry", DataType.GEOMETRY,
                                "GeoJSON Polygon or MultiPolygon in lon/lat (SIRGAS 2000 or WGS 84); a Feature or FeatureCollection also works"),
                        BlockPort.optional("limit", DataType.NUMBER, "maximum properties listed, default 50 (1-500)"),
                        BlockPort.optional("minOverlapHa", DataType.NUMBER,
                                "minimum overlap in hectares, default 0.01 (ignores edge touches)")),
                List.of(
                        BlockPort.required("properties", DataType.ARRAY,
                                "largest overlap first: {codImovel, uf, municipality, statusLabel, typeLabel, declaredAreaHa,"
                                        + " overlapHa, overlapPctOfArea, overlapPctOfProperty}"),
                        BlockPort.required("count", DataType.NUMBER, "current properties cutting the area above the minimum"),
                        BlockPort.required("truncated", DataType.BOOLEAN, "more properties than the limit"),
                        BlockPort.required("hasProperties", DataType.BOOLEAN, "at least one property cuts the area"),
                        BlockPort.required("areaHa", DataType.NUMBER, "area of the polygon, hectares"),
                        BlockPort.required("totalOverlapHa", DataType.NUMBER,
                                "sum of the overlaps of every property (land claimed twice counts twice)"),
                        BlockPort.required("coveredHa", DataType.NUMBER, "part of the area inside some CAR property, hectares (union)"),
                        BlockPort.required("coveragePct", DataType.NUMBER, "coveredHa as a percentage of areaHa")),
                List.of(
                        requirement(IMOVEL, "id", "uf", "cod_municipio"),
                        requirement(IMOVEL_VERSAO, "imovel_id", "cod_imovel", "valido_ate", "geometria_id", "ind_status",
                                "ind_tipo", "num_area"),
                        requirement(IMOVEL_GEOMETRIA, "id", "imovel_id", "geom", "area_calc_ha"),
                        requirement(MUNICIPIO, "cod_ibge", "nome"),
                        requirement(STATUS_CADASTRO, "codigo", "descricao"),
                        requirement(TIPO_IMOVEL, "codigo", "descricao")),
                TIMEOUT);
    }

    @Override
    public BlockDocs docs() {
        return new BlockDocs("CAR",
                "Lists the CAR properties registered over any area (a farm drawn by hand, a field, a "
                        + "municipality piece) and says how much of the area is covered by some CAR. It answers "
                        + "\"who declared this land in the CAR?\".",
                List.of(
                        "Cleans the polygon: makes it valid, keeps only its polygons and merges overlapping parts.",
                        "Searches the spatial index for current CAR properties that intersect it.",
                        "Measures, in geodesic hectares, the piece of the area inside each property, and ignores pieces "
                                + "smaller than minOverlapHa.",
                        "Unions those pieces to measure how much of the area is covered by some CAR (coveredHa, coveragePct)."),
                List.of(
                        "geometry: connect report-area.geometry (or any Block output that is a polygon).",
                        "properties goes to a table; coveragePct and count to highlight cards.",
                        "hasProperties wraps the table: {{#<node>.hasProperties}}...{{/<node>.hasProperties}}.",
                        "codImovel of each item can be typed into another CAR report."),
                List.of(
                        "Only current registrations; properties removed from the SICAR are not listed.",
                        "totalOverlapHa adds the overlaps of every property, so overlapping registrations make it larger "
                                + "than coveredHa; coveredHa counts each piece of land once.",
                        "Meant for farm to municipality sized areas: a whole state can exceed the 30 s limit.",
                        "Coordinates must be longitude/latitude in degrees."),
                "SICAR, gold.car_agent_app_imovel_geometria with _imovel_versao",
                "{{<node>.count}} imovel(is) CAR, cobrindo {{<node>.coveragePct}}% da area");
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> inputs, ExecutionMetadata metadata) {
        String geoJson = ReportAreaBlock.geometryOf(inputs.get("geometry"));
        int limit = limit(inputs.get("limit"));
        double minOverlapHa = inputs.get("minOverlapHa") instanceof Number n ? Math.max(0, n.doubleValue()) : DEFAULT_MIN_OVERLAP_HA;
        List<Map<String, Object>> rows;
        try {
            rows = queries.read(QUERY_TIMEOUT, jdbc -> jdbc.queryForList(SQL, geoJson, minOverlapHa, limit));
        } catch (DataAccessException e) {
            String message = e.getMostSpecificCause().getMessage();
            if (message != null && message.contains("out of range")) {
                throw new IllegalArgumentException("the polygon coordinates must be longitude/latitude in degrees", e);
            }
            if (message != null && message.contains("GeoJSON")) {
                throw new IllegalArgumentException("the polygon is not a valid GeoJSON geometry: " + message, e);
            }
            throw e;
        }
        return result(rows, limit);
    }

    static Map<String, Object> result(List<Map<String, Object>> rows, int limit) {
        Map<String, Object> first = rows.get(0);
        if (Boolean.TRUE.equals(first.get("empty"))) {
            throw new IllegalArgumentException("the polygon has no area (send a Polygon or MultiPolygon)");
        }
        if (number(first.get("min_lon")) < -180 || number(first.get("max_lon")) > 180
                || number(first.get("min_lat")) < -90 || number(first.get("max_lat")) > 90) {
            throw new IllegalArgumentException("the polygon coordinates must be longitude/latitude in degrees");
        }
        double areaHa = number(first.get("area_ha"));
        if (areaHa <= 0) throw new IllegalArgumentException("the polygon has no area");
        Double area = CarValues.hectares(areaHa);

        List<Map<String, Object>> properties = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (row.get("cod_imovel") == null) continue; // nothing cuts the area
            double overlapHa = CarValues.hectares(number(row.get("overlap_ha")));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("codImovel", row.get("cod_imovel"));
            item.put("uf", row.get("uf"));
            item.put("municipality", row.get("municipio"));
            item.put("statusLabel", row.get("status_label"));
            item.put("typeLabel", row.get("tipo_label"));
            item.put("declaredAreaHa", CarValues.hectares((BigDecimal) row.get("num_area")));
            item.put("overlapHa", overlapHa);
            item.put("overlapPctOfArea", CarValues.percent(overlapHa, area));
            Double propertyHa = row.get("property_ha") == null ? null : CarValues.hectares(number(row.get("property_ha")));
            item.put("overlapPctOfProperty", CarValues.percent(overlapHa, propertyHa));
            properties.add(item);
        }
        long count = first.get("total") == null ? 0 : ((Number) first.get("total")).longValue();
        double totalOverlap = first.get("total_overlap_ha") == null ? 0 : number(first.get("total_overlap_ha"));
        double covered = first.get("covered_ha") == null ? 0 : number(first.get("covered_ha"));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("properties", properties);
        out.put("count", count);
        out.put("truncated", count > limit);
        out.put("hasProperties", count > 0);
        out.put("areaHa", area);
        out.put("totalOverlapHa", CarValues.hectares(totalOverlap));
        out.put("coveredHa", CarValues.hectares(covered));
        out.put("coveragePct", Math.min(100.0, CarValues.percent(covered, area)));
        return out;
    }

    static int limit(Object value) {
        if (!(value instanceof Number n)) return DEFAULT_LIMIT;
        return Math.max(1, Math.min(MAX_LIMIT, n.intValue()));
    }

    private static double number(Object value) {
        return ((Number) value).doubleValue();
    }
}
