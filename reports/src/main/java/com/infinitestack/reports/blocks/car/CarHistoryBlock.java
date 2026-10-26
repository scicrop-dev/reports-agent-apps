package com.infinitestack.reports.blocks.car;

import static com.infinitestack.reports.blocks.car.CarTables.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.infinitestack.reports.engine.DataType;
import com.infinitestack.reports.engine.block.BlockDefinition;
import com.infinitestack.reports.engine.block.BlockPort;
import com.infinitestack.reports.engine.block.BlockQueries;
import com.infinitestack.reports.engine.block.ExecutionMetadata;
import com.infinitestack.reports.engine.block.ReportBlock;

/**
 * {@code car-history}: every version the CAR loads recorded for a property, oldest first.
 * A version marks {@code geometryChanged} when it points to a different geometry than the
 * previous one (geometries are stored once per property and hash, so the id is enough).
 */
@Component
public class CarHistoryBlock implements ReportBlock {

    static final String SQL = "SELECT v.valido_de, v.valido_ate, v.ind_status, s.descricao AS status_label,"
            + " c.descricao AS condicao, v.num_area, v.geometria_id"
            + " FROM " + Q_IMOVEL + " i"
            + " JOIN " + Q_IMOVEL_VERSAO + " v ON v.imovel_id = i.id"
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
                "Versions of a CAR property registration, oldest first", "1.0.0",
                List.of(CarValues.COD_IMOVEL_INPUT),
                List.of(
                        BlockPort.required("versions", DataType.ARRAY,
                                "oldest first: {validFrom, validTo, status, statusLabel, condition, declaredAreaHa,"
                                        + " geometryChanged}"),
                        BlockPort.required("count", DataType.NUMBER, "number of versions")),
                List.of(
                        requirement(IMOVEL, "id", "cod_imovel"),
                        requirement(IMOVEL_VERSAO, "imovel_id", "valido_de", "valido_ate", "ind_status", "condicao_id",
                                "num_area", "geometria_id"),
                        requirement(STATUS_CADASTRO, "codigo", "descricao"),
                        requirement(CONDICAO, "id", "descricao")),
                null);
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
            item.put("geometryId", rs.getLong("geometria_id"));
            return item;
        }, codImovel));
        Object previousGeometry = null;
        for (Map<String, Object> item : versions) {
            Object geometry = item.remove("geometryId");
            item.put("geometryChanged", previousGeometry != null && !previousGeometry.equals(geometry));
            previousGeometry = geometry;
        }
        if (versions.isEmpty()) throw CarValues.notFound(codImovel);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("versions", versions);
        out.put("count", versions.size());
        return out;
    }
}
