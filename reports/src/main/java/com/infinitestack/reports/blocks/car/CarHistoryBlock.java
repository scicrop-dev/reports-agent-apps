package com.infinitestack.reports.blocks.car;

import static com.infinitestack.reports.blocks.car.CarTables.*;

import java.math.BigDecimal;
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
 * {@code car-history}: every version the CAR loads recorded for a property, oldest first.
 * A version marks {@code geometryChanged} when it points to a different geometry than the
 * previous one (geometries are stored once per property and hash, so the id is enough), and
 * carries the area calculated from its geometry with the change from the previous version.
 * The change is between consecutive versions only: a version that changed only attributes keeps
 * the geometry, so its change is 0.
 */
@Component
public class CarHistoryBlock implements ReportBlock {

    static final String SQL = "SELECT v.valido_de, v.valido_ate, v.ind_status, s.descricao AS status_label,"
            + " c.descricao AS condicao, v.num_area, v.geometria_id, i.removido_em,"
            + " COALESCE(g.area_calc_ha, (ST_Area(g.geom::geography) / 1e4)::numeric) AS area_calc_ha"
            + " FROM " + Q_IMOVEL + " i"
            + " JOIN " + Q_IMOVEL_VERSAO + " v ON v.imovel_id = i.id"
            + " LEFT JOIN " + Q_IMOVEL_GEOMETRIA + " g ON g.id = v.geometria_id"
            + " LEFT JOIN " + Q_STATUS_CADASTRO + " s ON s.codigo = v.ind_status"
            + " LEFT JOIN " + Q_CONDICAO + " c ON c.id = v.condicao_id"
            + " WHERE i.cod_imovel = ?"
            + " ORDER BY v.valido_de";

    private final BlockQueries queries;

    public CarHistoryBlock(BlockQueries queries) {
        this.queries = queries;
    }

    @Override
    public BlockDefinition definition() {
        return new BlockDefinition("car-history", "CAR history",
                "Versions of a CAR property registration, oldest first", "1.1.0",
                List.of(CarValues.COD_IMOVEL_INPUT),
                List.of(
                        BlockPort.required("versions", DataType.ARRAY,
                                "oldest first: {validFrom, validTo, status, statusLabel, condition, declaredAreaHa,"
                                        + " calculatedAreaHa, areaChangeHa, areaChangePct, geometryChanged}; the changes compare"
                                        + " calculatedAreaHa with the previous version and are null on the first"),
                        BlockPort.required("count", DataType.NUMBER, "number of versions"),
                        BlockPort.optional("removedAt", DataType.DATE,
                                "date the property left SICAR (the last version ends then), null if still there")),
                List.of(
                        requirement(IMOVEL, "id", "cod_imovel", "removido_em"),
                        requirement(IMOVEL_VERSAO, "imovel_id", "valido_de", "valido_ate", "ind_status", "condicao_id",
                                "num_area", "geometria_id"),
                        requirement(IMOVEL_GEOMETRIA, "id", "geom", "area_calc_ha"),
                        requirement(STATUS_CADASTRO, "codigo", "descricao"),
                        requirement(CONDICAO, "id", "descricao")),
                null);
    }

    @Override
    public BlockDocs docs() {
        return new BlockDocs("CAR",
                "Lists every version of a CAR registration, from the first to the current one, with status, "
                        + "condition, declared area and whether the drawing changed.",
                List.of(
                        "Reads all versions of the property, oldest first.",
                        "Marks geometryChanged when a version points to a geometry different from the previous one.",
                        "Gives the area calculated from each version's geometry (geodesic) and its change from the previous "
                                + "version, in hectares and percent; a version that only changed attributes has a change of 0.",
                        "removedAt is filled when the property left the SICAR; its last version then ends on that date."),
                List.of(
                        "codImovel: the same report input as car-property.",
                        "versions goes to a table; count to a summary line.",
                        "areaChangeHa / areaChangePct show when the drawing grew or shrank between versions."),
                List.of(
                        "The history starts when car-agent-app began collecting the property, not at the first SICAR registration.",
                        "The change is between consecutive versions only, not from the first one; the declared area "
                                + "(declaredAreaHa) may change without the drawing and vice versa."),
                "SICAR, gold.car_agent_app_imovel_versao",
                "{{#<node>.versions}}{{validFrom}}: {{statusLabel}}<br>{{/<node>.versions}}");
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> inputs, ExecutionMetadata metadata) {
        String codImovel = CarValues.codImovel(inputs);
        List<Map<String, Object>> versions = queries.read(CarValues.QUERY_TIMEOUT, jdbc -> jdbc.query(SQL, (rs, n) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("validFrom", CarValues.isoDate(rs, "valido_de"));
            item.put("validTo", CarValues.isoDate(rs, "valido_ate"));
            item.put("status", rs.getString("ind_status"));
            item.put("statusLabel", rs.getString("status_label"));
            item.put("condition", rs.getString("condicao"));
            item.put("declaredAreaHa", CarValues.hectares(rs.getBigDecimal("num_area")));
            item.put("calculatedAreaHa", CarValues.hectares(rs.getBigDecimal("area_calc_ha")));
            item.put("geometryId", rs.getLong("geometria_id"));
            item.put("removedAt", CarValues.isoDate(rs, "removido_em"));
            return item;
        }, codImovel));
        if (versions.isEmpty()) throw CarValues.notFound(codImovel);
        Object removedAt = versions.get(0).get("removedAt");
        Object previousGeometry = null;
        Double previousArea = null;
        for (Map<String, Object> item : versions) {
            item.remove("removedAt");
            Object geometry = item.remove("geometryId");
            Double area = (Double) item.get("calculatedAreaHa");
            boolean first = previousGeometry == null;
            item.put("areaChangeHa", first ? null : areaChangeHa(previousArea, area));
            item.put("areaChangePct", first ? null : areaChangePct(previousArea, area));
            item.put("geometryChanged", !first && !previousGeometry.equals(geometry));
            previousGeometry = geometry;
            previousArea = area;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("versions", versions);
        out.put("count", versions.size());
        out.put("removedAt", removedAt);
        return out;
    }

    /** current - previous, 4 decimals; null when either is unknown. */
    static Double areaChangeHa(Double previousHa, Double currentHa) {
        if (previousHa == null || currentHa == null) return null;
        return CarValues.hectares(BigDecimal.valueOf(currentHa).subtract(BigDecimal.valueOf(previousHa)));
    }

    /** (current - previous) / previous x 100, 2 decimals; null when either is unknown or previous is not positive. */
    static Double areaChangePct(Double previousHa, Double currentHa) {
        if (previousHa == null || currentHa == null || previousHa <= 0) return null;
        return CarValues.round(BigDecimal.valueOf((currentHa - previousHa) * 100.0 / previousHa), 2);
    }
}
