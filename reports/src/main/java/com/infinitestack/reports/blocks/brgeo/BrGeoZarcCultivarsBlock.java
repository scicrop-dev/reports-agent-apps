package com.infinitestack.reports.blocks.brgeo;

import static com.infinitestack.reports.blocks.brgeo.BrGeoTables.Q_ZARC_CULTIVAR;
import static com.infinitestack.reports.blocks.brgeo.BrGeoTables.ZARC_CULTIVAR;
import static com.infinitestack.reports.blocks.brgeo.BrGeoTables.requirement;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.infinitestack.reports.engine.DataType;
import com.infinitestack.reports.engine.block.BlockDefinition;
import com.infinitestack.reports.engine.block.BlockDocs;
import com.infinitestack.reports.engine.block.BlockPort;
import com.infinitestack.reports.engine.block.BlockQueries;
import com.infinitestack.reports.engine.block.ExecutionMetadata;
import com.infinitestack.reports.engine.block.ReportBlock;

/**
 * {@code brgeo-zarc-cultivars}: the cultivars of a crop that the ZARC (SISZARC, MAPA) indicates for a
 * state in a season, one row per cultivar and adaptation region.
 *
 * <p>BR Geo stores the columns that change by crop (soy: relative maturity group, days to maturity and
 * to flowering, yield potential; cotton: cycle, durations...) in {@code attributes}, a flat JSON object
 * keyed by the SISZARC column title with the cell text as value ("9,2"). The Block passes it on and
 * picks the group (a title with "grupo") and the cycle (a title with "ciclo") when the crop has them.
 */
@Component
public class BrGeoZarcCultivarsBlock implements ReportBlock {

    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 500;
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Distinct (season, crop) pairs, one index probe each (recursive skip scan over the scope index). */
    static final String PAIRS_SQL = "WITH RECURSIVE p AS ("
            + " (SELECT season, crop FROM " + Q_ZARC_CULTIVAR + " ORDER BY season, crop LIMIT 1)"
            + " UNION ALL"
            + " SELECT n.season, n.crop FROM p, LATERAL (SELECT season, crop FROM " + Q_ZARC_CULTIVAR
            + " WHERE (season, crop) > (p.season, p.crop) ORDER BY season, crop LIMIT 1) n)"
            + " SELECT season, crop FROM p";

    static final String CULTIVARS_SQL = "SELECT rnc, cultivar, adaptation_region, attributes::text AS attributes FROM " + Q_ZARC_CULTIVAR
            + " WHERE season = ? AND crop = ? AND uf = ? AND valid_to IS NULL"
            + " ORDER BY cultivar, rnc, adaptation_region, variant";

    private final BlockQueries queries;

    public BrGeoZarcCultivarsBlock(BlockQueries queries) {
        this.queries = queries;
    }

    @Override
    public BlockDefinition definition() {
        return new BlockDefinition("brgeo-zarc-cultivars", "ZARC cultivars of a crop",
                "ZARC (SISZARC, MAPA): cultivars of a crop indicated for a state in a season, with adaptation region and crop parameters",
                "1.0.0",
                List.of(
                        BlockPort.required("crop", DataType.STRING, "crop as the SISZARC names it, e.g. SOJA, MILHO, ALGODÃO (accents and case ignored)"),
                        BlockPort.optional("uf", DataType.STRING, "state (abbreviation, name or IBGE code); this or municipalityIbge"),
                        BlockPort.optional("municipalityIbge", DataType.NUMBER,
                                "IBGE municipality code (7 digits), used for its state when uf is empty; connect car-property.municipalityIbge"),
                        BlockPort.optional("season", DataType.STRING, "season, e.g. 2026/2027; default the latest with the crop"),
                        BlockPort.optional("limit", DataType.NUMBER, "how many rows to list; default 50, at most 500")),
                List.of(
                        BlockPort.required("found", DataType.BOOLEAN, "the ZARC indicates cultivars of the crop in the state"),
                        BlockPort.optional("message", DataType.STRING, "why nothing was found"),
                        BlockPort.optional("crop", DataType.STRING, "the crop as the SISZARC names it"),
                        BlockPort.optional("season", DataType.STRING, "the season used"),
                        BlockPort.optional("uf", DataType.STRING, "the state used"),
                        BlockPort.required("cultivars", DataType.ARRAY,
                                "by cultivar name: {rnc, cultivar, adaptationRegion, group, cycle, attributes {SISZARC column: text}}"),
                        BlockPort.required("count", DataType.NUMBER, "rows found (before the limit)"),
                        BlockPort.required("distinctCultivars", DataType.NUMBER, "distinct cultivars (RNC) found"),
                        BlockPort.required("adaptationRegions", DataType.ARRAY, "the adaptation regions found, sorted"),
                        BlockPort.required("truncated", DataType.BOOLEAN, "more rows than the limit"),
                        BlockPort.required("availableCrops", DataType.ARRAY, "crops the SISZARC has cultivars for in the season")),
                List.of(requirement(ZARC_CULTIVAR, "season", "crop", "rnc", "cultivar", "uf", "adaptation_region", "variant",
                        "attributes", "valid_to")),
                BrGeoValues.BLOCK_TIMEOUT);
    }

    @Override
    public BlockDocs docs() {
        return new BlockDocs("BR Geo",
                "Lists the cultivars of a crop that the agricultural climate risk zoning (ZARC) indicates for a state: "
                        + "the ones whose planting is covered by the zoning (PROAGRO, insurance), with their adaptation region "
                        + "and the crop parameters MAPA publishes (maturity group, cycle, days to flowering...).",
                List.of(
                        "Lists the crops and seasons BR Geo loaded and matches the crop given, ignoring accents and case "
                                + "(also the name before ' - ', for labels with the scientific name).",
                        "Takes the season given or the latest one with the crop.",
                        "Reads the rows of the state: one per cultivar (RNC number) and adaptation region; a cultivar with "
                                + "different parameters for different maintainers comes once per variant.",
                        "group and cycle are taken from the crop parameters when the crop has a column with 'grupo' or 'ciclo' "
                                + "in the title; attributes has every parameter as SISZARC prints it."),
                List.of(
                        "crop: a fixed value (SOJA) or a report input; availableCrops lists the names.",
                        "uf: connect brgeo-municipalities.mainUf, or give municipalityIbge (car-property.municipalityIbge).",
                        "Use it next to brgeo-zarc-risk: the risk table is by cultivar group, this Block says which cultivars "
                                + "are in each group."),
                List.of(
                        "The adaptation region (e.g. Macrorregião 5) is not resolved to the municipality: all regions of the "
                                + "state are listed.",
                        "Values in attributes are text as SISZARC prints them (decimal comma).",
                        "The report has no maintainer column; a cultivar kept by several companies can show more than once.",
                        "Only the crops and seasons BR Geo loaded are present."),
                "MAPA, SISZARC (Consultar cultivares), loaded into gold.br_geo_agent_app_zarc_cultivar",
                "{{#<node>.found}}Cultivares de {{<node>.crop}} indicadas em {{<node>.uf}} ({{<node>.season}}): "
                        + "{{<node>.distinctCultivars}}{{#<node>.cultivars}}<br>{{cultivar}} (RNC {{rnc}}), {{adaptationRegion}}"
                        + "{{#group}}, grupo {{group}}{{/group}}{{/<node>.cultivars}}{{/<node>.found}}"
                        + "{{^<node>.found}}{{<node>.message}}{{/<node>.found}}");
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> inputs, ExecutionMetadata metadata) {
        String crop = text(inputs.get("crop"));
        if (crop == null) throw new IllegalArgumentException("crop is required");
        String uf = uf(text(inputs.get("uf")), inputs.get("municipalityIbge"));
        String season = text(inputs.get("season"));
        int limit = inputs.get("limit") instanceof Number n ? Math.max(1, Math.min(MAX_LIMIT, n.intValue())) : DEFAULT_LIMIT;

        return queries.read(BrGeoValues.QUERY_TIMEOUT, jdbc -> {
            List<Map<String, Object>> pairs = jdbc.queryForList(PAIRS_SQL);
            // the exact name first ("MILHO" must not take "MILHO (2ª safra)"), then the name before ' - ' / ' ('
            boolean exact = pairs.stream().anyMatch(p -> BrazilianStates.fold(p.get("crop").toString()).equals(BrazilianStates.fold(crop)));
            String zarcCrop = null;
            List<String> seasons = new ArrayList<>();
            for (Map<String, Object> pair : pairs) {
                String pairCrop = pair.get("crop").toString();
                if (exact ? !BrazilianStates.fold(pairCrop).equals(BrazilianStates.fold(crop)) : !cropMatches(pairCrop, crop)) continue;
                String pairSeason = pair.get("season").toString();
                if (season != null && !BrazilianStates.fold(pairSeason).equals(BrazilianStates.fold(season))) continue;
                if (zarcCrop != null && !zarcCrop.equals(pairCrop)) continue; // several labels match: keep the first
                zarcCrop = pairCrop;
                seasons.add(pairSeason);
            }
            seasons.sort(BrGeoZarcRiskBlock.SEASONS_NEWEST_FIRST);
            String latestSeason = pairs.stream().map(p -> p.get("season").toString())
                    .sorted(BrGeoZarcRiskBlock.SEASONS_NEWEST_FIRST).findFirst().orElse(null);
            if (zarcCrop == null) {
                String in = season == null ? "" : " in season " + season;
                return notFound("the ZARC has no cultivars of '" + crop + "'" + in, uf, cropsOf(pairs, season != null ? season : latestSeason));
            }

            String used = seasons.get(0);
            List<Map<String, Object>> rows = jdbc.queryForList(CULTIVARS_SQL, used, zarcCrop, uf);
            if (rows.isEmpty()) {
                return notFound("the ZARC of " + zarcCrop + " " + used + " indicates no cultivar in " + uf, uf, cropsOf(pairs, used));
            }
            List<Map<String, Object>> cultivars = new ArrayList<>();
            Set<Object> rncs = new LinkedHashSet<>();
            Set<String> regions = new java.util.TreeSet<>();
            for (Map<String, Object> row : rows) {
                rncs.add(row.get("rnc"));
                regions.add(String.valueOf(row.get("adaptation_region")));
                if (cultivars.size() < limit) cultivars.add(cultivar(row));
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("found", true);
            out.put("message", null);
            out.put("crop", zarcCrop);
            out.put("season", used);
            out.put("uf", uf);
            out.put("cultivars", cultivars);
            out.put("count", rows.size());
            out.put("distinctCultivars", rncs.size());
            out.put("adaptationRegions", new ArrayList<>(regions));
            out.put("truncated", rows.size() > limit);
            out.put("availableCrops", cropsOf(pairs, used));
            return out;
        });
    }

    /** The state from uf (abbreviation, name or code) or, when empty, from the municipality code. */
    static String uf(String uf, Object municipalityIbge) {
        if (uf != null) {
            return BrazilianStates.find(uf).map(BrazilianStates.State::uf)
                    .orElseThrow(() -> new IllegalArgumentException("unknown state '" + uf + "'"));
        }
        if (municipalityIbge instanceof Number n) {
            String code = String.valueOf(n.longValue());
            return BrazilianStates.ofMunicipality(code).map(BrazilianStates.State::uf)
                    .orElseThrow(() -> new IllegalArgumentException("municipalityIbge " + code + " is of no Brazilian state"));
        }
        throw new IllegalArgumentException("give uf or municipalityIbge");
    }

    /** "SOJA", "Soja" and "soja" match "SOJA - Glycine max (L.) Merr." (the label before ' - ' or ' ('). */
    static boolean cropMatches(String label, String wanted) {
        String folded = BrazilianStates.fold(wanted);
        if (folded.isEmpty()) return false;
        if (BrazilianStates.fold(label).equals(folded)) return true;
        String head = label.split(" - | \\(", 2)[0];
        return BrazilianStates.fold(head).equals(folded);
    }

    static Map<String, Object> cultivar(Map<String, Object> row) {
        Map<String, Object> attributes = attributes((String) row.get("attributes"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rnc", row.get("rnc"));
        out.put("cultivar", row.get("cultivar"));
        out.put("adaptationRegion", row.get("adaptation_region"));
        out.put("group", attribute(attributes, "grupo"));
        out.put("cycle", attribute(attributes, "ciclo"));
        out.put("attributes", attributes);
        return out;
    }

    /** The flat JSON object of crop parameters; anything else is ignored. */
    static Map<String, Object> attributes(String json) {
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
        try {
            Map<String, Object> parsed = JSON.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {});
            return parsed == null ? new LinkedHashMap<>() : parsed;
        } catch (JsonProcessingException e) {
            return new LinkedHashMap<>();
        }
    }

    /** The value (as text) of the first parameter whose folded title contains {@code word}, or null. */
    static String attribute(Map<String, Object> attributes, String word) {
        for (Map.Entry<String, Object> entry : attributes.entrySet()) {
            if (!BrazilianStates.fold(entry.getKey()).contains(word)) continue;
            Object value = entry.getValue();
            if (value == null || value.toString().isBlank()) continue;
            return value.toString().trim();
        }
        return null;
    }

    private static List<String> cropsOf(List<Map<String, Object>> pairs, String season) {
        Set<String> crops = new java.util.TreeSet<>();
        for (Map<String, Object> pair : pairs) {
            if (season == null || season.equals(pair.get("season"))) crops.add(pair.get("crop").toString());
        }
        return new ArrayList<>(crops);
    }

    private static Map<String, Object> notFound(String message, String uf, List<String> crops) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("found", false);
        out.put("message", message);
        out.put("crop", null);
        out.put("season", null);
        out.put("uf", uf);
        out.put("cultivars", List.of());
        out.put("count", 0);
        out.put("distinctCultivars", 0);
        out.put("adaptationRegions", List.of());
        out.put("truncated", false);
        out.put("availableCrops", crops);
        return out;
    }

    private static String text(Object value) {
        return value == null || value.toString().isBlank() ? null : value.toString().trim();
    }
}
