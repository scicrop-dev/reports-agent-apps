package com.infinitestack.reports.blocks.car;

import static com.infinitestack.reports.blocks.car.CarTables.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.stereotype.Component;

import com.infinitestack.reports.engine.DataType;
import com.infinitestack.reports.engine.block.BlockDefinition;
import com.infinitestack.reports.engine.block.BlockPort;
import com.infinitestack.reports.engine.block.BlockQueries;
import com.infinitestack.reports.engine.block.ExecutionMetadata;
import com.infinitestack.reports.engine.block.ReportBlock;

/**
 * {@code car-environmental-layers}: the land use and cover a property declared in SICAR, per
 * layer (APP, native vegetation, legal reserve...), from its current features.
 *
 * <p>SQL only groups features by layer and theme; the area rule lives in {@link #buildLayers} so
 * it can be tested without a database: a layer's area is its aggregate theme's
 * ({@code tema.agregado}, e.g. {@code APP_TOTAL}) when present, else the sum of its themes. The
 * aggregate is never added to its parts, and the parts themselves may overlap (an APP along a river
 * is also an APP in native vegetation), which is why the aggregate wins.
 */
@Component
public class CarEnvironmentalLayersBlock implements ReportBlock {

    static final String PROPERTY_SQL = "SELECT i.id, v.num_area FROM " + Q_IMOVEL + " i"
            + " JOIN " + Q_IMOVEL_VERSAO + " v ON v.imovel_id = i.id AND v.valido_ate IS NULL"
            + " WHERE i.cod_imovel = ?";

    /** Current features of the property grouped by layer and theme; AREA_IMOVEL is the property itself. */
    static final String THEMES_SQL = "SELECT f.camada, c.nome AS camada_nome, c.ordem, f.cod_tema,"
            + " t.nome AS tema_nome, t.agregado, COUNT(*) AS n, SUM(f.num_area) AS area"
            + " FROM " + Q_FEICAO + " f"
            + " JOIN " + Q_CAMADA + " c ON c.codigo = f.camada"
            + " JOIN " + Q_TEMA + " t ON t.codigo = f.cod_tema"
            + " WHERE f.imovel_id = ? AND f.valido_ate IS NULL AND f.camada <> 'AREA_IMOVEL'"
            + " GROUP BY f.camada, c.nome, c.ordem, f.cod_tema, t.nome, t.agregado";

    /** Summary key of each layer's area, in the contract's order. */
    static final Map<String, String> SUMMARY_KEYS = new LinkedHashMap<>();
    static {
        SUMMARY_KEYS.put("APPS", "appHa");
        SUMMARY_KEYS.put("RESERVA_LEGAL", "legalReserveHa");
        SUMMARY_KEYS.put("VEGETACAO_NATIVA", "nativeVegetationHa");
        SUMMARY_KEYS.put("AREA_CONSOLIDADA", "consolidatedHa");
        SUMMARY_KEYS.put("AREA_POUSIO", "fallowHa");
        SUMMARY_KEYS.put("HIDROGRAFIA", "hydrographyHa");
        SUMMARY_KEYS.put("USO_RESTRITO", "restrictedUseHa");
        SUMMARY_KEYS.put("SERVIDAO_ADMINISTRATIVA", "administrativeEasementHa");
    }

    /** One (layer, theme) group of current features. {@code areaHa} is null when no feature declares an area. */
    record ThemeRow(String layer, String layerName, int layerOrder, String theme, String themeName,
                    boolean aggregate, long count, BigDecimal areaHa) {}

    private final BlockQueries queries;

    public CarEnvironmentalLayersBlock(BlockQueries queries) {
        this.queries = queries;
    }

    @Override
    public BlockDefinition definition() {
        return new BlockDefinition("car-environmental-layers", "CAR environmental layers",
                "Land use and cover declared in SICAR per layer (APP, native vegetation, legal reserve...)", "1.0.0",
                List.of(CarValues.COD_IMOVEL_INPUT),
                List.of(
                        BlockPort.required("layers", DataType.ARRAY,
                                "one item per layer present, by layer order: {layer, layerName, declaredAreaHa,"
                                        + " percentOfProperty, themes: [{theme, themeName, count, declaredAreaHa}]}"),
                        BlockPort.required("summary", DataType.OBJECT,
                                "{propertyAreaHa, appHa, legalReserveHa, nativeVegetationHa, consolidatedHa, fallowHa,"
                                        + " hydrographyHa, restrictedUseHa, administrativeEasementHa, appPercent,"
                                        + " legalReservePercent, nativeVegetationPercent}; absent layer = 0"),
                        BlockPort.required("hasLegalReserve", DataType.BOOLEAN, "has a current legal reserve feature")),
                List.of(
                        requirement(IMOVEL, "id", "cod_imovel"),
                        requirement(IMOVEL_VERSAO, "imovel_id", "valido_ate", "num_area"),
                        requirement(FEICAO, "imovel_id", "camada", "cod_tema", "num_area", "valido_ate"),
                        requirement(CAMADA, "codigo", "nome", "ordem"),
                        requirement(TEMA, "codigo", "nome", "agregado")),
                null);
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> inputs, ExecutionMetadata metadata) {
        String codImovel = CarValues.codImovel(inputs);
        return queries.read(CarValues.QUERY_TIMEOUT, jdbc -> {
            List<Map<String, Object>> property = jdbc.queryForList(PROPERTY_SQL, codImovel);
            if (property.isEmpty()) throw CarValues.notFound(codImovel);
            Long imovelId = ((Number) property.get(0).get("id")).longValue();
            Double propertyAreaHa = CarValues.hectares((BigDecimal) property.get(0).get("num_area"));
            List<ThemeRow> rows = jdbc.query(THEMES_SQL, (rs, n) -> new ThemeRow(
                    rs.getString("camada"), rs.getString("camada_nome"), rs.getInt("ordem"),
                    rs.getString("cod_tema"), rs.getString("tema_nome"), rs.getBoolean("agregado"),
                    rs.getLong("n"), rs.getBigDecimal("area")), imovelId);
            return result(rows, propertyAreaHa);
        });
    }

    /** The Block's outputs from the grouped rows and the property's declared area. */
    static Map<String, Object> result(List<ThemeRow> rows, Double propertyAreaHa) {
        List<Map<String, Object>> layers = buildLayers(rows, propertyAreaHa);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("layers", layers);
        out.put("summary", buildSummary(layers, propertyAreaHa));
        out.put("hasLegalReserve", layers.stream().anyMatch(l -> "RESERVA_LEGAL".equals(l.get("layer"))));
        return out;
    }

    /** Groups theme rows into layers ordered by layer order; themes inside a layer by code. */
    static List<Map<String, Object>> buildLayers(List<ThemeRow> rows, Double propertyAreaHa) {
        Map<String, List<ThemeRow>> byLayer = new TreeMap<>();
        for (ThemeRow row : rows) byLayer.computeIfAbsent(row.layer(), k -> new ArrayList<>()).add(row);

        List<List<ThemeRow>> ordered = new ArrayList<>(byLayer.values());
        ordered.sort(Comparator.comparingInt((List<ThemeRow> l) -> l.get(0).layerOrder())
                .thenComparing(l -> l.get(0).layer()));

        List<Map<String, Object>> layers = new ArrayList<>();
        for (List<ThemeRow> themes : ordered) {
            themes.sort(Comparator.comparing(ThemeRow::theme));
            double areaHa = CarValues.hectares(layerArea(themes));
            List<Map<String, Object>> themeItems = new ArrayList<>();
            for (ThemeRow t : themes) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("theme", t.theme());
                item.put("themeName", t.themeName());
                item.put("count", t.count());
                item.put("declaredAreaHa", CarValues.hectares(orZero(t.areaHa())));
                themeItems.add(item);
            }
            Map<String, Object> layer = new LinkedHashMap<>();
            layer.put("layer", themes.get(0).layer());
            layer.put("layerName", themes.get(0).layerName());
            layer.put("declaredAreaHa", areaHa);
            layer.put("percentOfProperty", CarValues.percent(areaHa, propertyAreaHa));
            layer.put("themes", themeItems);
            layers.add(layer);
        }
        return layers;
    }

    /** Area of one layer: its aggregate themes when there is one, else the sum of all its themes. */
    static double layerArea(List<ThemeRow> themes) {
        boolean hasAggregate = themes.stream().anyMatch(ThemeRow::aggregate);
        BigDecimal total = BigDecimal.ZERO;
        for (ThemeRow t : themes) {
            if (!hasAggregate || t.aggregate()) total = total.add(orZero(t.areaHa()));
        }
        return total.doubleValue();
    }

    static Map<String, Object> buildSummary(List<Map<String, Object>> layers, Double propertyAreaHa) {
        Map<String, Double> areaByLayer = new LinkedHashMap<>();
        for (Map<String, Object> layer : layers) {
            areaByLayer.put((String) layer.get("layer"), (Double) layer.get("declaredAreaHa"));
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("propertyAreaHa", propertyAreaHa == null ? 0.0 : propertyAreaHa);
        SUMMARY_KEYS.forEach((layer, key) -> summary.put(key, areaByLayer.getOrDefault(layer, 0.0)));
        summary.put("appPercent", CarValues.percent((Double) summary.get("appHa"), propertyAreaHa));
        summary.put("legalReservePercent", CarValues.percent((Double) summary.get("legalReserveHa"), propertyAreaHa));
        summary.put("nativeVegetationPercent",
                CarValues.percent((Double) summary.get("nativeVegetationHa"), propertyAreaHa));
        return summary;
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
