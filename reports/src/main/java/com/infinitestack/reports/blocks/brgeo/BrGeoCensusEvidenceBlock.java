package com.infinitestack.reports.blocks.brgeo;

import static com.infinitestack.reports.blocks.brgeo.BrGeoTables.CENSUS_CNEFE;
import static com.infinitestack.reports.blocks.brgeo.BrGeoTables.CENSUS_ROUTE;
import static com.infinitestack.reports.blocks.brgeo.BrGeoTables.MUNICIPALITY;
import static com.infinitestack.reports.blocks.brgeo.BrGeoTables.Q_CENSUS_CNEFE;
import static com.infinitestack.reports.blocks.brgeo.BrGeoTables.Q_CENSUS_ROUTE;
import static com.infinitestack.reports.blocks.brgeo.BrGeoTables.Q_MUNICIPALITY;
import static com.infinitestack.reports.blocks.brgeo.BrGeoTables.requirement;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

import org.springframework.stereotype.Component;

import com.infinitestack.reports.engine.DataType;
import com.infinitestack.reports.engine.block.BlockDefinition;
import com.infinitestack.reports.engine.block.BlockDocs;
import com.infinitestack.reports.engine.block.BlockPort;
import com.infinitestack.reports.engine.block.BlockQueries;
import com.infinitestack.reports.engine.block.ExecutionMetadata;
import com.infinitestack.reports.engine.block.ReportBlock;

/**
 * {@code brgeo-census-evidence}: historical evidence from the 2017 Agricultural Census (IBGE) for an
 * area: the enumerator routes that cross it or pass near it, and the CNEFE addresses of agricultural
 * establishments inside it or near it. The status follows phase F3 of the br-geo methodology:
 * {@code trajeto_interno}, {@code trajeto_de_borda}, {@code trajeto_proximo},
 * {@code trajeto_nao_encontrado} or {@code dados_indisponiveis}.
 *
 * <p>It is presence of a census route in 2017, never proof that the property was visited or exists.
 */
@Component
public class BrGeoCensusEvidenceBlock implements ReportBlock {

    static final double DEFAULT_BUFFER_M = 500;
    static final double MAX_BUFFER_M = 5000;
    static final double DEFAULT_EDGE_TOLERANCE_M = 30;
    static final int CNEFE_LIMIT = 20;

    static final String INTERNAL = "trajeto_interno";
    static final String EDGE = "trajeto_de_borda";
    static final String NEAR = "trajeto_proximo";
    static final String NOT_FOUND = "trajeto_nao_encontrado";
    static final String UNAVAILABLE = "dados_indisponiveis";

    /**
     * The area ({@code geom}), the area grown by the buffer ({@code near_geom}, for the index) and the area
     * shrunk by the edge tolerance ({@code core}, empty when the tolerance eats it). Parameters: GeoJSON,
     * buffer (m), edge tolerance (m).
     */
    private static final String ZONE_CTE = "WITH " + BrGeoValues.INPUT_CTE + ","
            + " zone AS (SELECT input.geom, input.area_ha,"
            + " ST_Buffer(input.geom::geography, ?)::geometry AS near_geom,"
            + " ST_CollectionExtract(ST_MakeValid(ST_Buffer(input.geom::geography, -1 * ?)::geometry), 3) AS core FROM input)";

    /** Parameters: GeoJSON, buffer, tolerance, buffer (exact distance). */
    static final String ROUTES_SQL = ZONE_CTE
            + " SELECT zone.area_ha, count(r.id) AS near_routes,"
            + " count(r.id) FILTER (WHERE ST_Intersects(r.geom, zone.geom)) AS crossing_routes,"
            + " COALESCE(sum(ST_Length(ST_Intersection(r.geom, zone.geom)::geography)) FILTER (WHERE ST_Intersects(r.geom, zone.geom)), 0) AS inside_m,"
            + " COALESCE(sum(ST_Length(ST_Intersection(r.geom, zone.core)::geography))"
            + " FILTER (WHERE NOT ST_IsEmpty(zone.core) AND ST_Intersects(r.geom, zone.core)), 0) AS core_m,"
            + " min(ST_Distance(r.geom::geography, zone.geom::geography)) AS min_distance_m"
            + " FROM zone LEFT JOIN " + Q_CENSUS_ROUTE + " r ON r.valid_to IS NULL AND r.geom && zone.near_geom"
            + " AND ST_DWithin(r.geom::geography, zone.geom::geography, ?)"
            + " GROUP BY zone.area_ha";

    /** Whether BR Geo loaded routes for any municipality the area touches. Parameter: GeoJSON. */
    static final String AVAILABLE_SQL = "WITH " + BrGeoValues.INPUT_CTE + ","
            + " edition AS (SELECT max(edition) AS edition FROM " + Q_MUNICIPALITY + " WHERE valid_to IS NULL)"
            + " SELECT EXISTS (SELECT 1 FROM input CROSS JOIN edition JOIN " + Q_MUNICIPALITY + " m ON m.valid_to IS NULL"
            + " AND m.edition = edition.edition AND m.geom && input.geom AND ST_Intersects(m.geom, input.geom)"
            + " JOIN " + Q_CENSUS_ROUTE + " r ON r.valid_to IS NULL AND r.cd_mun = m.cd_mun) AS available";

    /** Parameters: GeoJSON, buffer, tolerance, buffer (exact distance), limit. */
    static final String CNEFE_SQL = ZONE_CTE
            + " SELECT s.*, count(*) OVER () AS near_total, count(*) FILTER (WHERE s.inside) OVER () AS inside_total FROM ("
            + " SELECT c.record_id, c.cd_mun, c.dsc_localidade, c.nom_tipo_seglogr, c.nom_titulo_seglogr, c.nom_seglogr,"
            + " c.num_endereco, c.situacao, ST_X(c.geom) AS lon, ST_Y(c.geom) AS lat,"
            + " ST_Intersects(c.geom, zone.geom) AS inside, ST_Distance(c.geom::geography, zone.geom::geography) AS distance_m"
            + " FROM zone JOIN " + Q_CENSUS_CNEFE + " c ON c.valid_to IS NULL AND c.geom && zone.near_geom"
            + " AND ST_DWithin(c.geom::geography, zone.geom::geography, ?)) s"
            + " ORDER BY s.distance_m, s.record_id LIMIT ?";

    private final BlockQueries queries;

    public BrGeoCensusEvidenceBlock(BlockQueries queries) {
        this.queries = queries;
    }

    @Override
    public BlockDefinition definition() {
        return new BlockDefinition("brgeo-census-evidence", "Census 2017 evidence of an area",
                "IBGE Agricultural Census 2017: enumerator routes and CNEFE establishment addresses inside or near an area",
                "1.0.0",
                List.of(BrGeoValues.GEOMETRY_INPUT,
                        BlockPort.optional("bufferM", DataType.NUMBER, "how far around the area to look, metres; default 500, at most 5000"),
                        BlockPort.optional("edgeToleranceM", DataType.NUMBER,
                                "a route only within this distance of the border counts as edge, not internal; default 30")),
                List.of(
                        BlockPort.required("status", DataType.STRING,
                                "trajeto_interno | trajeto_de_borda | trajeto_proximo | trajeto_nao_encontrado | dados_indisponiveis"),
                        BlockPort.required("routeInside", DataType.BOOLEAN, "status is trajeto_interno"),
                        BlockPort.required("routeEdge", DataType.BOOLEAN, "status is trajeto_de_borda"),
                        BlockPort.required("routeNear", DataType.BOOLEAN, "some route crosses the area or passes within the buffer"),
                        BlockPort.required("dataAvailable", DataType.BOOLEAN, "BR Geo has routes for the municipalities of the area"),
                        BlockPort.required("insideKm", DataType.NUMBER, "length of route inside the area, km (3 decimals)"),
                        BlockPort.required("insideMPerHa", DataType.NUMBER, "metres of route inside the area per hectare"),
                        BlockPort.optional("minDistanceM", DataType.NUMBER, "distance from the area to the nearest route, metres (0 = crosses); empty when none within the buffer"),
                        BlockPort.required("routesCrossing", DataType.NUMBER, "route pieces that cross the area"),
                        BlockPort.required("routesNear", DataType.NUMBER, "route pieces within the buffer (crossing included)"),
                        BlockPort.required("cnefeInside", DataType.NUMBER, "CNEFE agricultural addresses inside the area"),
                        BlockPort.required("cnefeNearCount", DataType.NUMBER, "CNEFE addresses inside the area or within the buffer"),
                        BlockPort.required("cnefeNear", DataType.ARRAY,
                                "nearest first, at most 20: {distanceM, inside, locality, address, number, situation, municipalityIbge, lat, lon}"),
                        BlockPort.required("truncated", DataType.BOOLEAN, "more CNEFE addresses than listed"),
                        BlockPort.required("bufferM", DataType.NUMBER, "the buffer used, metres"),
                        BlockPort.required("edgeToleranceM", DataType.NUMBER, "the edge tolerance used, metres"),
                        BlockPort.required("year", DataType.NUMBER, "census year (2017)")),
                List.of(
                        requirement(CENSUS_ROUTE, "id", "cd_mun", "geom", "valid_to"),
                        requirement(CENSUS_CNEFE, "record_id", "cd_mun", "dsc_localidade", "nom_tipo_seglogr", "nom_titulo_seglogr",
                                "nom_seglogr", "num_endereco", "situacao", "geom", "valid_to"),
                        requirement(MUNICIPALITY, "edition", "cd_mun", "geom", "valid_to")),
                BrGeoValues.BLOCK_TIMEOUT);
    }

    @Override
    public BlockDocs docs() {
        return new BlockDocs("BR Geo",
                "Looks for traces of the 2017 Agricultural Census in an area: the routes the IBGE enumerators travelled "
                        + "and the addresses of agricultural establishments they registered (CNEFE). Historical context for "
                        + "credit analysis, as in phase F3 of the br-geo methodology.",
                List.of(
                        "Measures the length of enumerator route inside the area and the distance to the nearest route within bufferM.",
                        "Status: trajeto_interno when a route enters the area beyond edgeToleranceM from the border; "
                                + "trajeto_de_borda when it only crosses or touches the border strip (or passes within the tolerance "
                                + "outside); trajeto_proximo when the nearest is within bufferM; trajeto_nao_encontrado when there is "
                                + "none; dados_indisponiveis when BR Geo has no route for the municipalities of the area.",
                        "Counts the CNEFE agricultural addresses inside the area and lists the nearest ones within bufferM."),
                List.of(
                        "geometry: connect car-property-geometry.geometry (the whole CAR, before any subtraction) or report-area.geometry.",
                        "Show status with the limitation text: it is presence, not proof.",
                        "Put it in a section 'Evidências históricas' with brgeo-embargoes, brgeo-deter-alerts and brgeo-prodes."),
                List.of(
                        "Historical presence only: a route inside the area means the census operation passed there in 2017, "
                                + "not that this property was interviewed, exists today or has the same owner or use.",
                        "A public road can cross or touch many properties; no route does not prove the property did not exist "
                                + "(coverage gaps, suppressed segments, position errors).",
                        "The IBGE routes are simplified and may be shifted; edgeToleranceM (default 30 m) is an exploratory "
                                + "tolerance, not a validated one.",
                        "A census establishment is not a CAR property: a CNEFE address inside the area is not a match with it.",
                        "Only the states BR Geo loaded are present (dados_indisponiveis elsewhere)."),
                "IBGE, Censo Agropecuário 2017: trajetos dos recenseadores and CNEFE, loaded into "
                        + "gold.br_geo_agent_app_census2017_route and gold.br_geo_agent_app_census2017_cnefe",
                "Censo Agro 2017: {{<node>.status}} ({{<node>.insideKm}} km de trajeto na área; "
                        + "{{<node>.cnefeInside}} endereços CNEFE na área). Presença histórica, não prova de visita.");
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> inputs, ExecutionMetadata metadata) {
        String geoJson = BrGeoValues.geoJson(inputs);
        double bufferM = inputs.get("bufferM") instanceof Number n && n.doubleValue() >= 0
                ? Math.min(MAX_BUFFER_M, n.doubleValue()) : DEFAULT_BUFFER_M;
        double toleranceM = inputs.get("edgeToleranceM") instanceof Number n && n.doubleValue() >= 0
                ? Math.min(bufferM, n.doubleValue()) : Math.min(bufferM, DEFAULT_EDGE_TOLERANCE_M);

        return queries.read(BrGeoValues.QUERY_TIMEOUT, jdbc -> {
            Map<String, Object> routes = jdbc.queryForMap(ROUTES_SQL, geoJson, bufferM, toleranceM, bufferM);
            int near = ((Number) routes.get("near_routes")).intValue();
            int crossing = ((Number) routes.get("crossing_routes")).intValue();
            double insideM = ((Number) routes.get("inside_m")).doubleValue();
            double coreM = ((Number) routes.get("core_m")).doubleValue();
            Double minDistance = routes.get("min_distance_m") instanceof Number n ? BrGeoValues.round(n.doubleValue(), 1) : null;
            double areaHa = routes.get("area_ha") instanceof Number n ? n.doubleValue() : 0.0;
            boolean available = near > 0 || Boolean.TRUE.equals(jdbc.queryForObject(AVAILABLE_SQL, Boolean.class, geoJson));

            List<Map<String, Object>> rows = jdbc.queryForList(CNEFE_SQL, geoJson, bufferM, toleranceM, bufferM, CNEFE_LIMIT);
            int nearTotal = rows.isEmpty() ? 0 : ((Number) rows.get(0).get("near_total")).intValue();
            int insideTotal = rows.isEmpty() ? 0 : ((Number) rows.get(0).get("inside_total")).intValue();
            List<Map<String, Object>> cnefe = new ArrayList<>();
            for (Map<String, Object> row : rows) cnefe.add(address(row));

            String status = status(available, near, crossing, coreM, minDistance, toleranceM);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", status);
            out.put("routeInside", INTERNAL.equals(status));
            out.put("routeEdge", EDGE.equals(status));
            out.put("routeNear", near > 0);
            out.put("dataAvailable", available);
            out.put("insideKm", BrGeoValues.round(insideM / 1000, 3));
            out.put("insideMPerHa", areaHa > 0 ? BrGeoValues.round(insideM / areaHa, 2) : 0.0);
            out.put("minDistanceM", minDistance);
            out.put("routesCrossing", crossing);
            out.put("routesNear", near);
            out.put("cnefeInside", insideTotal);
            out.put("cnefeNearCount", nearTotal);
            out.put("cnefeNear", cnefe);
            out.put("truncated", nearTotal > cnefe.size());
            out.put("bufferM", bufferM);
            out.put("edgeToleranceM", toleranceM);
            out.put("year", 2017);
            return out;
        });
    }

    /**
     * The evidence status of the methodology (F3). A route counts as internal only when part of it lies
     * inside the area shrunk by the tolerance; one that only cuts the border strip, or passes outside within
     * the tolerance, is an edge route.
     */
    static String status(boolean dataAvailable, int nearRoutes, int crossingRoutes, double coreM, Double minDistanceM,
            double toleranceM) {
        if (nearRoutes == 0) return dataAvailable ? NOT_FOUND : UNAVAILABLE;
        if (coreM > 0) return INTERNAL;
        if (crossingRoutes > 0 || (minDistanceM != null && minDistanceM <= toleranceM)) return EDGE;
        return NEAR;
    }

    private static Map<String, Object> address(Map<String, Object> row) {
        StringJoiner street = new StringJoiner(" ");
        for (String column : List.of("nom_tipo_seglogr", "nom_titulo_seglogr", "nom_seglogr")) {
            Object part = row.get(column);
            if (part != null && !part.toString().isBlank()) street.add(part.toString().trim());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("distanceM", BrGeoValues.round(((Number) row.get("distance_m")).doubleValue(), 1));
        out.put("inside", Boolean.TRUE.equals(row.get("inside")));
        out.put("locality", row.get("dsc_localidade"));
        out.put("address", street.length() == 0 ? null : street.toString());
        out.put("number", row.get("num_endereco"));
        out.put("situation", row.get("situacao"));
        out.put("municipalityIbge", row.get("cd_mun") == null ? null : Long.valueOf(row.get("cd_mun").toString()));
        out.put("lat", BrGeoValues.round(((Number) row.get("lat")).doubleValue(), 6));
        out.put("lon", BrGeoValues.round(((Number) row.get("lon")).doubleValue(), 6));
        return out;
    }
}
