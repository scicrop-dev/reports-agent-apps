package com.infinitestack.reports.blocks.car;

import static com.infinitestack.reports.blocks.car.CarTables.*;

import java.math.BigDecimal;
import java.time.Duration;
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
 * {@code car-overlaps}: other CAR properties whose current geometry overlaps this property's.
 *
 * <p>The candidates come from the GiST index ({@code &&} then {@code ST_Intersects}) and are kept
 * only when the geometry is the current version of another property. The overlap is measured on
 * the geography (hectares) so that edge touches below {@code minOverlapHa} are ignored. Geometries
 * go through {@code ST_MakeValid} because one invalid neighbor would otherwise fail the whole Block.
 * A property removed from the SICAR is compared by the geometry of its last version
 * ({@link CarTables#VERSION_IN_EFFECT}); the neighbours are always current.
 */
@Component
public class CarOverlapsBlock implements ReportBlock {

    static final int LIMIT = 100;
    static final double DEFAULT_MIN_OVERLAP_HA = 0.01;
    static final Duration TIMEOUT = Duration.ofSeconds(30);
    /** Statement timeout, a little under the Block's own so the database stops before the Block gives up. */
    static final Duration QUERY_TIMEOUT = Duration.ofSeconds(25);

    static final String SQL = "WITH me AS ("
            + " SELECT i.id AS imovel_id, g.geom,"
            + " COALESCE(g.area_calc_ha, (ST_Area(g.geom::geography) / 1e4)::numeric) AS area_ha"
            + " FROM " + Q_IMOVEL + " i"
            + VERSION_IN_EFFECT
            + " JOIN " + Q_IMOVEL_GEOMETRIA + " g ON g.id = v.geometria_id"
            + " WHERE i.cod_imovel = ?),"
            + " hits AS ("
            + " SELECT v2.cod_imovel, v2.ind_status, s.descricao AS status_label,"
            + " ST_Area(ST_CollectionExtract(ST_Intersection(ST_MakeValid(g2.geom), ST_MakeValid(me.geom)), 3)::geography)"
            + " / 1e4 AS overlap_ha"
            + " FROM me"
            + " JOIN " + Q_IMOVEL_GEOMETRIA + " g2 ON g2.geom && me.geom AND ST_Intersects(g2.geom, me.geom)"
            + " AND g2.imovel_id <> me.imovel_id"
            + " JOIN " + Q_IMOVEL_VERSAO + " v2 ON v2.imovel_id = g2.imovel_id AND v2.valido_ate IS NULL"
            + " AND v2.geometria_id = g2.id"
            + " LEFT JOIN " + Q_STATUS_CADASTRO + " s ON s.codigo = v2.ind_status)"
            + " SELECT me.area_ha, hits.* FROM me LEFT JOIN hits ON hits.overlap_ha >= ?"
            + " ORDER BY hits.overlap_ha DESC NULLS LAST, hits.cod_imovel";

    private final BlockQueries queries;

    public CarOverlapsBlock(BlockQueries queries) {
        this.queries = queries;
    }

    @Override
    public BlockDefinition definition() {
        return new BlockDefinition("car-overlaps", "CAR overlaps",
                "Other current CAR properties whose geometry overlaps this property", "1.0.1",
                List.of(CarValues.COD_IMOVEL_INPUT,
                        BlockPort.optional("minOverlapHa", DataType.NUMBER,
                                "minimum overlap in hectares, default 0.01 (ignores edge touches)")),
                List.of(
                        BlockPort.required("overlaps", DataType.ARRAY,
                                "up to 100, largest first: {codImovel, status, statusLabel, overlapHa, percentOfProperty}"),
                        BlockPort.required("count", DataType.NUMBER, "current properties overlapping above the minimum"),
                        BlockPort.required("totalOverlapHa", DataType.NUMBER,
                                "sum of pairwise overlaps (the same area may count more than once)"),
                        BlockPort.required("truncated", DataType.BOOLEAN, "more than 100 overlaps")),
                List.of(
                        requirement(IMOVEL, "id", "cod_imovel", "removido_em"),
                        requirement(IMOVEL_VERSAO, "imovel_id", "cod_imovel", "valido_de", "valido_ate", "geometria_id",
                                "ind_status"),
                        requirement(IMOVEL_GEOMETRIA, "id", "imovel_id", "geom", "area_calc_ha"),
                        requirement(STATUS_CADASTRO, "codigo", "descricao")),
                TIMEOUT);
    }

    @Override
    public BlockDocs docs() {
        return new BlockDocs("CAR",
                "Finds other current CAR properties whose drawing overlaps this one, and how much. Overlaps "
                        + "usually mean a boundary dispute or a registration error.",
                List.of(
                        "Takes the current geometry of the property (the last one if it left the SICAR).",
                        "Searches the spatial index for other current properties that intersect it.",
                        "Measures each overlap in geodesic hectares and ignores touches smaller than minOverlapHa."),
                List.of(
                        "codImovel: the same report input as car-property.",
                        "minOverlapHa: a fixed value when tiny edge overlaps should be ignored more (default 0.01 ha)."),
                List.of(
                        "totalOverlapHa adds pairwise overlaps: the same piece of land can be counted more than once.",
                        "Only current registrations are searched as neighbours; removed properties are not.",
                        "A removed property is still compared, by its last geometry, against today's registrations."),
                "SICAR, gold.car_agent_app_imovel_geometria",
                "{{<node>.count}} sobreposicao(oes), {{<node>.totalOverlapHa}} ha");
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> inputs, ExecutionMetadata metadata) {
        String codImovel = CarValues.codImovel(inputs);
        double minOverlapHa = inputs.get("minOverlapHa") instanceof Number n ? n.doubleValue() : DEFAULT_MIN_OVERLAP_HA;
        List<Map<String, Object>> rows = queries.read(QUERY_TIMEOUT,
                jdbc -> jdbc.queryForList(SQL, codImovel, minOverlapHa));
        if (rows.isEmpty()) throw CarValues.notFound(codImovel);

        Double propertyAreaHa = CarValues.hectares((BigDecimal) rows.get(0).get("area_ha"));
        List<Map<String, Object>> overlaps = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        int count = 0;
        for (Map<String, Object> row : rows) {
            if (row.get("cod_imovel") == null) continue; // the property itself, with no overlap
            double overlapHa = CarValues.hectares(((Number) row.get("overlap_ha")).doubleValue());
            count++;
            total = total.add(BigDecimal.valueOf(overlapHa));
            if (overlaps.size() < LIMIT) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("codImovel", row.get("cod_imovel"));
                item.put("status", row.get("ind_status"));
                item.put("statusLabel", row.get("status_label"));
                item.put("overlapHa", overlapHa);
                item.put("percentOfProperty", CarValues.percent(overlapHa, propertyAreaHa));
                overlaps.add(item);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("overlaps", overlaps);
        out.put("count", count);
        out.put("totalOverlapHa", CarValues.hectares(total));
        out.put("truncated", count > LIMIT);
        return out;
    }
}
