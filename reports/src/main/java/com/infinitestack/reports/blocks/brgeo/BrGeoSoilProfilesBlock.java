package com.infinitestack.reports.blocks.brgeo;

import static com.infinitestack.reports.blocks.brgeo.BrGeoTables.PEDOLOGY_ANALYSIS;
import static com.infinitestack.reports.blocks.brgeo.BrGeoTables.PEDOLOGY_HORIZON;
import static com.infinitestack.reports.blocks.brgeo.BrGeoTables.PEDOLOGY_POINT;
import static com.infinitestack.reports.blocks.brgeo.BrGeoTables.Q_PEDOLOGY_ANALYSIS;
import static com.infinitestack.reports.blocks.brgeo.BrGeoTables.Q_PEDOLOGY_HORIZON;
import static com.infinitestack.reports.blocks.brgeo.BrGeoTables.Q_PEDOLOGY_POINT;
import static com.infinitestack.reports.blocks.brgeo.BrGeoTables.requirement;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.infinitestack.reports.engine.DataType;
import com.infinitestack.reports.engine.block.BlockDefinition;
import com.infinitestack.reports.engine.block.BlockDocs;
import com.infinitestack.reports.engine.block.BlockPort;
import com.infinitestack.reports.engine.block.BlockQueries;
import com.infinitestack.reports.engine.block.ExecutionMetadata;
import com.infinitestack.reports.engine.block.ReportBlock;

/**
 * {@code brgeo-soil-profiles}: the IBGE soil profile points nearest to an area (inside it or within
 * a radius), with their classification and the sand, silt and clay of the top 40 cm measured in the
 * lab: the mean of the analysed horizons weighted by how many of the 0-40 cm each covers.
 *
 * <p>Points, horizons and analyses share the point code ({@code cod_ponto}); an analysis belongs to
 * a horizon by {@code cod_horizonte}, the horizon's {@code id} (the sheet's {@code CodMorfologiaFk}).
 * Granulometry is published in g/kg and comes out in percent.
 */
@Component
public class BrGeoSoilProfilesBlock implements ReportBlock {

    static final double DEFAULT_RADIUS_KM = 20;
    static final double MAX_RADIUS_KM = 200;
    static final int DEFAULT_LIMIT = 5;
    static final int MAX_LIMIT = 50;
    static final int TOP_CM = 0;
    static final int BOTTOM_CM = 40;

    /**
     * Parameters: GeoJSON, radius (m) for the bounding box, radius (m) for the exact test, limit. The
     * box is the area grown by the radius in degrees at its widest latitude, so the GiST index is used.
     */
    static final String POINTS_SQL = "WITH " + BrGeoValues.INPUT_CTE + ","
            + " edition AS (SELECT max(edition) AS edition FROM " + Q_PEDOLOGY_POINT + " WHERE valid_to IS NULL)"
            + " SELECT edition.edition, p.cod_ponto, p.id_ponto, p.tipo_ponto, p.ordem, p.subordem, p.grande_grupo,"
            + " p.textura, p.drenagem, p.relevo, ST_X(p.geom) AS lon, ST_Y(p.geom) AS lat,"
            + " ST_Distance(p.geom::geography, input.geom::geography) AS distance_m"
            + " FROM input CROSS JOIN edition JOIN " + Q_PEDOLOGY_POINT + " p ON p.valid_to IS NULL AND p.edition = edition.edition"
            + " AND p.geom && ST_Expand(input.geom, ? / (111320.0 * cos(radians(least(89, greatest(abs(ST_YMin(input.geom)), abs(ST_YMax(input.geom))))))))"
            + " AND ST_DWithin(p.geom::geography, input.geom::geography, ?)"
            + " ORDER BY distance_m, p.cod_ponto LIMIT ?";

    /** Parameters: edition, point codes (int[]). One row per horizon and analysis (or none). */
    static final String HORIZONS_SQL = "SELECT h.cod_ponto, h.id, h.horizonte, h.limite_superior, h.limite_inferior,"
            + " h.limite_superior_cm, h.limite_inferior_cm, h.classe_textura,"
            + " a.areia_total, a.areia_muito_grossa, a.areia_grossa, a.areia_media, a.areia_fina, a.areia_muito_fina, a.silte, a.argila"
            + " FROM " + Q_PEDOLOGY_HORIZON + " h"
            + " LEFT JOIN " + Q_PEDOLOGY_ANALYSIS + " a ON a.valid_to IS NULL AND a.edition = h.edition"
            + " AND a.cod_ponto = h.cod_ponto AND a.cod_horizonte = h.id"
            + " WHERE h.valid_to IS NULL AND h.edition = ? AND h.cod_ponto = ANY (?)"
            + " ORDER BY h.cod_ponto, h.limite_superior_cm NULLS LAST, h.id, a.id";

    /** One horizon with its depth (cm) and granulometry (g/kg); any value may be missing. */
    record Layer(Integer topCm, Integer bottomCm, Double sand, Double silt, Double clay) {}

    /** Sand, silt and clay (%) of a depth range, and how many cm of it had lab data. */
    record Granulometry(Double sandPct, Double siltPct, Double clayPct, int coveredCm) {}

    private final BlockQueries queries;

    public BrGeoSoilProfilesBlock(BlockQueries queries) {
        this.queries = queries;
    }

    @Override
    public BlockDefinition definition() {
        return new BlockDefinition("brgeo-soil-profiles", "Soil profiles near an area",
                "IBGE soil profile points nearest to an area, with classification and the lab sand, silt and clay of 0-40 cm",
                "1.0.0",
                List.of(BrGeoValues.GEOMETRY_INPUT,
                        BlockPort.optional("radiusKm", DataType.NUMBER, "search radius around the area, km; default 20, at most 200"),
                        BlockPort.optional("limit", DataType.NUMBER, "how many profiles, nearest first; default 5, at most 50")),
                List.of(
                        BlockPort.required("found", DataType.BOOLEAN, "at least one profile point within the radius"),
                        BlockPort.required("profiles", DataType.ARRAY,
                                "nearest first: {code, pointCode, type, distanceKm, inside, lat, lon, order, suborder, greatGroup, texture,"
                                        + " drainage, relief, sand0_40Pct, silt0_40Pct, clay0_40Pct, covered0_40Cm, hasLabData,"
                                        + " horizons [{name, top, bottom, topCm, bottomCm, textureClass, sandPct, siltPct, clayPct}]}"),
                        BlockPort.required("count", DataType.NUMBER, "profiles returned"),
                        BlockPort.required("radiusKm", DataType.NUMBER, "the radius used, km"),
                        BlockPort.optional("edition", DataType.NUMBER, "edition (year) of the IBGE pedology data used")),
                List.of(
                        requirement(PEDOLOGY_POINT, "edition", "cod_ponto", "id_ponto", "tipo_ponto", "ordem", "subordem",
                                "grande_grupo", "textura", "drenagem", "relevo", "geom", "valid_to"),
                        requirement(PEDOLOGY_HORIZON, "edition", "id", "cod_ponto", "horizonte", "limite_superior", "limite_inferior",
                                "limite_superior_cm", "limite_inferior_cm", "classe_textura", "valid_to"),
                        requirement(PEDOLOGY_ANALYSIS, "edition", "id", "cod_ponto", "cod_horizonte", "areia_total",
                                "areia_muito_grossa", "areia_grossa", "areia_media", "areia_fina", "areia_muito_fina", "silte",
                                "argila", "valid_to")),
                BrGeoValues.BLOCK_TIMEOUT);
    }

    @Override
    public BlockDocs docs() {
        return new BlockDocs("BR Geo",
                "Finds the soil profiles the IBGE described and analysed nearest to an area and gives, for each, the soil "
                        + "class, drainage, relief and the sand, silt and clay of the top 40 cm measured in the lab.",
                List.of(
                        "Takes the most recent edition of the IBGE pedology points (profiles and samples).",
                        "Lists the points inside the area or within radiusKm of its border, nearest first (distance 0 = inside).",
                        "Reads the horizons of each point and their lab analyses; an analysis belongs to a horizon by the "
                                + "horizon id the IBGE gives it.",
                        "Averages sand, silt and clay of the horizons that overlap 0-40 cm, weighted by the centimetres of "
                                + "0-40 each covers, and converts g/kg to %. covered0_40Cm says how much of the 40 cm had data."),
                List.of(
                        "geometry: connect car-property-geometry.geometry or report-area.geometry.",
                        "radiusKm: profiles are sparse (about 5,600 in Brazil); 20 km finds some in most agricultural regions, "
                                + "raise it in the North.",
                        "Use it next to brgeo-soil-units: the unit is the mapped soil, the profile is a measured one nearby.",
                        "clay0_40Pct is the input the ZARC soil class (AD1 to AD6) starts from, with sand."),
                List.of(
                        "A profile is one pit, maybe kilometres away and decades old: it describes the soil of the region, "
                                + "not of the field.",
                        "Points without lab analysis (or whose horizon depths are not whole centimetres) come with empty "
                                + "granulometry (hasLabData false).",
                        "Sand is the total sand of the analysis, or the sum of its five fractions when the total is missing."),
                "IBGE, Pedologia 1:250.000 (pontos, horizontes e análises), loaded into gold.br_geo_agent_app_ibge_pedology_point,"
                        + " _horizon and _analysis",
                "{{#<node>.profiles}}{{code}} ({{distanceKm}} km): {{order}} {{suborder}}, argila 0-40 cm {{clay0_40Pct}}%<br>{{/<node>.profiles}}"
                        + "{{^<node>.found}}Nenhum perfil num raio de {{<node>.radiusKm}} km.{{/<node>.found}}");
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> inputs, ExecutionMetadata metadata) {
        String geoJson = BrGeoValues.geoJson(inputs);
        double radiusKm = inputs.get("radiusKm") instanceof Number n && n.doubleValue() > 0
                ? Math.min(MAX_RADIUS_KM, n.doubleValue()) : DEFAULT_RADIUS_KM;
        int limit = inputs.get("limit") instanceof Number n ? Math.max(1, Math.min(MAX_LIMIT, n.intValue())) : DEFAULT_LIMIT;
        double radiusM = radiusKm * 1000;

        return queries.read(BrGeoValues.QUERY_TIMEOUT, jdbc -> {
            List<Map<String, Object>> points = jdbc.queryForList(POINTS_SQL, geoJson, radiusM, radiusM, limit);
            Object edition = points.isEmpty() ? null : points.get(0).get("edition");
            Map<Integer, List<Map<String, Object>>> horizonRows = new LinkedHashMap<>();
            if (!points.isEmpty()) {
                Integer[] codes = points.stream().map(p -> p.get("cod_ponto")).filter(c -> c != null)
                        .map(c -> ((Number) c).intValue()).toArray(Integer[]::new);
                jdbc.query(con -> {
                    var ps = con.prepareStatement(HORIZONS_SQL);
                    ps.setObject(1, ((Number) edition).intValue());
                    ps.setArray(2, con.createArrayOf("integer", codes));
                    return ps;
                }, rs -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (String column : List.of("cod_ponto", "id", "horizonte", "limite_superior", "limite_inferior",
                            "limite_superior_cm", "limite_inferior_cm", "classe_textura", "areia_total", "areia_muito_grossa",
                            "areia_grossa", "areia_media", "areia_fina", "areia_muito_fina", "silte", "argila")) {
                        row.put(column, rs.getObject(column));
                    }
                    horizonRows.computeIfAbsent(rs.getInt("cod_ponto"), k -> new ArrayList<>()).add(row);
                });
            }

            List<Map<String, Object>> profiles = new ArrayList<>();
            for (Map<String, Object> point : points) {
                Integer code = point.get("cod_ponto") instanceof Number n ? n.intValue() : null;
                profiles.add(profile(point, code == null ? List.of() : horizonRows.getOrDefault(code, List.of())));
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("found", !profiles.isEmpty());
            out.put("profiles", profiles);
            out.put("count", profiles.size());
            out.put("radiusKm", radiusKm);
            out.put("edition", edition);
            return out;
        });
    }

    private static Map<String, Object> profile(Map<String, Object> point, List<Map<String, Object>> rows) {
        // One horizon may have more than one analysis: average them per horizon.
        Map<Object, List<Map<String, Object>>> byHorizon = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) byHorizon.computeIfAbsent(row.get("id"), k -> new ArrayList<>()).add(row);

        List<Layer> layers = new ArrayList<>();
        List<Map<String, Object>> horizons = new ArrayList<>();
        for (List<Map<String, Object>> analyses : byHorizon.values()) {
            Map<String, Object> first = analyses.get(0);
            Double sand = mean(analyses.stream().map(BrGeoSoilProfilesBlock::sand).toList());
            Double silt = mean(analyses.stream().map(a -> number(a.get("silte"))).toList());
            Double clay = mean(analyses.stream().map(a -> number(a.get("argila"))).toList());
            Integer top = first.get("limite_superior_cm") instanceof Number n ? n.intValue() : null;
            Integer bottom = first.get("limite_inferior_cm") instanceof Number n ? n.intValue() : null;
            layers.add(new Layer(top, bottom, sand, silt, clay));
            Map<String, Object> horizon = new LinkedHashMap<>();
            horizon.put("name", first.get("horizonte"));
            horizon.put("top", first.get("limite_superior"));
            horizon.put("bottom", first.get("limite_inferior"));
            horizon.put("topCm", top);
            horizon.put("bottomCm", bottom);
            horizon.put("textureClass", first.get("classe_textura"));
            horizon.put("sandPct", percent(sand));
            horizon.put("siltPct", percent(silt));
            horizon.put("clayPct", percent(clay));
            horizons.add(horizon);
        }
        Granulometry g = weighted(layers, TOP_CM, BOTTOM_CM);

        double distanceM = point.get("distance_m") instanceof Number n ? n.doubleValue() : 0.0;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", point.get("id_ponto"));
        out.put("pointCode", point.get("cod_ponto"));
        out.put("type", point.get("tipo_ponto"));
        out.put("distanceKm", BrGeoValues.round(distanceM / 1000, 2));
        out.put("inside", distanceM <= 0);
        out.put("lat", point.get("lat") instanceof Number n ? BrGeoValues.round(n.doubleValue(), 6) : null);
        out.put("lon", point.get("lon") instanceof Number n ? BrGeoValues.round(n.doubleValue(), 6) : null);
        out.put("order", point.get("ordem"));
        out.put("suborder", point.get("subordem"));
        out.put("greatGroup", point.get("grande_grupo"));
        out.put("texture", point.get("textura"));
        out.put("drainage", point.get("drenagem"));
        out.put("relief", point.get("relevo"));
        out.put("sand0_40Pct", g.sandPct());
        out.put("silt0_40Pct", g.siltPct());
        out.put("clay0_40Pct", g.clayPct());
        out.put("covered0_40Cm", g.coveredCm());
        out.put("hasLabData", g.coveredCm() > 0);
        out.put("horizons", horizons);
        return out;
    }

    /**
     * Sand, silt and clay (%) between {@code fromCm} and {@code toCm}: the mean of the layers with all
     * three values and known depths, each weighted by the centimetres of the range it covers.
     */
    static Granulometry weighted(List<Layer> layers, int fromCm, int toCm) {
        double weight = 0, sand = 0, silt = 0, clay = 0;
        for (Layer layer : layers) {
            if (layer.topCm() == null || layer.bottomCm() == null) continue;
            if (layer.sand() == null || layer.silt() == null || layer.clay() == null) continue;
            int overlap = Math.min(layer.bottomCm(), toCm) - Math.max(layer.topCm(), fromCm);
            if (overlap <= 0) continue;
            weight += overlap;
            sand += layer.sand() * overlap;
            silt += layer.silt() * overlap;
            clay += layer.clay() * overlap;
        }
        if (weight == 0) return new Granulometry(null, null, null, 0);
        return new Granulometry(percent(sand / weight), percent(silt / weight), percent(clay / weight), (int) weight);
    }

    /** Total sand, or the sum of the five fractions when the total is missing and they are all there. */
    private static Double sand(Map<String, Object> analysis) {
        Double total = number(analysis.get("areia_total"));
        if (total != null) return total;
        double sum = 0;
        for (String fraction : List.of("areia_muito_grossa", "areia_grossa", "areia_media", "areia_fina", "areia_muito_fina")) {
            Double value = number(analysis.get(fraction));
            if (value == null) return null;
            sum += value;
        }
        return sum;
    }

    /** g/kg to %, 1 decimal. */
    static Double percent(Double gPerKg) {
        return gPerKg == null ? null : BrGeoValues.round(gPerKg / 10.0, 1);
    }

    private static Double mean(List<Double> values) {
        double sum = 0;
        int n = 0;
        for (Double v : values) {
            if (v == null) continue;
            sum += v;
            n++;
        }
        return n == 0 ? null : sum / n;
    }

    private static Double number(Object value) {
        return value instanceof Number n ? n.doubleValue() : null;
    }
}
