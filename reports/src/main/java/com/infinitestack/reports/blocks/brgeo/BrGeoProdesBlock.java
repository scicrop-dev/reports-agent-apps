package com.infinitestack.reports.blocks.brgeo;

import static com.infinitestack.reports.blocks.brgeo.BrGeoTables.BIOME;
import static com.infinitestack.reports.blocks.brgeo.BrGeoTables.PRODES_INCREMENT;
import static com.infinitestack.reports.blocks.brgeo.BrGeoTables.Q_PRODES_INCREMENT;
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
 * {@code brgeo-prodes}: yearly deforestation increments mapped by INPE PRODES inside an area, per
 * product and year. Products are kept apart and never summed: the PRODES of the Amazonia biome and
 * the Legal Amazon overlap, as do the Legal Amazon and the Cerrado.
 */
@Component
public class BrGeoProdesBlock implements ReportBlock {

    /** Biome products only: the Legal Amazon product overlaps them and is left out of the coverage check. */
    static final List<MonitoringCoverage.Product> PRODUCTS = List.of(
            new MonitoringCoverage.Product("prodes_amazon", 1, "PRODES Amazonia"),
            new MonitoringCoverage.Product("prodes_caatinga", 2, "PRODES Caatinga"),
            new MonitoringCoverage.Product("prodes_cerrado", 3, "PRODES Cerrado"),
            new MonitoringCoverage.Product("prodes_mata_atlantica", 4, "PRODES Mata Atlantica"),
            new MonitoringCoverage.Product("prodes_pampa", 5, "PRODES Pampa"),
            new MonitoringCoverage.Product("prodes_pantanal", 6, "PRODES Pantanal"));

    /** PRODES year N maps clearing from August of N-1 to July of N: 2009 is the first year after the 2008 Forest Code cut-off. */
    static final int FIRST_YEAR_AFTER_CUTOFF = 2009;

    static final String SQL = "WITH " + BrGeoValues.INPUT_CTE
            + " SELECT p.product, p.year, count(*) AS polygons,"
            + " sum(ST_Area(ST_CollectionExtract(ST_Intersection(ST_MakeValid(p.geom), input.geom), 3)::geography) / 1e4) AS ha"
            + " FROM input JOIN " + Q_PRODES_INCREMENT + " p ON p.valid_to IS NULL AND p.geom && input.geom"
            + " AND ST_Intersects(p.geom, input.geom)"
            + " WHERE p.product <> 'prodes_legal_amz' AND (CAST(? AS integer) IS NULL OR p.year >= CAST(? AS integer))"
            + " GROUP BY p.product, p.year ORDER BY p.product, p.year";

    private final BlockQueries queries;

    public BrGeoProdesBlock(BlockQueries queries) {
        this.queries = queries;
    }

    @Override
    public BlockDefinition definition() {
        return new BlockDefinition("brgeo-prodes", "PRODES deforestation",
                "INPE PRODES yearly deforestation increments inside an area, per product and year, with coverage", "1.0.0",
                List.of(BrGeoValues.GEOMETRY_INPUT,
                        BlockPort.optional("yearFrom", DataType.NUMBER, "only PRODES years from this one on")),
                List.of(
                        BlockPort.required("byYear", DataType.ARRAY,
                                "per product and year, oldest first: {product, productName, year, polygons, deforestedHa}"),
                        BlockPort.required("byProduct", DataType.ARRAY,
                                "{product, productName, firstYear, lastYear, deforestedHa, sinceCutoffHa}"),
                        BlockPort.required("count", DataType.NUMBER, "number of (product, year) rows"),
                        BlockPort.required("maxDeforestedHa", DataType.NUMBER,
                                "deforested hectares of the product with the most (products are never summed)"),
                        BlockPort.optional("lastYear", DataType.NUMBER, "most recent year with deforestation in the area"),
                        BlockPort.required("monitored", DataType.BOOLEAN,
                                "a loaded PRODES product covers the area; false means 'not monitored', not 'no deforestation'"),
                        BlockPort.required("coverage", DataType.ARRAY,
                                "{product, productName, biome, intersectsArea, loaded, lastYear} per PRODES biome product")),
                List.of(
                        requirement(PRODES_INCREMENT, "product", "year", "geom", "valid_to"),
                        requirement(BIOME, "edition", "cd_bioma", "nm_bioma", "geom", "valid_to")),
                BrGeoValues.BLOCK_TIMEOUT);
    }

    @Override
    public BlockDocs docs() {
        return new BlockDocs("BR Geo",
                "Measures, year by year, how much of an area INPE PRODES mapped as clear-cut deforestation. "
                        + "PRODES is the official annual deforestation figure in Brazil.",
                List.of(
                        "Selects the current PRODES increment polygons that intersect the area (biome products; the Legal Amazon product is skipped because it repeats them).",
                        "Sums, per product and PRODES year, the geodesic hectares inside the area.",
                        "sinceCutoffHa adds the years from 2009 on: the PRODES year 2009 starts in August 2008, right after "
                                + "the Forest Code cut-off of 22 July 2008.",
                        "Checks the coverage: which biome products intersect the area and which BR Geo has loaded (and up to which year)."),
                List.of(
                        "geometry: connect car-property-geometry.geometry.",
                        "yearFrom: a fixed value such as 2009, or a report input of type number.",
                        "Show coverage[].lastYear in the report: a product loaded only up to an old year says nothing about recent years."),
                List.of(
                        "PRODES maps clear-cut of primary vegetation only; degradation and regrowth are not in it.",
                        "Minimum mapped area is 6.25 ha in the Amazonia (1 ha in recent years) and varies by biome.",
                        "Products are never summed: they overlap where biomes and the Legal Amazon meet.",
                        "The year boundary is August to July, not the calendar year."),
                "INPE, PRODES (TerraBrasilis WFS), loaded into gold.br_geo_agent_app_inpe_prodes_increment",
                "{{#<node>.byProduct}}{{productName}}: {{deforestedHa}} ha ({{firstYear}} a {{lastYear}})<br>{{/<node>.byProduct}}");
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> inputs, ExecutionMetadata metadata) {
        String geoJson = BrGeoValues.geoJson(inputs);
        Integer yearFrom = inputs.get("yearFrom") instanceof Number n ? n.intValue() : null;
        return queries.read(BrGeoValues.QUERY_TIMEOUT, jdbc -> {
            List<Map<String, Object>> rows = jdbc.queryForList(SQL, geoJson, yearFrom, yearFrom);
            List<Map<String, Object>> coverage = MonitoringCoverage.check(jdbc, geoJson, Q_PRODES_INCREMENT, PRODUCTS, true);

            List<Map<String, Object>> byYear = new ArrayList<>();
            Map<String, Map<String, Object>> byProduct = new LinkedHashMap<>();
            Integer lastYear = null;
            for (Map<String, Object> row : rows) {
                String product = (String) row.get("product");
                int year = ((Number) row.get("year")).intValue();
                double ha = BrGeoValues.hectares((Number) row.get("ha"));
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("product", product);
                item.put("productName", MonitoringCoverage.nameOf(PRODUCTS, product));
                item.put("year", year);
                item.put("polygons", ((Number) row.get("polygons")).intValue());
                item.put("deforestedHa", ha);
                byYear.add(item);
                if (lastYear == null || year > lastYear) lastYear = year;

                Map<String, Object> total = byProduct.computeIfAbsent(product, p -> {
                    Map<String, Object> t = new LinkedHashMap<>();
                    t.put("product", p);
                    t.put("productName", MonitoringCoverage.nameOf(PRODUCTS, p));
                    t.put("firstYear", year);
                    t.put("lastYear", year);
                    t.put("deforestedHa", 0.0);
                    t.put("sinceCutoffHa", 0.0);
                    return t;
                });
                total.put("firstYear", Math.min((Integer) total.get("firstYear"), year));
                total.put("lastYear", Math.max((Integer) total.get("lastYear"), year));
                total.put("deforestedHa", BrGeoValues.round((Double) total.get("deforestedHa") + ha, 4));
                if (year >= FIRST_YEAR_AFTER_CUTOFF) {
                    total.put("sinceCutoffHa", BrGeoValues.round((Double) total.get("sinceCutoffHa") + ha, 4));
                }
            }
            double max = byProduct.values().stream().mapToDouble(t -> (Double) t.get("deforestedHa")).max().orElse(0);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("byYear", byYear);
            out.put("byProduct", new ArrayList<>(byProduct.values()));
            out.put("count", byYear.size());
            out.put("maxDeforestedHa", max);
            out.put("lastYear", lastYear);
            out.put("monitored", MonitoringCoverage.monitored(coverage));
            out.put("coverage", coverage);
            return out;
        });
    }
}
