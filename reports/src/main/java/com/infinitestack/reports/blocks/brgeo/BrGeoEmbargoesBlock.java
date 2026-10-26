package com.infinitestack.reports.blocks.brgeo;

import static com.infinitestack.reports.blocks.brgeo.BrGeoTables.EMBARGO;
import static com.infinitestack.reports.blocks.brgeo.BrGeoTables.EMBARGO_DECISION;
import static com.infinitestack.reports.blocks.brgeo.BrGeoTables.Q_EMBARGO;
import static com.infinitestack.reports.blocks.brgeo.BrGeoTables.Q_EMBARGO_DECISION;
import static com.infinitestack.reports.blocks.brgeo.BrGeoTables.requirement;

import java.math.BigDecimal;
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
 * {@code brgeo-embargoes}: IBAMA embargo terms that touch an area.
 *
 * <p>A term matches when its embargoed polygon intersects the area or, for the terms the IBAMA
 * published without a polygon, when the term's coordinate falls inside it. The status comes from
 * the term itself: cancelled ({@code sit_cancelado = 'S'}), lifted ({@code sit_desembargo = 'S'})
 * or active. No personal data: BR Geo does not store the names or documents of the embargoed.
 */
@Component
public class BrGeoEmbargoesBlock implements ReportBlock {

    static final int LIMIT = 100;

    private static final String COLUMNS = "e.seq_tad, e.num_tad, e.ser_tad, e.des_status_formulario, e.sit_cancelado,"
            + " e.sit_desembargo, e.tipo_desembargo, e.dat_embargo::date AS embargoed_at,"
            + " e.dat_desembargo::date AS lifted_at, e.dat_ult_alteracao::date AS changed_at, e.qtd_area_embargada,"
            + " e.tipo_area, e.deter_prodes, e.uf, e.cod_municipio, e.num_processo, e.num_auto_infracao";

    static final String SQL = "WITH " + BrGeoValues.INPUT_CTE + ","
            + " hits AS ("
            + " SELECT " + COLUMNS + ", 'polygon' AS matched_by,"
            + " ST_Area(ST_CollectionExtract(ST_Intersection(ST_MakeValid(e.geom), input.geom), 3)::geography) / 1e4 AS overlap_ha"
            + " FROM input JOIN " + Q_EMBARGO + " e ON e.valid_to IS NULL AND e.geom && input.geom"
            + " AND ST_Intersects(e.geom, input.geom)"
            + " UNION ALL"
            + " SELECT " + COLUMNS + ", 'point' AS matched_by, NULL AS overlap_ha"
            + " FROM input JOIN " + Q_EMBARGO + " e ON e.valid_to IS NULL AND e.geom IS NULL"
            + " AND e.num_longitude_tad BETWEEN ST_XMin(input.geom) AND ST_XMax(input.geom)"
            + " AND e.num_latitude_tad BETWEEN ST_YMin(input.geom) AND ST_YMax(input.geom)"
            + " AND ST_Intersects(input.geom, ST_SetSRID(ST_MakePoint(e.num_longitude_tad, e.num_latitude_tad), 4674)))"
            + " SELECT hits.*, (SELECT count(*) FROM " + Q_EMBARGO_DECISION + " d WHERE d.valid_to IS NULL"
            + " AND d.seq_tad = hits.seq_tad AND COALESCE(d.sit_cancelado, 'N') <> 'S') AS decisions"
            + " FROM hits ORDER BY hits.embargoed_at DESC NULLS LAST, hits.num_tad";

    private final BlockQueries queries;

    public BrGeoEmbargoesBlock(BlockQueries queries) {
        this.queries = queries;
    }

    @Override
    public BlockDefinition definition() {
        return new BlockDefinition("brgeo-embargoes", "IBAMA embargoes",
                "IBAMA embargo terms whose polygon (or point, when there is no polygon) falls in an area", "1.0.0",
                List.of(BrGeoValues.GEOMETRY_INPUT,
                        BlockPort.optional("includeInactive", DataType.BOOLEAN,
                                "also list lifted and cancelled terms; default true")),
                List.of(
                        BlockPort.required("embargoes", DataType.ARRAY,
                                "up to 100, active first, newest first: {term, series, seqTad, status, active, lifted, cancelled,"
                                        + " formStatus, embargoedAt, liftedAt, liftType, changedAt, areaType, deterProdes,"
                                        + " embargoedAreaHa, overlapHa, matchedBy, uf, municipalityIbge, processNumber,"
                                        + " infractionNotice, judicialDecisions}"),
                        BlockPort.required("count", DataType.NUMBER, "terms listed (after includeInactive)"),
                        BlockPort.required("activeCount", DataType.NUMBER, "active terms (not lifted, not cancelled)"),
                        BlockPort.required("hasActive", DataType.BOOLEAN, "at least one active term"),
                        BlockPort.required("activeOverlapHa", DataType.NUMBER,
                                "sum of the overlap of active polygons with the area (terms may overlap each other)"),
                        BlockPort.optional("lastEmbargoAt", DataType.DATE, "date of the most recent term listed"),
                        BlockPort.required("truncated", DataType.BOOLEAN, "more than 100 terms")),
                List.of(
                        requirement(EMBARGO, "seq_tad", "num_tad", "ser_tad", "des_status_formulario", "sit_cancelado",
                                "sit_desembargo", "tipo_desembargo", "dat_embargo", "dat_desembargo", "dat_ult_alteracao",
                                "qtd_area_embargada", "tipo_area", "deter_prodes", "uf", "cod_municipio", "num_processo",
                                "num_auto_infracao", "num_longitude_tad", "num_latitude_tad", "geom", "valid_to"),
                        requirement(EMBARGO_DECISION, "seq_tad", "sit_cancelado", "valid_to")),
                BrGeoValues.BLOCK_TIMEOUT);
    }

    @Override
    public BlockDocs docs() {
        return new BlockDocs("BR Geo",
                "Finds the environmental embargoes the IBAMA issued on an area: which terms, since when, "
                        + "whether they are still in force and how much of the area they cover.",
                List.of(
                        "Reads the current version of every embargo term (BR Geo updates the IBAMA open data daily).",
                        "Matches terms whose embargoed polygon intersects the area, measuring the overlap in geodesic hectares.",
                        "About half of the terms have no polygon, only a coordinate: those match when the point falls "
                                + "inside the area (matchedBy = point, overlapHa empty).",
                        "Classifies each term: cancelled, lifted (desembargado) or active.",
                        "Counts the judicial decisions linked to the term."),
                List.of(
                        "geometry: connect car-property-geometry.geometry.",
                        "includeInactive: set a fixed value false to list only terms in force.",
                        "Use hasActive in the template to show an alert ({{#<node>.hasActive}}...{{/<node>.hasActive}})."),
                List.of(
                        "No result does not prove there is no embargo: terms without polygon whose point is outside the "
                                + "area are not found, and state agencies (not IBAMA) issue embargoes too.",
                        "embargoedAreaHa is what the term declares; overlapHa is what falls inside this area.",
                        "The open data has typos (e.g. embargo dates in the future); they are shown as published."),
                "IBAMA, Termos de Embargo (dados abertos, CSV diario), loaded into gold.br_geo_agent_app_ibama_embargo",
                "{{#<node>.embargoes}}TAD {{term}} de {{embargoedAt}}{{#active}} (ativo){{/active}}<br>{{/<node>.embargoes}}");
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> inputs, ExecutionMetadata metadata) {
        String geoJson = BrGeoValues.geoJson(inputs);
        boolean includeInactive = !(inputs.get("includeInactive") instanceof Boolean b) || b;
        List<Map<String, Object>> rows = queries.read(BrGeoValues.QUERY_TIMEOUT, jdbc -> jdbc.queryForList(SQL, geoJson));

        List<Map<String, Object>> active = new ArrayList<>();
        List<Map<String, Object>> inactive = new ArrayList<>();
        double activeOverlapHa = 0;
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = item(row);
            if (Boolean.TRUE.equals(item.get("active"))) {
                active.add(item);
                if (item.get("overlapHa") instanceof Number n) activeOverlapHa += n.doubleValue();
            } else if (includeInactive) {
                inactive.add(item);
            }
        }
        List<Map<String, Object>> all = new ArrayList<>(active);
        all.addAll(inactive);

        String lastEmbargoAt = all.stream().map(i -> (String) i.get("embargoedAt")).filter(d -> d != null)
                .max(String::compareTo).orElse(null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("embargoes", all.size() > LIMIT ? new ArrayList<>(all.subList(0, LIMIT)) : all);
        out.put("count", all.size());
        out.put("activeCount", active.size());
        out.put("hasActive", !active.isEmpty());
        out.put("activeOverlapHa", BrGeoValues.round(activeOverlapHa, 4));
        out.put("lastEmbargoAt", lastEmbargoAt);
        out.put("truncated", all.size() > LIMIT);
        return out;
    }

    static Map<String, Object> item(Map<String, Object> row) {
        boolean cancelled = "S".equals(row.get("sit_cancelado"));
        boolean lifted = !cancelled && "S".equals(row.get("sit_desembargo"));
        boolean active = !cancelled && !lifted;
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("term", row.get("num_tad"));
        item.put("series", row.get("ser_tad"));
        item.put("seqTad", row.get("seq_tad"));
        item.put("status", cancelled ? "cancelled" : lifted ? "lifted" : "active");
        item.put("active", active);
        item.put("lifted", lifted);
        item.put("cancelled", cancelled);
        item.put("formStatus", row.get("des_status_formulario"));
        item.put("embargoedAt", text(row.get("embargoed_at")));
        item.put("liftedAt", text(row.get("lifted_at")));
        item.put("liftType", row.get("tipo_desembargo"));
        item.put("changedAt", text(row.get("changed_at")));
        item.put("areaType", row.get("tipo_area"));
        item.put("deterProdes", row.get("deter_prodes"));
        item.put("embargoedAreaHa", row.get("qtd_area_embargada") instanceof BigDecimal d ? BrGeoValues.hectares(d) : null);
        item.put("overlapHa", BrGeoValues.hectares((Number) row.get("overlap_ha")));
        item.put("matchedBy", row.get("matched_by"));
        item.put("uf", row.get("uf"));
        item.put("municipalityIbge", row.get("cod_municipio"));
        item.put("processNumber", row.get("num_processo"));
        item.put("infractionNotice", row.get("num_auto_infracao"));
        item.put("judicialDecisions", row.get("decisions") instanceof Number n ? n.intValue() : 0);
        return item;
    }

    /** A java.sql.Date (from {@code ::date}) as ISO text; the JDBC driver already formats it that way. */
    private static String text(Object value) {
        return value == null ? null : value.toString();
    }
}
