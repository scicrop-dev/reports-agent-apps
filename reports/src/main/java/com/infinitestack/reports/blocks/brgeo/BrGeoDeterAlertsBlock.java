package com.infinitestack.reports.blocks.brgeo;

import static com.infinitestack.reports.blocks.brgeo.BrGeoTables.BIOME;
import static com.infinitestack.reports.blocks.brgeo.BrGeoTables.DETER_ALERT;
import static com.infinitestack.reports.blocks.brgeo.BrGeoTables.Q_DETER_ALERT;
import static com.infinitestack.reports.blocks.brgeo.BrGeoTables.requirement;

import java.util.ArrayList;
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
 * {@code brgeo-deter-alerts}: INPE DETER alerts (near real time deforestation and degradation
 * warnings) that touch an area, optionally within a period, summarised by class, plus whether a
 * loaded DETER product covers the area at all (see {@link MonitoringCoverage}).
 */
@Component
public class BrGeoDeterAlertsBlock implements ReportBlock {

    static final int LIMIT = 200;

    static final List<MonitoringCoverage.Product> PRODUCTS = List.of(
            new MonitoringCoverage.Product("deter_amz", 1, "DETER Amazonia"),
            new MonitoringCoverage.Product("deter_cerrado", 3, "DETER Cerrado"));

    static final String SQL = "WITH " + BrGeoValues.INPUT_CTE
            + " SELECT a.product, a.classname, a.view_date, a.satellite, a.sensor,"
            + " ST_Area(ST_CollectionExtract(ST_Intersection(ST_MakeValid(a.geom), input.geom), 3)::geography) / 1e4 AS overlap_ha"
            + " FROM input JOIN " + Q_DETER_ALERT + " a ON a.valid_to IS NULL AND a.geom && input.geom"
            + " AND ST_Intersects(a.geom, input.geom)"
            + " WHERE (CAST(? AS date) IS NULL OR a.view_date >= CAST(? AS date))"
            + " AND (CAST(? AS date) IS NULL OR a.view_date <= CAST(? AS date))"
            + " ORDER BY a.view_date DESC, a.classname";

    private final BlockQueries queries;

    public BrGeoDeterAlertsBlock(BlockQueries queries) {
        this.queries = queries;
    }

    @Override
    public BlockDefinition definition() {
        return new BlockDefinition("brgeo-deter-alerts", "DETER alerts",
                "INPE DETER deforestation and degradation alerts in an area, by class, with monitoring coverage", "1.0.0",
                List.of(BrGeoValues.GEOMETRY_INPUT,
                        BlockPort.optional("dateFrom", DataType.DATE, "only alerts seen on or after this date"),
                        BlockPort.optional("dateTo", DataType.DATE, "only alerts seen on or before this date")),
                List.of(
                        BlockPort.required("alerts", DataType.ARRAY,
                                "up to 200, newest first: {viewDate, className, product, productName, overlapHa, satellite, sensor}"),
                        BlockPort.required("byClass", DataType.ARRAY,
                                "largest area first: {className, count, overlapHa, firstDate, lastDate}"),
                        BlockPort.required("count", DataType.NUMBER, "number of alerts in the area and period"),
                        BlockPort.required("totalOverlapHa", DataType.NUMBER, "sum of the alert areas inside the area, hectares"),
                        BlockPort.optional("lastAlertDate", DataType.DATE, "date of the most recent alert"),
                        BlockPort.required("monitored", DataType.BOOLEAN,
                                "a loaded DETER product covers the area; false means 'not monitored', not 'no alert'"),
                        BlockPort.required("coverage", DataType.ARRAY,
                                "{product, productName, biome, intersectsArea, loaded} per DETER product"),
                        BlockPort.required("truncated", DataType.BOOLEAN, "more than 200 alerts")),
                List.of(
                        requirement(DETER_ALERT, "product", "classname", "view_date", "satellite", "sensor", "geom", "valid_to"),
                        requirement(BIOME, "edition", "cd_bioma", "nm_bioma", "geom", "valid_to")),
                BrGeoValues.BLOCK_TIMEOUT);
    }

    @Override
    public BlockDocs docs() {
        return new BlockDocs("BR Geo",
                "Lists the DETER alerts of deforestation, degradation and burn scars that INPE detected in an "
                        + "area, and says whether the area is covered by DETER at all.",
                List.of(
                        "Selects the current alerts whose polygon intersects the area, optionally between dateFrom and dateTo (view date).",
                        "Measures, for each alert, the part inside the area in geodesic hectares.",
                        "Groups by class (DESMATAMENTO_CR, DEGRADACAO, CICATRIZ_DE_QUEIMADA, ...).",
                        "Checks the coverage: DETER Amazonia for the Amazonia biome, DETER Cerrado for the Cerrado, "
                                + "and whether BR Geo loaded each product."),
                List.of(
                        "geometry: connect car-property-geometry.geometry.",
                        "dateFrom / dateTo: a report input of type date, or a fixed value (e.g. 2008-07-22, the Forest Code cut-off).",
                        "In the template, show 'nao monitorado' when monitored is false instead of 'sem alertas'."),
                List.of(
                        "DETER is a warning system, not an official deforestation measure (that is PRODES).",
                        "Small clearings (below about 3 ha) and areas under clouds may go undetected.",
                        "Coverage uses the IBGE biome as a proxy; DETER Amazonia really covers the Legal Amazon.",
                        "Pantanal, Caatinga, Atlantic Forest and Pampa have no DETER product."),
                "INPE, DETER (TerraBrasilis WFS), loaded into gold.br_geo_agent_app_inpe_deter_alert",
                "{{#<node>.monitored}}{{<node>.count}} alerta(s), {{<node>.totalOverlapHa}} ha{{/<node>.monitored}}"
                        + "{{^<node>.monitored}}Area nao monitorada pelo DETER{{/<node>.monitored}}");
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> inputs, ExecutionMetadata metadata) {
        String geoJson = BrGeoValues.geoJson(inputs);
        Object dateFrom = inputs.get("dateFrom");
        Object dateTo = inputs.get("dateTo");
        return queries.read(BrGeoValues.QUERY_TIMEOUT, jdbc -> {
            List<Map<String, Object>> rows = jdbc.queryForList(SQL, geoJson, dateFrom, dateFrom, dateTo, dateTo);
            List<Map<String, Object>> coverage = MonitoringCoverage.check(jdbc, geoJson, Q_DETER_ALERT, PRODUCTS, false);

            List<Map<String, Object>> alerts = new ArrayList<>();
            Map<String, double[]> areaByClass = new TreeMap<>();
            Map<String, Integer> countByClass = new TreeMap<>();
            Map<String, String[]> datesByClass = new TreeMap<>();
            double total = 0;
            for (Map<String, Object> row : rows) {
                String className = (String) row.get("classname");
                String viewDate = row.get("view_date") == null ? null : row.get("view_date").toString();
                double ha = BrGeoValues.hectares((Number) row.get("overlap_ha"));
                total += ha;
                areaByClass.computeIfAbsent(className, k -> new double[1])[0] += ha;
                countByClass.merge(className, 1, Integer::sum);
                String[] dates = datesByClass.computeIfAbsent(className, k -> new String[] {viewDate, viewDate});
                if (viewDate != null && (dates[0] == null || viewDate.compareTo(dates[0]) < 0)) dates[0] = viewDate;
                if (viewDate != null && (dates[1] == null || viewDate.compareTo(dates[1]) > 0)) dates[1] = viewDate;
                if (alerts.size() < LIMIT) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("viewDate", viewDate);
                    item.put("className", className);
                    item.put("product", row.get("product"));
                    item.put("productName", MonitoringCoverage.nameOf(PRODUCTS, (String) row.get("product")));
                    item.put("overlapHa", ha);
                    item.put("satellite", row.get("satellite"));
                    item.put("sensor", row.get("sensor"));
                    alerts.add(item);
                }
            }
            List<Map<String, Object>> byClass = new ArrayList<>();
            areaByClass.forEach((className, ha) -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("className", className);
                item.put("count", countByClass.get(className));
                item.put("overlapHa", BrGeoValues.round(ha[0], 4));
                item.put("firstDate", datesByClass.get(className)[0]);
                item.put("lastDate", datesByClass.get(className)[1]);
                byClass.add(item);
            });
            byClass.sort((a, b) -> Double.compare((Double) b.get("overlapHa"), (Double) a.get("overlapHa")));

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("alerts", alerts);
            out.put("byClass", byClass);
            out.put("count", rows.size());
            out.put("totalOverlapHa", BrGeoValues.round(total, 4));
            out.put("lastAlertDate", alerts.isEmpty() ? null : alerts.get(0).get("viewDate"));
            out.put("monitored", MonitoringCoverage.monitored(coverage));
            out.put("coverage", coverage);
            out.put("truncated", rows.size() > LIMIT);
            return out;
        });
    }
}
