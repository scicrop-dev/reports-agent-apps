package com.infinitestack.reports.blocks.brgeo;

import static com.infinitestack.reports.blocks.brgeo.BrGeoTables.Q_ZARC_RISK;
import static com.infinitestack.reports.blocks.brgeo.BrGeoTables.ZARC_RISK;
import static com.infinitestack.reports.blocks.brgeo.BrGeoTables.requirement;

import java.sql.Array;
import java.sql.SQLException;
import java.time.Duration;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.infinitestack.reports.engine.DataType;
import com.infinitestack.reports.engine.block.BlockDefinition;
import com.infinitestack.reports.engine.block.BlockDocs;
import com.infinitestack.reports.engine.block.BlockPort;
import com.infinitestack.reports.engine.block.BlockQueries;
import com.infinitestack.reports.engine.block.ExecutionMetadata;
import com.infinitestack.reports.engine.block.ReportBlock;

/**
 * {@code brgeo-zarc-risk}: the agricultural climate risk zoning (ZARC, MAPA) of one crop in a
 * municipality: for each cultivar group and soil class, the loss risk of sowing in each of the 36
 * ten-day periods of the year (0 = not indicated, 20, 30 or 40 = % chance of loss), and the sowing
 * windows that come out of it.
 *
 * <p>The table has no index on the municipality, so the Block first lists the (season, crop) pairs
 * with a skip scan over the scope index (season, crop, ...) and then reads one crop in its seasons
 * and the municipality's state, which that index narrows down.
 */
@Component
public class BrGeoZarcRiskBlock implements ReportBlock {

    static final int PERIODS = 36;
    static final int DEFAULT_LIMIT = 30;

    /** Distinct (season, crop) pairs, one index probe each (recursive skip scan). */
    static final String PAIRS_SQL = "WITH RECURSIVE p AS ("
            + " (SELECT season, crop FROM " + Q_ZARC_RISK + " ORDER BY season, crop LIMIT 1)"
            + " UNION ALL"
            + " SELECT n.season, n.crop FROM p, LATERAL (SELECT season, crop FROM " + Q_ZARC_RISK
            + " WHERE (season, crop) > (p.season, p.crop) ORDER BY season, crop LIMIT 1) n)"
            + " SELECT season, crop FROM p";

    /** {@code %s} is the list of season placeholders. */
    static final String RISK_SQL = "SELECT season, cropping_system, climate_type, ordinance, ordinance_date, municipality,"
            + " cultivar_group, soil, risk FROM " + Q_ZARC_RISK
            + " WHERE season IN (%s) AND crop = ? AND uf = ? AND cd_mun = ? AND valid_to IS NULL"
            + " ORDER BY cropping_system, climate_type, cultivar_group, soil";

    private final BlockQueries queries;

    public BrGeoZarcRiskBlock(BlockQueries queries) {
        this.queries = queries;
    }

    @Override
    public BlockDefinition definition() {
        return new BlockDefinition("brgeo-zarc-risk", "Climate risk zoning (ZARC) of a crop",
                "ZARC (MAPA): sowing risk of a crop in a municipality per ten-day period, by cultivar group and soil, with the sowing windows",
                "1.0.0",
                List.of(
                        BlockPort.required("municipalityIbge", DataType.NUMBER,
                                "IBGE municipality code (7 digits); connect car-property.municipalityIbge or brgeo-municipalities.mainIbgeCode"),
                        BlockPort.required("crop", DataType.STRING, "crop as the ZARC names it, e.g. Soja, Milho 2ª safra, Trigo (accents and case ignored)"),
                        BlockPort.optional("season", DataType.STRING, "ZARC season, e.g. 2026/2027; default the latest with the crop in the municipality"),
                        BlockPort.optional("soil", DataType.STRING, "only this soil class (e.g. AD3); default all"),
                        BlockPort.optional("cultivarGroup", DataType.STRING, "only this cultivar group (e.g. Grupo II); default all"),
                        BlockPort.optional("limit", DataType.NUMBER, "how many rows (group x soil x management) to return; default 30")),
                List.of(
                        BlockPort.required("found", DataType.BOOLEAN, "the ZARC has the crop for the municipality"),
                        BlockPort.optional("message", DataType.STRING, "why nothing was found"),
                        BlockPort.optional("crop", DataType.STRING, "the crop as the ZARC names it"),
                        BlockPort.optional("season", DataType.STRING, "the season used"),
                        BlockPort.optional("uf", DataType.STRING, "state of the municipality"),
                        BlockPort.optional("municipality", DataType.STRING, "municipality name as the ZARC prints it"),
                        BlockPort.optional("ordinance", DataType.STRING, "MAPA ordinance number"),
                        BlockPort.optional("ordinanceDate", DataType.DATE, "ordinance date"),
                        BlockPort.required("rows", DataType.ARRAY,
                                "one per management x cultivar group x soil: {croppingSystem, climateType, cultivarGroup, soil, risk [36 numbers],"
                                        + " cells [36 {period, risk, label}], indicatedPeriods, lowestRisk, windows [{risk, fromPeriod, toPeriod, from, to, label}]}"),
                        BlockPort.required("count", DataType.NUMBER, "rows found (before the limit)"),
                        BlockPort.required("truncated", DataType.BOOLEAN, "more rows than the limit"),
                        BlockPort.required("periods", DataType.ARRAY, "the 36 ten-day periods, for a table header: {period, from, to, label}"),
                        BlockPort.required("availableCrops", DataType.ARRAY, "crops the ZARC has in the season (any state), to pick from")),
                List.of(requirement(ZARC_RISK, "season", "crop", "cropping_system", "climate_type", "uf", "ordinance",
                        "ordinance_date", "cd_mun", "municipality", "cultivar_group", "soil", "risk", "valid_to")),
                BrGeoValues.BLOCK_TIMEOUT);
    }

    @Override
    public BlockDocs docs() {
        return new BlockDocs("BR Geo",
                "Says when a crop can be sown in a municipality with an acceptable climate risk, as the official "
                        + "agricultural climate risk zoning (ZARC) of MAPA publishes it: by cultivar group and soil class, "
                        + "the loss risk of sowing in each ten-day period of the year.",
                List.of(
                        "Lists the seasons and crops the ZARC has and matches the crop given, ignoring accents and case.",
                        "Takes the season given or the latest one with the crop for the municipality (calendar seasons such as 2026/2027 "
                                + "first, then 'Sem Safra', 'Perenes'...).",
                        "Reads the risk table of the municipality: one row per management, climate type, cultivar group and soil class (AD1 to AD6).",
                        "Turns each row's 36 ten-day periods into sowing windows: runs of consecutive periods with the same risk, "
                                + "joined across the turn of the year."),
                List.of(
                        "municipalityIbge: connect car-property.municipalityIbge or brgeo-municipalities.mainIbgeCode.",
                        "crop: a fixed value (Soja) or a report input; availableCrops lists the names the ZARC uses.",
                        "soil / cultivarGroup: fix them when the report is about one field whose soil and cultivar are known.",
                        "Windows read well as text: {{#rows}}{{cultivarGroup}} {{soil}}: {{#windows}}{{label}}; {{/windows}}{{/rows}}. "
                                + "For a 36-column table use periods for the header and cells in each row."),
                List.of(
                        "Risk 20, 30 or 40 is the probability (%) of losing the crop to weather when sown in that period; 0 means "
                                + "the period is not indicated. Insurance and PROAGRO follow the ZARC.",
                        "The ZARC is by municipality, not by field: the soil class (AD1 to AD6, available water) of the field "
                                + "decides which row applies.",
                        "Only the states and seasons that BR Geo loaded (BRGEO_STATES) are present.",
                        "Ten-day periods: 1 is 1-10 Jan, 2 is 11-20 Jan, 3 is 21-31 Jan, and so on to 36 (21-31 Dec)."),
                "MAPA, Zoneamento Agrícola de Risco Climático (SISZARC), loaded into gold.br_geo_agent_app_zarc_risk",
                "{{#<node>.found}}ZARC {{<node>.crop}} {{<node>.season}} (Portaria {{<node>.ordinance}}):"
                        + "{{#<node>.rows}}<br>{{cultivarGroup}} {{soil}}: {{#windows}}{{label}}; {{/windows}}{{/<node>.rows}}{{/<node>.found}}"
                        + "{{^<node>.found}}{{<node>.message}}{{/<node>.found}}");
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> inputs, ExecutionMetadata metadata) {
        String cdMun = String.valueOf(((Number) inputs.get("municipalityIbge")).longValue());
        if (cdMun.length() != 7) throw new IllegalArgumentException("municipalityIbge must have 7 digits, got " + cdMun);
        String uf = BrazilianStates.ofMunicipality(cdMun)
                .orElseThrow(() -> new IllegalArgumentException("municipalityIbge " + cdMun + " is of no Brazilian state")).uf();
        String crop = text(inputs.get("crop"));
        if (crop == null) throw new IllegalArgumentException("crop is required");
        String season = text(inputs.get("season"));
        String soil = text(inputs.get("soil"));
        String group = text(inputs.get("cultivarGroup"));
        int limit = inputs.get("limit") instanceof Number n ? Math.max(1, n.intValue()) : DEFAULT_LIMIT;

        return queries.read(BrGeoValues.QUERY_TIMEOUT, jdbc -> {
            List<Map<String, Object>> pairs = jdbc.queryForList(PAIRS_SQL);
            String zarcCrop = null;
            List<String> seasons = new ArrayList<>();
            for (Map<String, Object> pair : pairs) {
                String pairCrop = pair.get("crop").toString();
                if (!BrazilianStates.fold(pairCrop).equals(BrazilianStates.fold(crop))) continue;
                String pairSeason = pair.get("season").toString();
                if (season != null && !BrazilianStates.fold(pairSeason).equals(BrazilianStates.fold(season))) continue;
                zarcCrop = pairCrop;
                seasons.add(pairSeason);
            }
            seasons.sort(SEASONS_NEWEST_FIRST);
            String latestSeason = pairs.stream().map(p -> p.get("season").toString()).sorted(SEASONS_NEWEST_FIRST).findFirst().orElse(null);

            if (zarcCrop == null) {
                String in = season == null ? "" : " in season " + season;
                return notFound("the ZARC has no crop '" + crop + "'" + in, uf, cropsOf(pairs, season != null ? season : latestSeason));
            }

            String placeholders = String.join(", ", Collections.nCopies(seasons.size(), "?"));
            List<Object> args = new ArrayList<>(seasons);
            args.add(zarcCrop);
            args.add(uf);
            args.add(cdMun);
            List<Map<String, Object>> rows = jdbc.query(String.format(RISK_SQL, placeholders), (rs, i) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("season", rs.getString("season"));
                row.put("croppingSystem", rs.getString("cropping_system"));
                row.put("climateType", rs.getString("climate_type"));
                row.put("ordinance", rs.getString("ordinance"));
                row.put("ordinanceDate", BrGeoValues.isoDate(rs, "ordinance_date"));
                row.put("municipality", rs.getString("municipality"));
                row.put("cultivarGroup", rs.getString("cultivar_group"));
                row.put("soil", rs.getString("soil"));
                row.put("risk", risk(rs.getArray("risk")));
                return row;
            }, args.toArray());

            String used = seasons.stream().filter(s -> rows.stream().anyMatch(r -> s.equals(r.get("season")))).findFirst().orElse(null);
            List<Map<String, Object>> chosen = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                if (!row.get("season").equals(used)) continue;
                if (soil != null && !BrazilianStates.fold((String) row.get("soil")).equals(BrazilianStates.fold(soil))) continue;
                if (group != null && !BrazilianStates.fold((String) row.get("cultivarGroup")).equals(BrazilianStates.fold(group))) continue;
                chosen.add(row);
            }
            if (chosen.isEmpty()) {
                String why = used == null
                        ? "the ZARC of " + zarcCrop + (season == null ? "" : " " + season) + " does not cover municipality " + cdMun + " (" + uf + ")"
                        : "no ZARC row of " + zarcCrop + " " + used + " in municipality " + cdMun + " matches the soil / cultivar group given";
                return notFound(why, uf, cropsOf(pairs, used != null ? used : seasons.get(0)));
            }

            Map<String, Object> first = chosen.get(0);
            List<Map<String, Object>> outRows = new ArrayList<>();
            for (Map<String, Object> row : chosen.subList(0, Math.min(limit, chosen.size()))) outRows.add(describe(row));

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("found", true);
            out.put("message", null);
            out.put("crop", zarcCrop);
            out.put("season", used);
            out.put("uf", uf);
            out.put("municipality", first.get("municipality"));
            out.put("ordinance", first.get("ordinance"));
            out.put("ordinanceDate", first.get("ordinanceDate"));
            out.put("rows", outRows);
            out.put("count", chosen.size());
            out.put("truncated", chosen.size() > limit);
            out.put("periods", periods());
            out.put("availableCrops", cropsOf(pairs, used));
            return out;
        });
    }

    /** Calendar seasons (2026/2027) newest first, then the others ("Sem Safra", "Perenes") by name. */
    static final Comparator<String> SEASONS_NEWEST_FIRST = (a, b) -> {
        boolean ya = a.matches("\\d{4}.*");
        boolean yb = b.matches("\\d{4}.*");
        if (ya != yb) return ya ? -1 : 1;
        return ya ? b.compareTo(a) : a.compareTo(b);
    };

    private static List<String> cropsOf(List<Map<String, Object>> pairs, String season) {
        Set<String> crops = new LinkedHashSet<>();
        for (Map<String, Object> pair : pairs) {
            if (season == null || season.equals(pair.get("season"))) crops.add(pair.get("crop").toString());
        }
        return crops.stream().sorted().toList();
    }

    private static Map<String, Object> notFound(String message, String uf, List<String> crops) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("found", false);
        out.put("message", message);
        for (String name : List.of("crop", "season")) out.put(name, null);
        out.put("uf", uf);
        for (String name : List.of("municipality", "ordinance", "ordinanceDate")) out.put(name, null);
        out.put("rows", List.of());
        out.put("count", 0);
        out.put("truncated", false);
        out.put("periods", periods());
        out.put("availableCrops", crops);
        return out;
    }

    /** One row of the risk table with its cells and sowing windows. */
    static Map<String, Object> describe(Map<String, Object> row) {
        @SuppressWarnings("unchecked")
        List<Integer> risk = (List<Integer>) row.get("risk");
        List<Map<String, Object>> cells = new ArrayList<>();
        int indicated = 0;
        Integer lowest = null;
        for (int p = 0; p < PERIODS; p++) {
            int r = p < risk.size() && risk.get(p) != null ? risk.get(p) : 0;
            if (r > 0) {
                indicated++;
                lowest = lowest == null ? r : Math.min(lowest, r);
            }
            Map<String, Object> cell = new LinkedHashMap<>();
            cell.put("period", p + 1);
            cell.put("risk", r);
            cell.put("label", r > 0 ? r + "%" : "-");
            cells.add(cell);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("croppingSystem", row.get("croppingSystem"));
        out.put("climateType", row.get("climateType"));
        out.put("cultivarGroup", row.get("cultivarGroup"));
        out.put("soil", row.get("soil"));
        out.put("risk", risk);
        out.put("cells", cells);
        out.put("indicatedPeriods", indicated);
        out.put("lowestRisk", lowest);
        out.put("windows", windows(risk));
        return out;
    }

    /**
     * Runs of consecutive periods with the same non-zero risk. A run that reaches period 36 and one that
     * starts at period 1 are the same window across the turn of the year (summer crops: Oct to Jan).
     */
    static List<Map<String, Object>> windows(List<Integer> risk) {
        int[] r = new int[PERIODS];
        for (int p = 0; p < PERIODS && p < risk.size(); p++) r[p] = risk.get(p) == null ? 0 : risk.get(p);
        // Start scanning right after a change, so a window across December/January is not cut in two.
        int start = 0;
        for (int p = 0; p < PERIODS; p++) {
            if (r[p] != r[(p + PERIODS - 1) % PERIODS]) {
                start = p;
                break;
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        int p = 0;
        while (p < PERIODS) {
            int from = (start + p) % PERIODS;
            int value = r[from];
            int length = 1;
            while (p + length < PERIODS && r[(start + p + length) % PERIODS] == value) length++;
            if (value > 0) {
                int to = (from + length - 1) % PERIODS;
                Map<String, Object> window = new LinkedHashMap<>();
                window.put("risk", value);
                window.put("fromPeriod", from + 1);
                window.put("toPeriod", to + 1);
                window.put("from", periodStart(from + 1));
                window.put("to", periodEnd(to + 1));
                window.put("label", periodStart(from + 1) + " a " + periodEnd(to + 1) + " (" + value + "%)");
                out.add(window);
            }
            p += length;
        }
        out.sort(Comparator.comparingInt(w -> (Integer) w.get("fromPeriod")));
        return out;
    }

    static List<Map<String, Object>> periods() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int p = 1; p <= PERIODS; p++) {
            Map<String, Object> period = new LinkedHashMap<>();
            period.put("period", p);
            period.put("from", periodStart(p));
            period.put("to", periodEnd(p));
            period.put("label", periodStart(p) + "-" + periodEnd(p));
            out.add(period);
        }
        return out;
    }

    /** dd/MM of the first day of a ten-day period (1 to 36). */
    static String periodStart(int period) {
        int month = (period - 1) / 3 + 1;
        int day = new int[] {1, 11, 21}[(period - 1) % 3];
        return String.format("%02d/%02d", day, month);
    }

    /** dd/MM of the last day of a ten-day period; the third one ends with the month (28 Feb, any year). */
    static String periodEnd(int period) {
        int month = (period - 1) / 3 + 1;
        int third = (period - 1) % 3;
        int day = third == 0 ? 10 : third == 1 ? 20 : YearMonth.of(2025, month).lengthOfMonth();
        return String.format("%02d/%02d", day, month);
    }

    private static List<Integer> risk(Array array) throws SQLException {
        List<Integer> out = new ArrayList<>();
        if (array == null) return out;
        for (Object value : (Object[]) array.getArray()) out.add(value instanceof Number n ? n.intValue() : 0);
        return out;
    }

    private static String text(Object value) {
        return value == null || value.toString().isBlank() ? null : value.toString().trim();
    }
}
