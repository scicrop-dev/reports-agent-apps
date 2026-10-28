package com.infinitestack.reports.blocks.brgeo;

import static com.infinitestack.reports.blocks.brgeo.BrGeoTables.PAM_PRODUCT;
import static com.infinitestack.reports.blocks.brgeo.BrGeoTables.PAM_VALUE;
import static com.infinitestack.reports.blocks.brgeo.BrGeoTables.Q_PAM_PRODUCT;
import static com.infinitestack.reports.blocks.brgeo.BrGeoTables.Q_PAM_VALUE;
import static com.infinitestack.reports.blocks.brgeo.BrGeoTables.requirement;

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
 * {@code brgeo-crop-production}: what a municipality grows, from the IBGE PAM (Producao Agricola
 * Municipal, SIDRA table 5457): planted and harvested area, quantity, yield and value per crop for
 * one year (the latest available by default).
 */
@Component
public class BrGeoCropProductionBlock implements ReportBlock {

    static final int TABLE = 5457;
    static final int PLANTED = 8331;
    static final int HARVESTED = 216;
    static final int QUANTITY = 214;
    static final int YIELD = 112;
    static final int VALUE = 215;
    /** The grand total of the table, used for the totals and the shares, never listed as a crop. */
    static final int TOTAL_PRODUCT = 0;
    static final int DEFAULT_LIMIT = 15;

    static final String SQL = "WITH y AS (SELECT COALESCE(CAST(? AS integer),"
            + " (SELECT max(year) FROM " + Q_PAM_VALUE + " WHERE table_id = " + TABLE + " AND cd_mun = ? AND valid_to IS NULL)) AS year)"
            + " SELECT y.year, v.product_id, p.name, v.variable_id, v.value, v.value_flag, v.unit"
            + " FROM y JOIN " + Q_PAM_VALUE + " v ON v.table_id = " + TABLE + " AND v.year = y.year AND v.cd_mun = ?"
            + " AND v.valid_to IS NULL AND v.variable_id IN (" + PLANTED + ", " + HARVESTED + ", " + QUANTITY + ", " + YIELD + ", " + VALUE + ")"
            + " LEFT JOIN " + Q_PAM_PRODUCT + " p ON p.table_id = v.table_id AND p.product_id = v.product_id AND p.valid_to IS NULL"
            + " ORDER BY v.product_id";

    private final BlockQueries queries;

    public BrGeoCropProductionBlock(BlockQueries queries) {
        this.queries = queries;
    }

    @Override
    public BlockDefinition definition() {
        return new BlockDefinition("brgeo-crop-production", "Crop production of a municipality",
                "IBGE PAM: planted and harvested area, quantity, yield and value per crop in a municipality, for one year",
                "1.0.0",
                List.of(
                        BlockPort.required("municipalityIbge", DataType.NUMBER,
                                "IBGE municipality code (7 digits); connect car-property.municipalityIbge or brgeo-municipalities.mainIbgeCode"),
                        BlockPort.optional("year", DataType.NUMBER, "PAM year; default the latest available"),
                        BlockPort.optional("limit", DataType.NUMBER, "how many crops to list, by harvested area; default 15")),
                List.of(
                        BlockPort.optional("year", DataType.NUMBER, "the PAM year used; empty when the municipality has no data"),
                        BlockPort.required("crops", DataType.ARRAY,
                                "largest harvested area first: {product, plantedHa, harvestedHa, quantity, quantityUnit,"
                                        + " yieldKgHa, valueThousandBrl, shareOfHarvestedPercent}"),
                        BlockPort.required("count", DataType.NUMBER, "crops with harvested area in the year"),
                        BlockPort.required("totalHarvestedHa", DataType.NUMBER, "harvested area of all crops, hectares"),
                        BlockPort.required("totalValueThousandBrl", DataType.NUMBER, "production value of all crops, thousand BRL"),
                        BlockPort.required("truncated", DataType.BOOLEAN, "more crops than the limit"),
                        BlockPort.optional("uf", DataType.STRING,
                                "state (abbreviation) of the municipality, from its IBGE code; connect it to brgeo-crop-progress.uf")),
                List.of(
                        requirement(PAM_VALUE, "table_id", "year", "cd_mun", "variable_id", "product_id", "value",
                                "value_flag", "unit", "valid_to"),
                        requirement(PAM_PRODUCT, "table_id", "product_id", "name", "valid_to")),
                Duration.ofSeconds(20));
    }

    @Override
    public BlockDocs docs() {
        return new BlockDocs("BR Geo",
                "Shows the agricultural profile of a municipality: which temporary and permanent crops it "
                        + "grows, on how many hectares, how much it produces and what it is worth.",
                List.of(
                        "Takes the latest year the IBGE published for the municipality (or the year given).",
                        "Reads SIDRA table 5457 (temporary and permanent crops) for the five variables: planted area, "
                                + "harvested area, quantity, yield and value.",
                        "Lists the crops with the largest harvested area; totals come from the table's own total line.",
                        "Subtotal lines such as 'Cafe (em grao) Total' are skipped so a crop is not counted twice."),
                List.of(
                        "municipalityIbge: connect car-property.municipalityIbge (the municipality declared in the CAR) "
                                + "or brgeo-municipalities.mainIbgeCode (where most of the area is).",
                        "year: leave empty for the latest year, or set a fixed value to compare reports over time."),
                List.of(
                        "It describes the municipality, not the property: it is context, not what is grown in the area.",
                        "Values the IBGE keeps confidential or does not have ('X', '...', '-') come empty.",
                        "Quantity units change by crop (tons, thousand fruits); read quantityUnit."),
                "IBGE, Producao Agricola Municipal (SIDRA 5457), loaded into gold.br_geo_agent_app_ibge_pam_value",
                "{{#<node>.crops}}{{product}}: {{harvestedHa}} ha colhidos<br>{{/<node>.crops}}");
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> inputs, ExecutionMetadata metadata) {
        long ibge = ((Number) inputs.get("municipalityIbge")).longValue();
        String cdMun = String.valueOf(ibge);
        if (cdMun.length() != 7) throw new IllegalArgumentException("municipalityIbge must have 7 digits, got " + cdMun);
        Integer year = inputs.get("year") instanceof Number n ? n.intValue() : null;
        int limit = inputs.get("limit") instanceof Number n ? Math.max(1, n.intValue()) : DEFAULT_LIMIT;

        List<Map<String, Object>> rows = queries.read(Duration.ofSeconds(15),
                jdbc -> jdbc.queryForList(SQL, year, cdMun, cdMun));

        Map<Integer, Map<String, Object>> byProduct = new LinkedHashMap<>();
        Object usedYear = null;
        for (Map<String, Object> row : rows) {
            usedYear = row.get("year");
            int productId = ((Number) row.get("product_id")).intValue();
            Map<String, Object> crop = byProduct.computeIfAbsent(productId, id -> {
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("product", row.get("name") == null ? "product " + id : row.get("name"));
                c.put("plantedHa", null);
                c.put("harvestedHa", null);
                c.put("quantity", null);
                c.put("quantityUnit", null);
                c.put("yieldKgHa", null);
                c.put("valueThousandBrl", null);
                c.put("shareOfHarvestedPercent", null);
                return c;
            });
            Double value = row.get("value") instanceof Number n ? n.doubleValue() : null;
            switch (((Number) row.get("variable_id")).intValue()) {
                case PLANTED -> crop.put("plantedHa", value);
                case HARVESTED -> crop.put("harvestedHa", value);
                case QUANTITY -> {
                    crop.put("quantity", value);
                    crop.put("quantityUnit", row.get("unit"));
                }
                case YIELD -> crop.put("yieldKgHa", value);
                case VALUE -> crop.put("valueThousandBrl", value);
                default -> { }
            }
        }

        Map<String, Object> total = byProduct.remove(TOTAL_PRODUCT);
        double totalHarvested = total != null && total.get("harvestedHa") instanceof Double d ? d : 0.0;
        double totalValue = total != null && total.get("valueThousandBrl") instanceof Double d ? d : 0.0;

        List<Map<String, Object>> crops = new ArrayList<>();
        for (Map<String, Object> crop : byProduct.values()) {
            if (String.valueOf(crop.get("product")).endsWith(" Total")) continue;
            if (!(crop.get("harvestedHa") instanceof Double ha) || ha <= 0) continue;
            crop.put("shareOfHarvestedPercent", BrGeoValues.percent(ha, totalHarvested));
            crops.add(crop);
        }
        crops.sort((a, b) -> Double.compare((Double) b.get("harvestedHa"), (Double) a.get("harvestedHa")));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("year", usedYear);
        out.put("crops", crops.size() > limit ? new ArrayList<>(crops.subList(0, limit)) : crops);
        out.put("count", crops.size());
        out.put("totalHarvestedHa", totalHarvested);
        out.put("totalValueThousandBrl", totalValue);
        out.put("truncated", crops.size() > limit);
        out.put("uf", BrazilianStates.ofMunicipality(cdMun).map(BrazilianStates.State::uf).orElse(null));
        return out;
    }
}
