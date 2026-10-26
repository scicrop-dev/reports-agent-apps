package com.infinitestack.reports.blocks.brgeo;

import static com.infinitestack.reports.blocks.brgeo.BrGeoTables.MUNICIPALITY;
import static com.infinitestack.reports.blocks.brgeo.BrGeoTables.Q_MUNICIPALITY;
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
 * {@code brgeo-municipalities}: the IBGE municipalities an area falls in, with how much of the area
 * is in each. Uses the most recent edition of the municipal grid, so a municipality created or
 * redrawn by the IBGE shows as it is today.
 */
@Component
public class BrGeoMunicipalitiesBlock implements ReportBlock {

    static final String SQL = "WITH " + BrGeoValues.INPUT_CTE + ","
            + " edition AS (SELECT max(edition) AS edition FROM " + Q_MUNICIPALITY + " WHERE valid_to IS NULL)"
            + " SELECT input.area_ha, edition.edition, m.cd_mun, m.nm_mun, m.sigla_uf,"
            + " ST_Area(ST_Intersection(m.geom, input.geom)::geography) / 1e4 AS intersection_ha"
            + " FROM input CROSS JOIN edition"
            + " LEFT JOIN " + Q_MUNICIPALITY + " m ON m.valid_to IS NULL AND m.edition = edition.edition"
            + " AND m.geom && input.geom AND ST_Intersects(m.geom, input.geom)"
            + " ORDER BY intersection_ha DESC NULLS LAST, m.cd_mun";

    private final BlockQueries queries;

    public BrGeoMunicipalitiesBlock(BlockQueries queries) {
        this.queries = queries;
    }

    @Override
    public BlockDefinition definition() {
        return new BlockDefinition("brgeo-municipalities", "Municipalities of an area",
                "IBGE municipalities an area falls in, with the hectares and share of the area in each", "1.0.0",
                List.of(BrGeoValues.GEOMETRY_INPUT),
                List.of(
                        BlockPort.required("municipalities", DataType.ARRAY,
                                "largest share first: {ibgeCode, name, uf, intersectionHa, percentOfArea}"),
                        BlockPort.required("count", DataType.NUMBER, "number of municipalities touched"),
                        BlockPort.optional("main", DataType.OBJECT,
                                "the municipality with the largest share: {ibgeCode, name, uf, percentOfArea}"),
                        BlockPort.optional("mainIbgeCode", DataType.NUMBER,
                                "IBGE code of the main municipality, as a number, to connect to per-municipality Blocks"),
                        BlockPort.required("areaHa", DataType.NUMBER, "area of the input geometry, hectares"),
                        BlockPort.optional("edition", DataType.NUMBER, "edition (year) of the IBGE municipal grid used")),
                List.of(requirement(MUNICIPALITY, "edition", "cd_mun", "nm_mun", "sigla_uf", "geom", "valid_to")),
                BrGeoValues.BLOCK_TIMEOUT);
    }

    @Override
    public BlockDocs docs() {
        return new BlockDocs("BR Geo",
                "Tells in which municipalities an area is and how much of it is in each one. Useful to "
                        + "confirm the municipality declared in the CAR and to spot areas that cross a border.",
                List.of(
                        "Takes the most recent edition of the IBGE municipal grid (BR Geo keeps every edition side by side).",
                        "Intersects the area with each municipality polygon, using the spatial index.",
                        "Measures each intersection on the ellipsoid (geodesic hectares) and divides by the area of the input."),
                List.of(
                        "geometry: connect car-property-geometry.geometry, or a report input of type geometry.",
                        "mainIbgeCode feeds the Blocks that work per municipality, such as brgeo-crop-production."),
                List.of(
                        "Percentages are over the input geometry; slivers along borders are normal and come from the "
                                + "different scales of the CAR and IBGE drawings.",
                        "An area outside Brazil returns an empty list."),
                "IBGE, Malha Municipal (geoftp), loaded by br-geo-agent-app into gold.br_geo_agent_app_ibge_municipality",
                "{{#<node>.municipalities}}{{name}}/{{uf}}: {{percentOfArea}}%<br>{{/<node>.municipalities}}");
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> inputs, ExecutionMetadata metadata) {
        String geoJson = BrGeoValues.geoJson(inputs);
        List<Map<String, Object>> rows = queries.read(BrGeoValues.QUERY_TIMEOUT, jdbc -> jdbc.queryForList(SQL, geoJson));
        Double areaHa = rows.isEmpty() ? 0.0 : BrGeoValues.hectares((Number) rows.get(0).get("area_ha"));
        List<Map<String, Object>> municipalities = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (row.get("cd_mun") == null) continue;
            double ha = BrGeoValues.hectares((Number) row.get("intersection_ha"));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("ibgeCode", row.get("cd_mun"));
            item.put("name", row.get("nm_mun"));
            item.put("uf", row.get("sigla_uf"));
            item.put("intersectionHa", ha);
            item.put("percentOfArea", BrGeoValues.percent(ha, areaHa));
            municipalities.add(item);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("municipalities", municipalities);
        out.put("count", municipalities.size());
        out.put("main", municipalities.isEmpty() ? null : mainOf(municipalities.get(0)));
        out.put("mainIbgeCode", municipalities.isEmpty() ? null : Long.valueOf((String) municipalities.get(0).get("ibgeCode")));
        out.put("areaHa", areaHa == null ? 0.0 : areaHa);
        out.put("edition", rows.isEmpty() ? null : rows.get(0).get("edition"));
        return out;
    }

    private static Map<String, Object> mainOf(Map<String, Object> item) {
        Map<String, Object> main = new LinkedHashMap<>();
        main.put("ibgeCode", item.get("ibgeCode"));
        main.put("name", item.get("name"));
        main.put("uf", item.get("uf"));
        main.put("percentOfArea", item.get("percentOfArea"));
        return main;
    }
}
