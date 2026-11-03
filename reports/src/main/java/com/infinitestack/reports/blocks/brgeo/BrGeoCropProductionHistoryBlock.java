package com.infinitestack.reports.blocks.brgeo;

import static com.infinitestack.reports.blocks.brgeo.BrGeoCropProductionBlock.HARVESTED;
import static com.infinitestack.reports.blocks.brgeo.BrGeoCropProductionBlock.PLANTED;
import static com.infinitestack.reports.blocks.brgeo.BrGeoCropProductionBlock.QUANTITY;
import static com.infinitestack.reports.blocks.brgeo.BrGeoCropProductionBlock.TABLE;
import static com.infinitestack.reports.blocks.brgeo.BrGeoCropProductionBlock.TOTAL_PRODUCT;
import static com.infinitestack.reports.blocks.brgeo.BrGeoCropProductionBlock.VALUE;
import static com.infinitestack.reports.blocks.brgeo.BrGeoCropProductionBlock.YIELD;
import static com.infinitestack.reports.blocks.brgeo.BrGeoTables.PAM_PRODUCT;
import static com.infinitestack.reports.blocks.brgeo.BrGeoTables.PAM_VALUE;
import static com.infinitestack.reports.blocks.brgeo.BrGeoTables.Q_PAM_PRODUCT;
import static com.infinitestack.reports.blocks.brgeo.BrGeoTables.Q_PAM_VALUE;
import static com.infinitestack.reports.blocks.brgeo.BrGeoTables.requirement;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.stereotype.Component;

import com.infinitestack.reports.engine.DataType;
import com.infinitestack.reports.engine.block.BlockDefinition;
import com.infinitestack.reports.engine.block.BlockDocs;
import com.infinitestack.reports.engine.block.BlockPort;
import com.infinitestack.reports.engine.block.BlockQueries;
import com.infinitestack.reports.engine.block.ExecutionMetadata;
import com.infinitestack.reports.engine.block.ReportBlock;

/**
 * {@code brgeo-crop-production-history}: the yearly series of one crop in a municipality, from the
 * IBGE PAM (SIDRA table 5457, the same variables as {@code brgeo-crop-production}): planted and
 * harvested area, quantity, yield and value over the last N years, with a bar chart.
 */
@Component
public class BrGeoCropProductionHistoryBlock implements ReportBlock {

    static final int DEFAULT_YEARS = 10;
    static final int MAX_YEARS = 60;
    static final int CHART_WIDTH = 900;
    static final int CHART_HEIGHT = 300;
    static final List<String> CHART_VARIABLES = List.of("plantedHa", "harvestedHa", "quantity", "yieldKgHa", "valueThousandBrl");

    static final String PRODUCTS_SQL = "SELECT product_id, name FROM " + Q_PAM_PRODUCT
            + " WHERE table_id = " + TABLE + " AND valid_to IS NULL AND product_id <> " + TOTAL_PRODUCT + " ORDER BY name";

    /** Parameters: municipality, product, municipality, product, years. */
    static final String SERIES_SQL = "WITH last AS (SELECT max(year) AS year FROM " + Q_PAM_VALUE
            + " WHERE table_id = " + TABLE + " AND cd_mun = ? AND product_id = ? AND valid_to IS NULL)"
            + " SELECT v.year, v.variable_id, v.value, v.value_flag, v.unit FROM last JOIN " + Q_PAM_VALUE + " v"
            + " ON v.table_id = " + TABLE + " AND v.cd_mun = ? AND v.product_id = ? AND v.valid_to IS NULL"
            + " AND v.year > last.year - ? AND v.year <= last.year"
            + " AND v.variable_id IN (" + PLANTED + ", " + HARVESTED + ", " + QUANTITY + ", " + YIELD + ", " + VALUE + ")"
            + " ORDER BY v.year, v.variable_id";

    private final BlockQueries queries;
    private final YearBarChart chart = new YearBarChart();

    public BrGeoCropProductionHistoryBlock(BlockQueries queries) {
        this.queries = queries;
    }

    @Override
    public BlockDefinition definition() {
        return new BlockDefinition("brgeo-crop-production-history", "Crop production history of a municipality",
                "IBGE PAM: yearly series of one crop in a municipality (planted and harvested area, quantity, yield, value), with a chart",
                "1.0.0",
                List.of(
                        BlockPort.required("municipalityIbge", DataType.NUMBER,
                                "IBGE municipality code (7 digits); connect car-property.municipalityIbge or brgeo-municipalities.mainIbgeCode"),
                        BlockPort.required("product", DataType.STRING,
                                "crop as the PAM names it, e.g. Soja (em grão), Milho (em grão), Cana-de-açúcar; accents and case ignored,"
                                        + " the beginning of the name is enough (Soja)"),
                        BlockPort.optional("years", DataType.NUMBER, "how many years up to the latest available; default 10"),
                        BlockPort.optional("chartVariable", DataType.STRING,
                                "what the chart shows: plantedHa, harvestedHa (default), quantity, yieldKgHa or valueThousandBrl")),
                List.of(
                        BlockPort.required("found", DataType.BOOLEAN, "the PAM has the crop in the municipality"),
                        BlockPort.optional("message", DataType.STRING, "why nothing was found"),
                        BlockPort.optional("product", DataType.STRING, "the crop as the PAM names it"),
                        BlockPort.required("series", DataType.ARRAY,
                                "oldest first: {year, plantedHa, harvestedHa, quantity, unit, yieldKgHa, valueThousandBrl}"),
                        BlockPort.required("count", DataType.NUMBER, "years in the series"),
                        BlockPort.optional("fromYear", DataType.NUMBER, "first year of the series"),
                        BlockPort.optional("toYear", DataType.NUMBER, "last year of the series (latest available)"),
                        BlockPort.optional("unit", DataType.STRING, "unit of quantity (Toneladas, Mil frutos...)"),
                        BlockPort.optional("chart", DataType.STRING, "PNG bar chart of chartVariable per year, as a data:image/png;base64 URL"),
                        BlockPort.required("chartVariable", DataType.STRING, "the variable the chart shows"),
                        BlockPort.required("availableProducts", DataType.ARRAY, "crop names of table 5457, to pick from"),
                        BlockPort.optional("uf", DataType.STRING, "state (abbreviation) of the municipality")),
                List.of(
                        requirement(PAM_VALUE, "table_id", "year", "cd_mun", "variable_id", "product_id", "value",
                                "value_flag", "unit", "valid_to"),
                        requirement(PAM_PRODUCT, "table_id", "product_id", "name", "valid_to")),
                Duration.ofSeconds(20));
    }

    @Override
    public BlockDocs docs() {
        return new BlockDocs("BR Geo",
                "Shows how one crop evolved in a municipality over the years: area, production, yield and value from the "
                        + "IBGE PAM, with a bar chart ready for the report.",
                List.of(
                        "Matches the crop name with the products of SIDRA table 5457, ignoring accents and case; a name that is "
                                + "the start of several (Café) takes the subtotal line ('Café (em grão) Total').",
                        "Takes the latest year the IBGE published for the crop in the municipality and the years before it.",
                        "Reads the five variables of brgeo-crop-production for each year.",
                        "Draws chartVariable per year as bars (a year without value is a gap)."),
                List.of(
                        "municipalityIbge: connect car-property.municipalityIbge or brgeo-municipalities.mainIbgeCode.",
                        "product: a fixed value (Soja) or a report input; availableProducts lists the names.",
                        "chart goes straight into the template: <img src=\"{{<node>.chart}}\">."),
                List.of(
                        "It describes the municipality, not the property.",
                        "Values the IBGE keeps confidential or does not have ('X', '..', '...') come empty; '-' is the IBGE's zero and comes as 0.",
                        "Value is in thousand BRL of each year (not corrected for inflation); yield in kg/ha."),
                "IBGE, Produção Agrícola Municipal (SIDRA 5457), loaded into gold.br_geo_agent_app_ibge_pam_value",
                "{{#<node>.found}}<img src=\"{{<node>.chart}}\"><br>{{#<node>.series}}{{year}}: {{harvestedHa}} ha, "
                        + "{{yieldKgHa}} kg/ha<br>{{/<node>.series}}{{/<node>.found}}{{^<node>.found}}{{<node>.message}}{{/<node>.found}}");
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> inputs, ExecutionMetadata metadata) {
        String cdMun = String.valueOf(((Number) inputs.get("municipalityIbge")).longValue());
        if (cdMun.length() != 7) throw new IllegalArgumentException("municipalityIbge must have 7 digits, got " + cdMun);
        String product = inputs.get("product") == null ? null : inputs.get("product").toString().trim();
        if (product == null || product.isEmpty()) throw new IllegalArgumentException("product is required");
        int years = inputs.get("years") instanceof Number n ? Math.max(1, Math.min(MAX_YEARS, n.intValue())) : DEFAULT_YEARS;
        String variable = inputs.get("chartVariable") == null ? "harvestedHa" : inputs.get("chartVariable").toString().trim();
        if (!CHART_VARIABLES.contains(variable)) {
            throw new IllegalArgumentException("chartVariable must be one of " + CHART_VARIABLES + ", got " + variable);
        }
        String uf = BrazilianStates.ofMunicipality(cdMun).map(BrazilianStates.State::uf).orElse(null);

        return queries.read(Duration.ofSeconds(15), jdbc -> {
            List<Map<String, Object>> products = jdbc.queryForList(PRODUCTS_SQL);
            List<String> names = products.stream().map(p -> p.get("name").toString()).toList();
            Map<String, Object> match = matchProduct(products, product);
            if (match == null) return notFound("the PAM has no crop '" + product + "'", variable, names, uf);

            int productId = ((Number) match.get("product_id")).intValue();
            String name = match.get("name").toString();
            List<Map<String, Object>> rows = jdbc.queryForList(SERIES_SQL, cdMun, productId, cdMun, productId, years);
            List<Map<String, Object>> series = series(rows);
            if (series.isEmpty()) {
                Map<String, Object> out = notFound("the PAM has no " + name + " in municipality " + cdMun, variable, names, uf);
                out.put("product", name);
                return out;
            }
            List<YearBarChart.Bar> bars = new ArrayList<>();
            int first = (Integer) series.get(0).get("year");
            int last = (Integer) series.get(series.size() - 1).get("year");
            Map<Integer, Map<String, Object>> byYear = new TreeMap<>();
            for (Map<String, Object> item : series) byYear.put((Integer) item.get("year"), item);
            for (int y = first; y <= last; y++) {
                Map<String, Object> item = byYear.get(y);
                bars.add(new YearBarChart.Bar(y, item == null ? null : (Double) item.get(variable)));
            }
            Object unit = series.stream().map(s -> s.get("unit")).filter(u -> u != null).reduce((a, b) -> b).orElse(null);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("found", true);
            out.put("message", null);
            out.put("product", name);
            out.put("series", series);
            out.put("count", series.size());
            out.put("fromYear", first);
            out.put("toYear", last);
            out.put("unit", unit);
            out.put("chart", "data:image/png;base64," + Base64.getEncoder().encodeToString(chart.render(bars, CHART_WIDTH, CHART_HEIGHT)));
            out.put("chartVariable", variable);
            out.put("availableProducts", names);
            out.put("uf", uf);
            return out;
        });
    }

    /**
     * The product named: same name (accents and case ignored), else the only one whose name starts with
     * it, else among several the subtotal line ("... Total"), else the shortest name.
     */
    static Map<String, Object> matchProduct(List<Map<String, Object>> products, String text) {
        String wanted = BrazilianStates.fold(text);
        if (wanted.isEmpty()) return null;
        List<Map<String, Object>> prefix = new ArrayList<>();
        for (Map<String, Object> product : products) {
            String folded = BrazilianStates.fold(product.get("name").toString());
            if (folded.equals(wanted)) return product;
            if (folded.startsWith(wanted)) prefix.add(product);
        }
        if (prefix.isEmpty()) return null;
        return prefix.stream().filter(p -> p.get("name").toString().endsWith(" Total")).findFirst()
                .orElseGet(() -> prefix.stream().min(Comparator.comparingInt(p -> p.get("name").toString().length())).orElseThrow());
    }

    /** The value rows (one per year and variable) as one item per year, oldest first. */
    static List<Map<String, Object>> series(List<Map<String, Object>> rows) {
        Map<Integer, Map<String, Object>> byYear = new TreeMap<>();
        for (Map<String, Object> row : rows) {
            int year = ((Number) row.get("year")).intValue();
            Map<String, Object> item = byYear.computeIfAbsent(year, y -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("year", y);
                for (String name : List.of("plantedHa", "harvestedHa", "quantity", "unit", "yieldKgHa", "valueThousandBrl")) m.put(name, null);
                return m;
            });
            // '-' is the IBGE's zero; 'X', '..' and '...' stay empty
            Double value = row.get("value") instanceof Number n ? Double.valueOf(n.doubleValue())
                    : "-".equals(row.get("value_flag")) ? Double.valueOf(0.0) : null;
            switch (((Number) row.get("variable_id")).intValue()) {
                case PLANTED -> item.put("plantedHa", value);
                case HARVESTED -> item.put("harvestedHa", value);
                case QUANTITY -> {
                    item.put("quantity", value);
                    item.put("unit", row.get("unit"));
                }
                case YIELD -> item.put("yieldKgHa", value);
                case VALUE -> item.put("valueThousandBrl", value);
                default -> { }
            }
        }
        return new ArrayList<>(byYear.values());
    }

    private static Map<String, Object> notFound(String message, String variable, List<String> names, String uf) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("found", false);
        out.put("message", message);
        out.put("product", null);
        out.put("series", List.of());
        out.put("count", 0);
        out.put("fromYear", null);
        out.put("toYear", null);
        out.put("unit", null);
        out.put("chart", null);
        out.put("chartVariable", variable);
        out.put("availableProducts", names);
        out.put("uf", uf);
        return out;
    }
}
