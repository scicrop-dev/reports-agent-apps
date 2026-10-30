package com.infinitestack.reports.blocks.brgeo;

import static com.infinitestack.reports.blocks.brgeo.BrGeoTables.PEDOLOGY_UNIT;
import static com.infinitestack.reports.blocks.brgeo.BrGeoTables.Q_PEDOLOGY_UNIT;
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
 * {@code brgeo-soil-units}: the soil mapping units of the IBGE pedology map (1:250,000) that an
 * area crosses, with the share of the area in each, the share per SiBCS order and the soil
 * restrictions the map tells (steep relief, stony or rocky phases, Organossolos and Vertissolos).
 *
 * <p>Polygons of the same mapping unit (same symbol) are summed into one unit. The components of a
 * unit come in pieces: dBase cuts text at 254 characters and the IBGE spreads it over
 * {@code componente} and {@code componente_cont1..3}, losing characters at each cut, so the pieces
 * are listed as they are and never joined into a text that looks whole.
 */
@Component
public class BrGeoSoilUnitsBlock implements ReportBlock {

    static final int DEFAULT_LIMIT = 20;

    static final String SQL = "WITH " + BrGeoValues.INPUT_CTE + ","
            + " edition AS (SELECT max(edition) AS edition FROM " + Q_PEDOLOGY_UNIT + " WHERE valid_to IS NULL)"
            + " SELECT input.area_ha, edition.edition, u.id1, u.cod_simbolo, u.legenda, u.ordem, u.subordem, u.grande_grupo,"
            + " u.textura, u.relevo, u.pedregosidade, u.rochosidade, u.erosao,"
            + " u.componente, u.componente_cont1, u.componente_cont2, u.componente_cont3,"
            + " ST_Area(ST_CollectionExtract(ST_Intersection(u.geom, input.geom), 3)::geography) / 1e4 AS overlap_ha"
            + " FROM input CROSS JOIN edition"
            + " LEFT JOIN " + Q_PEDOLOGY_UNIT + " u ON u.valid_to IS NULL AND u.edition = edition.edition"
            + " AND u.geom && input.geom AND ST_Intersects(u.geom, input.geom)"
            + " ORDER BY overlap_ha DESC NULLS LAST, u.id1";

    /** The restrictions a unit can carry, in the order they are listed. */
    enum Restriction {
        STEEP_RELIEF("steep relief (strongly undulating, mountainous or escarpment)"),
        STONY("stony phase"),
        ROCKY("rocky phase"),
        ORGANOSOL("Organossolo (organic soil)"),
        VERTISOL("Vertissolo (shrink-swell clay)");

        final String label;

        Restriction(String label) {
            this.label = label;
        }
    }

    private final BlockQueries queries;

    public BrGeoSoilUnitsBlock(BlockQueries queries) {
        this.queries = queries;
    }

    @Override
    public BlockDefinition definition() {
        return new BlockDefinition("brgeo-soil-units", "Soil units of an area",
                "IBGE pedology map 1:250,000: the soil mapping units an area crosses, the share per soil order and the soil restrictions",
                "1.0.0",
                List.of(BrGeoValues.GEOMETRY_INPUT,
                        BlockPort.optional("limit", DataType.NUMBER, "how many units to list, largest share first; default 20")),
                List.of(
                        BlockPort.required("hasUnits", DataType.BOOLEAN, "the area crosses at least one mapped soil unit"),
                        BlockPort.required("units", DataType.ARRAY,
                                "largest share first: {symbol, legend, order, suborder, greatGroup, texture, relief, stoniness,"
                                        + " rockiness, erosion, components [text pieces], componentsCut, restrictions [codes], overlapHa, percent}"),
                        BlockPort.required("count", DataType.NUMBER, "number of units crossed (before the limit)"),
                        BlockPort.required("truncated", DataType.BOOLEAN, "more units than the limit"),
                        BlockPort.required("byOrder", DataType.ARRAY, "largest share first: {order, overlapHa, percent}"),
                        BlockPort.optional("mainOrder", DataType.STRING, "the soil order (SiBCS) with the largest share"),
                        BlockPort.required("flags", DataType.OBJECT,
                                "booleans: {steepRelief, stony, rocky, organosol, vertisol}, true when any unit crossed has it"),
                        BlockPort.required("restrictions", DataType.ARRAY,
                                "one per restriction found: {code, label, overlapHa, percent} (area of the units that carry it)"),
                        BlockPort.required("hasRestrictions", DataType.BOOLEAN, "at least one restriction found"),
                        BlockPort.required("areaHa", DataType.NUMBER, "area of the input geometry, hectares"),
                        BlockPort.optional("edition", DataType.NUMBER, "edition (year) of the IBGE pedology map used")),
                List.of(requirement(PEDOLOGY_UNIT, "edition", "id1", "cod_simbolo", "legenda", "ordem", "subordem", "grande_grupo",
                        "textura", "relevo", "pedregosidade", "rochosidade", "erosao", "componente", "componente_cont1",
                        "componente_cont2", "componente_cont3", "geom", "valid_to")),
                BrGeoValues.BLOCK_TIMEOUT);
    }

    @Override
    public BlockDocs docs() {
        return new BlockDocs("BR Geo",
                "Tells which soils the IBGE soil map shows in an area: the mapping units, the soil orders (Latossolo, "
                        + "Argissolo, Neossolo...) and the restrictions the map records (steep relief, stony or rocky phases, "
                        + "Organossolos and Vertissolos).",
                List.of(
                        "Takes the most recent edition of the IBGE pedology map 1:250,000.",
                        "Intersects the area with the mapping unit polygons and measures each intersection in geodesic hectares; "
                                + "polygons of the same unit (same symbol) are summed.",
                        "Sums the area per soil order (byOrder); mainOrder is the largest.",
                        "Flags a unit with steep relief when its relief mentions 'forte ondulado', 'montanhoso' or 'escarpado'; "
                                + "stony / rocky when the stoniness / rockiness phase is filled (and not 'não ...'); Organossolo and "
                                + "Vertissolo by the order."),
                List.of(
                        "geometry: connect car-property-geometry.geometry or report-area.geometry.",
                        "Use flags and hasRestrictions for warnings in the report; byOrder for a small table.",
                        "Pair it with brgeo-soil-profiles for measured texture near the area, and with brgeo-zarc-risk: "
                                + "the soil class (AD1 to AD6) of the ZARC depends on texture."),
                List.of(
                        "The map is 1:250,000: a unit is an association of soils over many square kilometres, not the soil of a "
                                + "field. Small areas get the unit around them.",
                        "components lists the pieces of the component text as the IBGE stores them: the dBase cut at 254 "
                                + "characters loses letters between pieces, so do not join them as one sentence.",
                        "The restrictions are those written in the map legend, not a field survey."),
                "IBGE, Pedologia 1:250.000 (unidades de mapeamento), loaded into gold.br_geo_agent_app_ibge_pedology_unit",
                "{{#<node>.hasUnits}}Ordem predominante: {{<node>.mainOrder}}"
                        + "{{#<node>.byOrder}}<br>{{order}}: {{percent}}%{{/<node>.byOrder}}"
                        + "{{#<node>.hasRestrictions}}<br>Restrições: {{#<node>.restrictions}}{{label}} ({{percent}}%); {{/<node>.restrictions}}{{/<node>.hasRestrictions}}"
                        + "{{/<node>.hasUnits}}");
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> inputs, ExecutionMetadata metadata) {
        String geoJson = BrGeoValues.geoJson(inputs);
        int limit = inputs.get("limit") instanceof Number n ? Math.max(1, n.intValue()) : DEFAULT_LIMIT;
        List<Map<String, Object>> rows = queries.read(BrGeoValues.QUERY_TIMEOUT, jdbc -> jdbc.queryForList(SQL, geoJson));
        Double measured = rows.isEmpty() ? null : BrGeoValues.hectares((Number) rows.get(0).get("area_ha"));
        final double areaHa = measured == null ? 0.0 : measured;

        Map<String, Map<String, Object>> bySymbol = new LinkedHashMap<>();
        Map<String, Double> rawOverlap = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            if (row.get("id1") == null) continue;
            double ha = row.get("overlap_ha") instanceof Number n ? n.doubleValue() : 0.0;
            if (ha <= 0) continue; // touches the area only along a border
            String symbol = text(row.get("cod_simbolo"));
            String key = symbol != null ? symbol : "id1:" + row.get("id1");
            bySymbol.computeIfAbsent(key, k -> unit(row));
            rawOverlap.merge(key, ha, Double::sum);
        }

        List<Map<String, Object>> units = new ArrayList<>();
        Map<String, Double> orderHa = new LinkedHashMap<>();
        Map<Restriction, Double> restrictionHa = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> entry : bySymbol.entrySet()) {
            Map<String, Object> unit = entry.getValue();
            double ha = rawOverlap.get(entry.getKey());
            unit.put("overlapHa", BrGeoValues.hectares(ha));
            unit.put("percent", BrGeoValues.percent(ha, areaHa));
            units.add(unit);
            String order = unit.get("order") == null ? "(not classified)" : (String) unit.get("order");
            orderHa.merge(order, ha, Double::sum);
            @SuppressWarnings("unchecked")
            List<String> codes = (List<String>) unit.get("restrictions");
            for (String code : codes) restrictionHa.merge(Restriction.valueOf(code), ha, Double::sum);
        }
        units.sort((a, b) -> Double.compare((Double) b.get("overlapHa"), (Double) a.get("overlapHa")));

        List<Map<String, Object>> byOrder = new ArrayList<>();
        orderHa.entrySet().stream().sorted((a, b) -> Double.compare(b.getValue(), a.getValue())).forEach(e -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("order", e.getKey());
            item.put("overlapHa", BrGeoValues.hectares(e.getValue()));
            item.put("percent", BrGeoValues.percent(e.getValue(), areaHa));
            byOrder.add(item);
        });

        List<Map<String, Object>> restrictions = new ArrayList<>();
        Map<String, Object> flags = new LinkedHashMap<>();
        for (Restriction r : Restriction.values()) {
            Double ha = restrictionHa.get(r);
            flags.put(flagName(r), ha != null);
            if (ha == null) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", r.name());
            item.put("label", r.label);
            item.put("overlapHa", BrGeoValues.hectares(ha));
            item.put("percent", BrGeoValues.percent(ha, areaHa));
            restrictions.add(item);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("hasUnits", !units.isEmpty());
        out.put("units", units.size() > limit ? new ArrayList<>(units.subList(0, limit)) : units);
        out.put("count", units.size());
        out.put("truncated", units.size() > limit);
        out.put("byOrder", byOrder);
        out.put("mainOrder", byOrder.isEmpty() ? null : byOrder.get(0).get("order"));
        out.put("flags", flags);
        out.put("restrictions", restrictions);
        out.put("hasRestrictions", !restrictions.isEmpty());
        out.put("areaHa", areaHa);
        out.put("edition", rows.isEmpty() ? null : rows.get(0).get("edition"));
        return out;
    }

    private static Map<String, Object> unit(Map<String, Object> row) {
        Map<String, Object> unit = new LinkedHashMap<>();
        unit.put("symbol", text(row.get("cod_simbolo")));
        unit.put("legend", text(row.get("legenda")));
        unit.put("order", text(row.get("ordem")));
        unit.put("suborder", text(row.get("subordem")));
        unit.put("greatGroup", text(row.get("grande_grupo")));
        unit.put("texture", text(row.get("textura")));
        unit.put("relief", text(row.get("relevo")));
        unit.put("stoniness", text(row.get("pedregosidade")));
        unit.put("rockiness", text(row.get("rochosidade")));
        unit.put("erosion", text(row.get("erosao")));
        List<String> components = components(row.get("componente"), row.get("componente_cont1"),
                row.get("componente_cont2"), row.get("componente_cont3"));
        unit.put("components", components);
        unit.put("componentsCut", components.size() > 1);
        unit.put("restrictions", restrictions(text(row.get("ordem")), text(row.get("relevo")),
                text(row.get("pedregosidade")), text(row.get("rochosidade"))).stream().map(Enum::name).toList());
        return unit;
    }

    /** The non-empty pieces of the component text, in order, each as stored (never joined). */
    static List<String> components(Object... pieces) {
        List<String> out = new ArrayList<>();
        for (Object piece : pieces) {
            String text = text(piece);
            if (text != null) out.add(text);
        }
        return out;
    }

    /** The restrictions a unit carries, from its order, relief and stoniness / rockiness phases. */
    static List<Restriction> restrictions(String order, String relief, String stoniness, String rockiness) {
        List<Restriction> out = new ArrayList<>();
        String r = BrazilianStates.fold(relief);
        if (r.contains("forteondulado") || r.contains("montanhoso") || r.contains("escarpado")) out.add(Restriction.STEEP_RELIEF);
        if (phasePresent(stoniness)) out.add(Restriction.STONY);
        if (phasePresent(rockiness)) out.add(Restriction.ROCKY);
        String o = BrazilianStates.fold(order);
        if (o.contains("organossolo")) out.add(Restriction.ORGANOSOL);
        if (o.contains("vertissolo")) out.add(Restriction.VERTISOL);
        return out;
    }

    /** A phase field filled with something other than a negation ("não pedregosa", "sem ..."). */
    static boolean phasePresent(String phase) {
        String folded = BrazilianStates.fold(phase);
        if (folded.isEmpty()) return false;
        return !(folded.startsWith("nao") || folded.startsWith("sem") || folded.equals("ausente"));
    }

    private static String flagName(Restriction r) {
        return switch (r) {
            case STEEP_RELIEF -> "steepRelief";
            case STONY -> "stony";
            case ROCKY -> "rocky";
            case ORGANOSOL -> "organosol";
            case VERTISOL -> "vertisol";
        };
    }

    private static String text(Object value) {
        return value == null || value.toString().isBlank() ? null : value.toString().trim();
    }
}
