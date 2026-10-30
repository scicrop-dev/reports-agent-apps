package com.infinitestack.reports.blocks.car;

import static com.infinitestack.reports.blocks.car.CarTables.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.infinitestack.reports.engine.DataType;
import com.infinitestack.reports.engine.block.BlockDefinition;
import com.infinitestack.reports.engine.block.BlockDocs;
import com.infinitestack.reports.engine.block.BlockPort;
import com.infinitestack.reports.engine.block.BlockQueries;
import com.infinitestack.reports.engine.block.ExecutionMetadata;
import com.infinitestack.reports.engine.block.ReportBlock;

/**
 * {@code car-data-reference}: which SICAR publication the CAR data of a state comes from, per
 * layer. Every layer of every state is a separate SICAR file with its own publication date; the
 * car-agent-app records one {@code carga} per file loaded. Only loads with
 * {@code status = 'concluida'} count (a load still running or failed did not change the data).
 * Every layer of {@code camada} is listed, the ones never loaded for the state with null dates.
 */
@Component
public class CarDataReferenceBlock implements ReportBlock {

    private static final Pattern UF = Pattern.compile("[A-Z]{2}");

    /** Every layer, with the latest completed load of the state for it (null when none). */
    static final String SQL = "SELECT c.codigo AS camada, c.nome AS camada_nome, k.data_publicacao, k.concluida_em"
            + " FROM " + Q_CAMADA + " c"
            + " LEFT JOIN LATERAL (SELECT lk.data_publicacao, lk.concluida_em FROM " + Q_CARGA + " lk"
            + " WHERE lk.uf = ? AND lk.camada = c.codigo AND lk.status = 'concluida'"
            + " ORDER BY lk.data_publicacao DESC, lk.concluida_em DESC NULLS LAST LIMIT 1) k ON true"
            + " ORDER BY c.ordem, c.codigo";

    private final BlockQueries queries;

    public CarDataReferenceBlock(BlockQueries queries) {
        this.queries = queries;
    }

    @Override
    public BlockDefinition definition() {
        return new BlockDefinition("car-data-reference", "CAR data reference",
                "SICAR publication date of the CAR data of a state, per layer", "1.0.0",
                List.of(
                        BlockPort.optional("uf", DataType.STRING, "state (UF, e.g. MT); used when there is no codImovel"),
                        BlockPort.optional("codImovel", DataType.STRING,
                                "SICAR property code; its first two letters are the UF and win over 'uf'")),
                List.of(
                        BlockPort.required("uf", DataType.STRING, "the state read"),
                        BlockPort.required("layers", DataType.ARRAY,
                                "every layer, by layer order: {layer, layerLabel, loaded, publishedAt, loadedAt};"
                                        + " publishedAt / loadedAt are null for a layer never loaded"),
                        BlockPort.optional("latestPublishedAt", DataType.DATE, "most recent publication among the loaded layers"),
                        BlockPort.optional("oldestPublishedAt", DataType.DATE, "oldest publication among the loaded layers"),
                        BlockPort.required("hasData", DataType.BOOLEAN, "at least one layer was loaded for the state"),
                        BlockPort.required("complete", DataType.BOOLEAN, "every layer was loaded for the state")),
                List.of(
                        requirement(CAMADA, "codigo", "nome", "ordem"),
                        requirement(CARGA, "uf", "camada", "data_publicacao", "status", "concluida_em")),
                null);
    }

    @Override
    public BlockDocs docs() {
        return new BlockDocs("CAR",
                "Tells which SICAR publication the CAR data of a state comes from: the date of each layer "
                        + "(property boundaries, APP, legal reserve...) and when it was loaded. It is the "
                        + "\"data reference date\" line of a CAR report.",
                List.of(
                        "Takes the state from uf, or from the first two letters of codImovel.",
                        "For each layer, finds the latest load of that state's file that completed, and returns its "
                                + "SICAR publication date (publishedAt) and when the load finished (loadedAt).",
                        "latestPublishedAt and oldestPublishedAt summarise the loaded layers; complete says whether "
                                + "every layer was loaded."),
                List.of(
                        "codImovel: the same report input as car-property; or uf from car-property.uf.",
                        "Put oldestPublishedAt / latestPublishedAt in the report footer as the data reference date.",
                        "layers goes to a table when the per-layer dates matter."),
                List.of(
                        "Layers of a state are published on different dates: the report data mixes them.",
                        "The state is the one of the SICAR file. A few properties coded with a neighbouring state "
                                + "(border municipalities) come in another state's file; their date is that file's.",
                        "Fails when neither uf nor codImovel is given, or when they name different states."),
                "SICAR, load records of car-agent-app in gold.car_agent_app_carga",
                "Base SICAR de {{<node>.uf}} publicada em {{<node>.oldestPublishedAt}} a {{<node>.latestPublishedAt}}");
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> inputs, ExecutionMetadata metadata) {
        String uf = uf(inputs);
        List<Map<String, Object>> layers = queries.read(CarValues.QUERY_TIMEOUT, jdbc -> jdbc.query(SQL, (rs, n) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("layer", rs.getString("camada"));
            item.put("layerLabel", rs.getString("camada_nome"));
            item.put("publishedAt", CarValues.isoDate(rs, "data_publicacao"));
            item.put("loadedAt", CarValues.isoDateTime(rs, "concluida_em"));
            item.put("loaded", item.get("publishedAt") != null);
            return item;
        }, uf));
        return result(uf, layers);
    }

    static Map<String, Object> result(String uf, List<Map<String, Object>> layers) {
        List<String> dates = new ArrayList<>();
        for (Map<String, Object> layer : layers) {
            if (layer.get("publishedAt") instanceof String date) dates.add(date);
        }
        dates.sort(null); // ISO dates sort as text
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("uf", uf);
        out.put("layers", layers);
        out.put("latestPublishedAt", dates.isEmpty() ? null : dates.get(dates.size() - 1));
        out.put("oldestPublishedAt", dates.isEmpty() ? null : dates.get(0));
        out.put("hasData", !dates.isEmpty());
        out.put("complete", !layers.isEmpty() && dates.size() == layers.size());
        return out;
    }

    /**
     * The UF of the property's SICAR file (prefix of {@code codImovel}) or, without a code, {@code uf}. The
     * code wins: a property on a state border can lie mostly in the other state, so a report can wire both
     * (the code for a CAR property, the main state of the area for a polygon).
     */
    static String uf(Map<String, Object> inputs) {
        String uf = text(inputs.get("uf"));
        String code = text(inputs.get("codImovel"));
        String fromCode = code == null ? null : code.substring(0, Math.min(2, code.length()));
        if (uf == null && fromCode == null) throw new IllegalArgumentException("give a state (uf) or a CAR code (codImovel)");
        String result = fromCode != null ? fromCode : uf;
        if (!UF.matcher(result).matches()) throw new IllegalArgumentException("invalid state (UF): " + result);
        return result;
    }

    private static String text(Object value) {
        return value instanceof String s && !s.isBlank() ? s.trim().toUpperCase() : null;
    }
}
