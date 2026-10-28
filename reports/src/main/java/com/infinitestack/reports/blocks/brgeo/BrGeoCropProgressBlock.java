package com.infinitestack.reports.blocks.brgeo;

import static com.infinitestack.reports.blocks.brgeo.BrGeoTables.CONAB_PROGRESS;
import static com.infinitestack.reports.blocks.brgeo.BrGeoTables.Q_CONAB_PROGRESS;
import static com.infinitestack.reports.blocks.brgeo.BrGeoTables.requirement;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
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
 * {@code brgeo-crop-progress}: how far sowing and harvest have gone in a state, from the weekly
 * CONAB crop progress survey: the share done in the latest week per crop, against the week before,
 * the same week last year and the 5-year average.
 *
 * <p>CONAB names states in full ("Mato Grosso"); the input takes the abbreviation the CAR gives.
 * The table is small (one value per crop x season x operation x state x week), so it is read whole
 * for one state.
 */
@Component
public class BrGeoCropProgressBlock implements ReportBlock {

    static final int DEFAULT_MAX_AGE_DAYS = 60;
    /** A campaign never spans more than this: older points of the same season label are last year's. */
    static final int SERIES_DAYS = 250;

    static final String STATES_SQL = "SELECT DISTINCT state FROM " + Q_CONAB_PROGRESS + " WHERE valid_to IS NULL";

    static final String PROGRESS_SQL = "SELECT crop, season, operation, measure, week_end, pct_done FROM " + Q_CONAB_PROGRESS
            + " WHERE state = ? AND valid_to IS NULL AND pct_done IS NOT NULL"
            + " AND week_end >= (SELECT max(week_end) FROM " + Q_CONAB_PROGRESS + " WHERE valid_to IS NULL) - 400"
            + " ORDER BY crop, season, operation, week_end";

    static final Map<String, String> OPERATIONS = Map.of("sowing", "Semeadura", "harvest", "Colheita");

    private final BlockQueries queries;

    public BrGeoCropProgressBlock(BlockQueries queries) {
        this.queries = queries;
    }

    @Override
    public BlockDefinition definition() {
        return new BlockDefinition("brgeo-crop-progress", "Sowing and harvest progress (CONAB)",
                "CONAB weekly crop progress in a state: % sown or harvested per crop, against last week, last year and the 5-year average",
                "1.0.0",
                List.of(
                        BlockPort.required("uf", DataType.STRING, "state abbreviation (MT) or name; connect car-property.uf"),
                        BlockPort.optional("crop", DataType.STRING, "only crops whose name contains this (e.g. Soja, Milho); default all"),
                        BlockPort.optional("operation", DataType.STRING, "sowing or harvest; default both"),
                        BlockPort.optional("maxAgeDays", DataType.NUMBER,
                                "drop operations whose latest week is older than this, counted from the latest survey; default 60")),
                List.of(
                        BlockPort.required("found", DataType.BOOLEAN, "CONAB has recent progress for the state"),
                        BlockPort.optional("message", DataType.STRING, "why nothing was found"),
                        BlockPort.optional("state", DataType.STRING, "state name as CONAB prints it"),
                        BlockPort.optional("latestWeekEnd", DataType.DATE, "end of the latest week CONAB surveyed (any state)"),
                        BlockPort.required("items", DataType.ARRAY,
                                "one per crop x operation, latest first: {crop, season, operation, operationLabel, weekEnd, pctDone,"
                                        + " previousWeekPct, weekChangePoints, lastYearPct, avg5yPct, vsAvg5yPoints, series [{weekEnd, pctDone}]}"),
                        BlockPort.required("count", DataType.NUMBER, "number of items"),
                        BlockPort.required("availableStates", DataType.ARRAY, "states CONAB surveys")),
                List.of(requirement(CONAB_PROGRESS, "crop", "season", "operation", "state", "measure", "week_end", "pct_done", "valid_to")),
                BrGeoValues.BLOCK_TIMEOUT);
    }

    @Override
    public BlockDocs docs() {
        return new BlockDocs("BR Geo",
                "Shows where the season stands in the state of an area: how much of each crop CONAB says was sown or "
                        + "harvested by the latest week, compared with the week before, last year and the 5-year average.",
                List.of(
                        "Finds the state in CONAB's list by abbreviation or name (CONAB covers the main producing states only).",
                        "Reads the last 400 days of the weekly survey for the state.",
                        "For each crop and operation keeps the newest season and its latest week: % done, the previous week, "
                                + "the same week a year before and the 5-year average CONAB prints for that week.",
                        "Drops operations not surveyed in the last maxAgeDays days (a harvest that ended months ago)."),
                List.of(
                        "uf: connect car-property.uf, or a report input.",
                        "crop: a fixed value to show one crop (Soja) or empty for all.",
                        "In the template: {{#<node>.items}}{{crop}} {{operationLabel}}: {{pctDone}}% (média 5 anos {{avg5yPct}}%){{/<node>.items}}."),
                List.of(
                        "It describes the state, not the property: a regional reference for the calendar of the area.",
                        "CONAB surveys only the main producing states of each crop; others come empty.",
                        "Percentages are of the area CONAB expects for the crop in the state, in the week that ends on weekEnd.",
                        "The 5-year average is CONAB's, for the same week; it is empty when CONAB did not print it."),
                "CONAB, Progresso de Safra (weekly), loaded into gold.br_geo_agent_app_conab_crop_progress",
                "{{#<node>.items}}{{crop}} {{season}}, {{operationLabel}}: {{pctDone}}% em {{weekEnd}}<br>{{/<node>.items}}");
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> inputs, ExecutionMetadata metadata) {
        String ufInput = inputs.get("uf") == null ? null : inputs.get("uf").toString().trim();
        if (ufInput == null || ufInput.isEmpty()) throw new IllegalArgumentException("uf is required");
        String crop = inputs.get("crop") instanceof String s && !s.isBlank() ? BrazilianStates.fold(s) : null;
        String operation = operation(inputs.get("operation"));
        int maxAge = inputs.get("maxAgeDays") instanceof Number n ? Math.max(1, n.intValue()) : DEFAULT_MAX_AGE_DAYS;

        return queries.read(BrGeoValues.QUERY_TIMEOUT, jdbc -> {
            List<String> states = jdbc.queryForList(STATES_SQL, String.class).stream().sorted().toList();
            String wanted = BrazilianStates.find(ufInput).map(BrazilianStates.State::name).orElse(ufInput);
            String state = states.stream().filter(s -> BrazilianStates.fold(s).equals(BrazilianStates.fold(wanted))).findFirst().orElse(null);
            if (state == null) return notFound("CONAB has no crop progress for " + ufInput, null, null, states);

            Map<List<String>, List<Map<String, Object>>> groups = new LinkedHashMap<>();
            LocalDate latest = null;
            for (Map<String, Object> row : jdbc.queryForList(PROGRESS_SQL, state)) {
                LocalDate weekEnd = LocalDate.parse(row.get("week_end").toString());
                if (latest == null || weekEnd.isAfter(latest)) latest = weekEnd;
                List<String> key = List.of(row.get("crop").toString(), row.get("season").toString(), row.get("operation").toString());
                groups.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
            }
            if (latest == null) return notFound("CONAB has no recent crop progress for " + state, state, null, states);

            // newest season per crop x operation
            Map<List<String>, Map<String, Object>> items = new LinkedHashMap<>();
            for (Map.Entry<List<String>, List<Map<String, Object>>> group : groups.entrySet()) {
                String groupCrop = group.getKey().get(0);
                String groupOperation = group.getKey().get(2);
                if (crop != null && !BrazilianStates.fold(groupCrop).contains(crop)) continue;
                if (operation != null && !operation.equals(groupOperation)) continue;
                Map<String, Object> item = item(group.getKey(), group.getValue());
                if (item == null) continue;
                LocalDate weekEnd = LocalDate.parse((String) item.get("weekEnd"));
                if (ChronoUnit.DAYS.between(weekEnd, latest) > maxAge) continue;
                List<String> slot = List.of(groupCrop, groupOperation);
                Map<String, Object> current = items.get(slot);
                if (current == null || weekEnd.isAfter(LocalDate.parse((String) current.get("weekEnd")))) items.put(slot, item);
            }
            List<Map<String, Object>> list = new ArrayList<>(items.values());
            list.sort(Comparator.comparing((Map<String, Object> i) -> (String) i.get("weekEnd")).reversed()
                    .thenComparing(i -> (String) i.get("crop")).thenComparing(i -> (String) i.get("operation"), Comparator.reverseOrder()));
            if (list.isEmpty()) {
                return notFound("CONAB has no crop progress for " + state + " in the last " + maxAge + " days"
                        + (crop == null ? "" : " for " + inputs.get("crop")), state, latest.toString(), states);
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("found", true);
            out.put("message", null);
            out.put("state", state);
            out.put("latestWeekEnd", latest.toString());
            out.put("items", list);
            out.put("count", list.size());
            out.put("availableStates", states);
            return out;
        });
    }

    /** The latest week of one crop x season x operation, with its comparisons; null without a % done. */
    static Map<String, Object> item(List<String> key, List<Map<String, Object>> rows) {
        TreeMap<LocalDate, Double> done = new TreeMap<>();
        Map<LocalDate, Double> average = new TreeMap<>();
        for (Map<String, Object> row : rows) {
            LocalDate weekEnd = LocalDate.parse(row.get("week_end").toString());
            double value = ((Number) row.get("pct_done")).doubleValue();
            if ("avg_5y".equals(row.get("measure"))) average.put(weekEnd, value);
            else done.put(weekEnd, value);
        }
        if (done.isEmpty()) return null;
        LocalDate last = done.lastKey();
        Double current = done.get(last);
        Map.Entry<LocalDate, Double> previous = done.lowerEntry(last);
        Double previousPct = previous != null && ChronoUnit.DAYS.between(previous.getKey(), last) <= 10 ? previous.getValue() : null;
        Double lastYear = null;
        for (Map.Entry<LocalDate, Double> entry : done.entrySet()) {
            long days = ChronoUnit.DAYS.between(entry.getKey(), last);
            if (days >= 357 && days <= 371) lastYear = entry.getValue();
        }
        Double avg = average.get(last);
        List<Map<String, Object>> series = new ArrayList<>();
        for (Map.Entry<LocalDate, Double> entry : done.entrySet()) {
            if (ChronoUnit.DAYS.between(entry.getKey(), last) > SERIES_DAYS) continue;
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("weekEnd", entry.getKey().toString());
            point.put("pctDone", pct(entry.getValue()));
            series.add(point);
        }

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("crop", key.get(0));
        item.put("season", key.get(1));
        item.put("operation", key.get(2));
        item.put("operationLabel", OPERATIONS.getOrDefault(key.get(2), key.get(2)));
        item.put("weekEnd", last.toString());
        item.put("pctDone", pct(current));
        item.put("previousWeekPct", pct(previousPct));
        item.put("weekChangePoints", previousPct == null ? null : points(current - previousPct));
        item.put("lastYearPct", pct(lastYear));
        item.put("avg5yPct", pct(avg));
        item.put("vsAvg5yPoints", avg == null ? null : points(current - avg));
        item.put("series", series);
        return item;
    }

    private static Map<String, Object> notFound(String message, String state, String latest, List<String> states) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("found", false);
        out.put("message", message);
        out.put("state", state);
        out.put("latestWeekEnd", latest);
        out.put("items", List.of());
        out.put("count", 0);
        out.put("availableStates", states);
        return out;
    }

    static String operation(Object value) {
        if (!(value instanceof String s) || s.isBlank()) return null;
        String folded = BrazilianStates.fold(s);
        if (folded.startsWith("sow") || folded.startsWith("semea") || folded.startsWith("plant")) return "sowing";
        if (folded.startsWith("harv") || folded.startsWith("colh")) return "harvest";
        throw new IllegalArgumentException("operation must be sowing or harvest, got " + s);
    }

    /** CONAB stores fractions (0.452): percentages with 1 decimal. */
    private static Double pct(Double fraction) {
        return fraction == null ? null : BrGeoValues.round(fraction * 100, 1);
    }

    private static Double points(double fractionDifference) {
        return BrGeoValues.round(fractionDifference * 100, 1);
    }
}
