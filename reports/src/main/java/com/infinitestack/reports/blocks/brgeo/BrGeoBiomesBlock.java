package com.infinitestack.reports.blocks.brgeo;

import static com.infinitestack.reports.blocks.brgeo.BrGeoTables.BIOME;
import static com.infinitestack.reports.blocks.brgeo.BrGeoTables.Q_BIOME;
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
 * {@code brgeo-biomes}: the IBGE biomes an area falls in and the share of the area in each. The
 * biome matters for the Forest Code (the legal reserve percentage depends on it) and tells which
 * deforestation monitoring products (DETER, PRODES) cover the area.
 */
@Component
public class BrGeoBiomesBlock implements ReportBlock {

    static final String SQL = "WITH " + BrGeoValues.INPUT_CTE + ","
            + " edition AS (SELECT max(edition) AS edition FROM " + Q_BIOME + " WHERE valid_to IS NULL)"
            + " SELECT input.area_ha, edition.edition, b.cd_bioma, b.nm_bioma,"
            + " ST_Area(ST_Intersection(b.geom, input.geom)::geography) / 1e4 AS intersection_ha"
            + " FROM input CROSS JOIN edition"
            + " LEFT JOIN " + Q_BIOME + " b ON b.valid_to IS NULL AND b.edition = edition.edition"
            + " AND b.geom && input.geom AND ST_Intersects(b.geom, input.geom)"
            + " ORDER BY intersection_ha DESC NULLS LAST, b.cd_bioma";

    private final BlockQueries queries;

    public BrGeoBiomesBlock(BlockQueries queries) {
        this.queries = queries;
    }

    @Override
    public BlockDefinition definition() {
        return new BlockDefinition("brgeo-biomes", "Biomes of an area",
                "IBGE biomes an area falls in, with the hectares and share of the area in each", "1.0.0",
                List.of(BrGeoValues.GEOMETRY_INPUT),
                List.of(
                        BlockPort.required("biomes", DataType.ARRAY,
                                "largest share first: {code, name, intersectionHa, percentOfArea}"),
                        BlockPort.required("count", DataType.NUMBER, "number of biomes touched"),
                        BlockPort.optional("mainBiome", DataType.STRING, "name of the biome with the largest share"),
                        BlockPort.required("areaHa", DataType.NUMBER, "area of the input geometry, hectares"),
                        BlockPort.optional("edition", DataType.NUMBER, "edition (year) of the IBGE biome map used")),
                List.of(requirement(BIOME, "edition", "cd_bioma", "nm_bioma", "geom", "valid_to")),
                BrGeoValues.BLOCK_TIMEOUT);
    }

    @Override
    public BlockDocs docs() {
        return new BlockDocs("BR Geo",
                "Tells which biome (Amazonia, Cerrado, Caatinga, Atlantic Forest, Pampa, Pantanal) an area "
                        + "is in, and how much of it is in each when it sits on a transition.",
                List.of(
                        "Takes the most recent edition of the IBGE biome map (1:250,000).",
                        "Intersects the area with the six biome polygons and measures each intersection in geodesic hectares.",
                        "mainBiome is the one with the largest share."),
                List.of(
                        "geometry: connect car-property-geometry.geometry, or a report input of type geometry.",
                        "Put it next to brgeo-deter-alerts and brgeo-prodes: their coverage depends on the biome."),
                List.of(
                        "The biome map is 1:250,000: near a biome border the split is approximate.",
                        "This is the IBGE biome, not the Legal Amazon (an administrative region that also includes "
                                + "parts of the Cerrado and Pantanal)."),
                "IBGE, Biomas e Sistema Costeiro-Marinho do Brasil, loaded into gold.br_geo_agent_app_ibge_biome",
                "Bioma predominante: {{<node>.mainBiome}}");
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> inputs, ExecutionMetadata metadata) {
        String geoJson = BrGeoValues.geoJson(inputs);
        List<Map<String, Object>> rows = queries.read(BrGeoValues.QUERY_TIMEOUT, jdbc -> jdbc.queryForList(SQL, geoJson));
        Double areaHa = rows.isEmpty() ? 0.0 : BrGeoValues.hectares((Number) rows.get(0).get("area_ha"));
        List<Map<String, Object>> biomes = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (row.get("cd_bioma") == null) continue;
            double ha = BrGeoValues.hectares((Number) row.get("intersection_ha"));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", row.get("cd_bioma"));
            item.put("name", row.get("nm_bioma"));
            item.put("intersectionHa", ha);
            item.put("percentOfArea", BrGeoValues.percent(ha, areaHa));
            biomes.add(item);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("biomes", biomes);
        out.put("count", biomes.size());
        out.put("mainBiome", biomes.isEmpty() ? null : biomes.get(0).get("name"));
        out.put("areaHa", areaHa == null ? 0.0 : areaHa);
        out.put("edition", rows.isEmpty() ? null : rows.get(0).get("edition"));
        return out;
    }
}
