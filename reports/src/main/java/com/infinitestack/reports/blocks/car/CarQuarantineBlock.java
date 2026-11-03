package com.infinitestack.reports.blocks.car;

import static com.infinitestack.reports.blocks.car.CarTables.*;

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
 * {@code car-quarantine}: the SICAR records of a property that the car-agent-app set aside instead
 * of loading ({@code gold.car_agent_app_descartado}): a repeated code ({@code duplicado}, the
 * record kept is in the base), a geometry {@code ST_MakeValid} could not fix
 * ({@code geometria_invalida}) or a feature whose property is not loaded ({@code orfao}). The
 * loader sets the rows aside on every load, so the same record repeats once per publication.
 * A code with nothing set aside is not an error: the property may simply be clean.
 */
@Component
public class CarQuarantineBlock implements ReportBlock {

    static final int LIMIT = 100;

    static final Map<String, String> REASON_LABELS = Map.of(
            "duplicado", "Duplicate record (another record of the code was kept)",
            "geometria_invalida", "Invalid geometry that could not be fixed",
            "orfao", "Feature of a property not loaded");

    /** Most recent publication first; {@code total} counts every row, before the limit. */
    static final String ITEMS_SQL = "SELECT d.motivo, d.camada, c.nome AS camada_nome, d.detalhe,"
            + " k.data_publicacao, d.criado_em, COUNT(*) OVER () AS total"
            + " FROM " + Q_DESCARTADO + " d"
            + " LEFT JOIN " + Q_CARGA + " k ON k.id = d.carga_id"
            + " LEFT JOIN " + Q_CAMADA + " c ON c.codigo = d.camada"
            + " WHERE d.cod_imovel = ?"
            + " ORDER BY k.data_publicacao DESC NULLS LAST, d.id DESC"
            + " LIMIT ?";

    static final String BY_REASON_SQL = "SELECT d.motivo, COUNT(*) AS n, MAX(k.data_publicacao) AS ultima"
            + " FROM " + Q_DESCARTADO + " d"
            + " LEFT JOIN " + Q_CARGA + " k ON k.id = d.carga_id"
            + " WHERE d.cod_imovel = ?"
            + " GROUP BY d.motivo ORDER BY COUNT(*) DESC, d.motivo";

    private final BlockQueries queries;

    public CarQuarantineBlock(BlockQueries queries) {
        this.queries = queries;
    }

    @Override
    public BlockDefinition definition() {
        return new BlockDefinition("car-quarantine", "CAR quarantine",
                "SICAR records of a property that were set aside instead of loaded (duplicate, invalid geometry, orphan)",
                "1.0.0",
                List.of(CarValues.COD_IMOVEL_INPUT),
                List.of(
                        BlockPort.required("items", DataType.ARRAY,
                                "up to 100, most recent publication first: {reason, reasonLabel, layer, layerLabel, detail,"
                                        + " publishedAt, recordedAt}; reason is duplicado, geometria_invalida or orfao"),
                        BlockPort.required("count", DataType.NUMBER, "number of records set aside"),
                        BlockPort.required("truncated", DataType.BOOLEAN, "more than 100 records"),
                        BlockPort.required("hasItems", DataType.BOOLEAN, "at least one record was set aside"),
                        BlockPort.required("byReason", DataType.ARRAY,
                                "{reason, reasonLabel, count, lastPublishedAt}, most frequent first")),
                List.of(
                        requirement(DESCARTADO, "id", "carga_id", "camada", "cod_imovel", "motivo", "detalhe", "criado_em"),
                        requirement(CARGA, "id", "data_publicacao"),
                        requirement(CAMADA, "codigo", "nome")),
                null);
    }

    @Override
    public BlockDocs docs() {
        return new BlockDocs("CAR",
                "Shows the SICAR records of a property that were left out of the CAR base when it was loaded, and "
                        + "why: a repeated code, a drawing that could not be repaired or a layer feature of a property "
                        + "that is not in the base. It explains gaps in the other CAR sections.",
                List.of(
                        "Looks up the property code among the records set aside by the car-agent-app loads.",
                        "Returns each one with its reason, layer, detail (e.g. which record was kept instead), the "
                                + "publication it came from and when it was set aside.",
                        "Counts them per reason in byReason."),
                List.of(
                        "codImovel: the same report input as car-property; mark the node optional so a polygon report skips it.",
                        "hasItems wraps a warning section: {{#<node>.hasItems}}...{{/<node>.hasItems}}.",
                        "byReason goes to a short list; items to a table."),
                List.of(
                        "A duplicate means the SICAR file had the code twice; the record kept (usually the active one) is "
                                + "the one the other CAR Blocks show.",
                        "The same record is set aside again at each publication, so count can grow with the number of loads.",
                        "Does not fail for an unknown code: an empty list means nothing was set aside."),
                "SICAR, records set aside by car-agent-app in gold.car_agent_app_descartado",
                "{{#<node>.hasItems}}{{<node>.count}} registro(s) descartado(s) na carga{{/<node>.hasItems}}");
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> inputs, ExecutionMetadata metadata) {
        String codImovel = CarValues.codImovel(inputs);
        return queries.read(CarValues.QUERY_TIMEOUT, jdbc -> {
            long[] total = {0};
            List<Map<String, Object>> items = jdbc.query(ITEMS_SQL, (rs, n) -> {
                total[0] = rs.getLong("total");
                Map<String, Object> item = new LinkedHashMap<>();
                String reason = rs.getString("motivo");
                item.put("reason", reason);
                item.put("reasonLabel", REASON_LABELS.getOrDefault(reason, reason));
                item.put("layer", rs.getString("camada"));
                item.put("layerLabel", rs.getString("camada_nome"));
                item.put("detail", rs.getString("detalhe"));
                item.put("publishedAt", CarValues.isoDate(rs, "data_publicacao"));
                item.put("recordedAt", CarValues.isoDateTime(rs, "criado_em"));
                return item;
            }, codImovel, LIMIT);
            List<Map<String, Object>> byReason = new ArrayList<>(jdbc.query(BY_REASON_SQL, (rs, n) -> {
                Map<String, Object> item = new LinkedHashMap<>();
                String reason = rs.getString("motivo");
                item.put("reason", reason);
                item.put("reasonLabel", REASON_LABELS.getOrDefault(reason, reason));
                item.put("count", rs.getLong("n"));
                item.put("lastPublishedAt", CarValues.isoDate(rs, "ultima"));
                return item;
            }, codImovel));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("items", items);
            out.put("count", total[0]);
            out.put("truncated", total[0] > LIMIT);
            out.put("hasItems", total[0] > 0);
            out.put("byReason", byReason);
            return out;
        });
    }
}
