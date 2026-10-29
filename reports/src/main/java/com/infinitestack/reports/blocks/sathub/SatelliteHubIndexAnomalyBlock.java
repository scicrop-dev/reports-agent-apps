package com.infinitestack.reports.blocks.sathub;

import java.awt.Color;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.infinitestack.reports.engine.DataType;
import com.infinitestack.reports.engine.block.BlockDefinition;
import com.infinitestack.reports.engine.block.BlockDocs;
import com.infinitestack.reports.engine.block.BlockPort;
import com.infinitestack.reports.engine.block.BlockQueries;
import com.infinitestack.reports.engine.block.DataRequirement;
import com.infinitestack.reports.engine.block.ExecutionMetadata;
import com.infinitestack.reports.engine.block.ReportBlock;

/**
 * {@code sathub-index-anomaly}: sudden drops in a Satellite Hub index series (harvest, fire, clearing,
 * drought stress). Each image is compared with a baseline, the rolling median of the observations
 * before it; a value at least {@code dropThreshold} below the baseline is a drop. With two years or
 * more of history it also compares the latest image with the same month of the earlier years.
 */
@Component
public class SatelliteHubIndexAnomalyBlock implements ReportBlock {

    static final double DEFAULT_DROP_THRESHOLD = 0.15;
    static final int DEFAULT_BASELINE_OBSERVATIONS = 5;
    static final int MAX_BASELINE_OBSERVATIONS = 30;
    /** A baseline needs at least this many earlier observations (fewer when baselineObservations is smaller). */
    static final int MIN_BASELINE_OBSERVATIONS = 3;
    static final int SEASONAL_MIN_YEARS = 2;
    static final int CHART_WIDTH = 900;
    static final int CHART_HEIGHT = 320;

    private static final Color VALUE = new Color(0x2E7D32);
    private static final Color BASELINE = new Color(0x757575);
    private static final Color DROP = new Color(0xC62828);

    private final BlockQueries queries;
    private final IndexSeriesChart chart = new IndexSeriesChart();

    public SatelliteHubIndexAnomalyBlock(BlockQueries queries) {
        this.queries = queries;
    }

    @Override
    public BlockDefinition definition() {
        return new BlockDefinition("sathub-index-anomaly", "Index drops (Satellite Hub)",
                "Sudden drops of a Satellite Hub index (e.g. NDVI) against the rolling median of the previous images, "
                        + "with the latest status, the drop events and a chart",
                "1.0.0",
                List.of(
                        BlockPort.optional("geometry", DataType.GEOMETRY,
                                "area whose Satellite Hub fields are combined; connect car-property-geometry.geometry"),
                        BlockPort.optional("areaId", DataType.NUMBER, "one Satellite Hub area by id, instead of a geometry"),
                        BlockPort.optional("index", DataType.STRING, "index slug; default ndvi"),
                        BlockPort.optional("days", DataType.NUMBER,
                                "length of the period shown, counted back from 'to' or, without it, from the latest image; default 365"),
                        BlockPort.optional("from", DataType.DATE, "first day of the period shown; when given, 'days' is ignored"),
                        BlockPort.optional("to", DataType.DATE, "last day of the period; default up to the latest image"),
                        BlockPort.optional("dropThreshold", DataType.NUMBER,
                                "a drop is a value at least this much below the baseline, in index units (absolute); default 0.15"),
                        BlockPort.optional("baselineObservations", DataType.NUMBER,
                                "the baseline is the median of this many previous images (1 to 30); default 5"),
                        BlockPort.optional("minValidPercent", DataType.NUMBER,
                                "drop images with less than this % of valid (cloud-free) area; default 50")),
                List.of(
                        BlockPort.required("index", DataType.OBJECT, "{slug, name, description, collection, collectionName, enabled}"),
                        BlockPort.required("points", DataType.ARRAY,
                                "one per image in the period, oldest first: {date, value, baseline, delta, isDrop}; baseline and delta are null "
                                        + "while there are not enough earlier images"),
                        BlockPort.required("count", DataType.NUMBER, "number of points in the period"),
                        BlockPort.required("events", DataType.ARRAY, "the drops in the period, oldest first: {date, value, baseline, delta}"),
                        BlockPort.required("eventCount", DataType.NUMBER, "number of drops in the period"),
                        BlockPort.required("hasDrops", DataType.BOOLEAN, "at least one drop in the period"),
                        BlockPort.optional("lastDropDate", DataType.DATE, "date of the latest drop in the period"),
                        BlockPort.required("latestStatus", DataType.STRING,
                                "normal, drop (the latest image is a drop) or no-data (no image in the period)"),
                        BlockPort.optional("latestDate", DataType.DATE, "date of the latest image in the period"),
                        BlockPort.optional("latestValue", DataType.NUMBER, "index value of the latest image"),
                        BlockPort.optional("latestDelta", DataType.NUMBER, "latest value minus its baseline"),
                        BlockPort.optional("seasonalBaseline", DataType.OBJECT,
                                "with 2 years of history or more: {month, years [{year, mean}], mean, observations, latestValue, anomaly}; "
                                        + "anomaly = latest value minus the mean of the same month in the earlier years"),
                        BlockPort.required("hasSeasonalBaseline", DataType.BOOLEAN, "seasonalBaseline was computed"),
                        BlockPort.required("areas", DataType.ARRAY,
                                "Satellite Hub areas used, largest share first (up to 50): {id, farmName, blockName, parcelName, areaHa, insideHa}"),
                        BlockPort.required("areaCount", DataType.NUMBER, "number of areas used"),
                        BlockPort.required("chart", DataType.STRING,
                                "PNG chart (value line, dashed baseline, drops circled in red) as a data:image/png;base64 URL"),
                        BlockPort.required("truncated", DataType.BOOLEAN, "more than 50 areas in the geometry")),
                List.of(
                        DataRequirement.table(SatelliteHubSeriesData.SCHEMA, SatelliteHubSeriesData.AREAS,
                                "id", "farm_name", "block_name", "parcel_name", "area_ha", "geom"),
                        DataRequirement.table(SatelliteHubSeriesData.SCHEMA, SatelliteHubSeriesData.INDICES,
                                "id", "slug", "name", "description", "collection", "enabled"),
                        DataRequirement.table(SatelliteHubSeriesData.SCHEMA, SatelliteHubSeriesData.VALUES, "index_id", "area_id",
                                "acquired_at", "mean", "min_value", "max_value", "p10", "p90", "valid_pixels", "valid_area_pct")),
                Duration.ofSeconds(30));
    }

    @Override
    public BlockDocs docs() {
        return new BlockDocs("Satellite Hub",
                "Flags sudden drops of a vegetation index (NDVI by default) in the Satellite Hub series of an area: each image is "
                        + "compared with the median of the images before it. Answers \"did the vegetation drop, when, and is it down now?\" "
                        + "with a chart that circles the drops.",
                List.of(
                        "Finds the Satellite Hub areas that intersect the geometry, or takes the one given by areaId, as sathub-index-series does.",
                        "Reads the whole history of the index up to 'to' (mean weighted by valid pixels per date, images under minValidPercent of clear area dropped), so the baseline at the start of the period already has earlier images.",
                        "For each image, the baseline is the median of the baselineObservations images before it (at least 3 of them, or all when baselineObservations is smaller); the image itself never enters its own baseline.",
                        "delta = value - baseline; a drop is delta <= -dropThreshold (absolute index units, 0.15 by default).",
                        "Shows the images between 'from' and 'to' or, by default, of the last 'days' days; latestStatus says whether the latest of them is a drop.",
                        "With at least 2 years of history before the latest image, also averages the same calendar month of the earlier years (seasonalBaseline) and gives the anomaly of the latest image against it."),
                List.of(
                        "geometry: connect car-property-geometry.geometry; or areaId as a report input for one field.",
                        "index: ndvi (default) or another slug; on the radar rvi_s1 (0 to 4) raise dropThreshold accordingly.",
                        "Test {{#<node>.hasDrops}} to list {{#<node>.events}}{{date}}: {{delta}}{{/<node>.events}}; {{<node>.latestStatus}} feeds a status badge.",
                        "chart goes straight into the template: <img src=\"{{<node>.chart}}\">."),
                List.of(
                        "A drop says the index fell, not why: harvest and mowing look the same as clearing, fire or stress. Read it with the crop calendar.",
                        "Undetected clouds, haze and shadows lower optical indices and can look like a one-image drop; a real drop usually persists over the next images.",
                        "The baseline follows the series: after a lasting change (e.g. harvest) it settles at the new level within about half of baselineObservations images and the following images stop being drops.",
                        "The threshold is absolute: 0.15 of NDVI is a lot on sparse vegetation and little on a dense canopy.",
                        "The seasonal baseline needs 2 years of history and has as many years as Satellite Hub processed; one year with a different crop shifts it."),
                "Satellite Hub agent app (Sentinel-2 L2A, Landsat 8/9 C2 L2, Sentinel-1 RTC), tables gold.satellite_hub_areas, _analytics_indices and _analytics_values",
                "<img src=\"{{<node>.chart}}\">{{#<node>.hasDrops}}<p>Última queda: {{<node>.lastDropDate}}</p>{{/<node>.hasDrops}}"
                        + "<p>Situação atual: {{<node>.latestStatus}}</p>");
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> inputs, ExecutionMetadata metadata) {
        SatelliteHubSeriesData.Window window = SatelliteHubSeriesData.Window.of(inputs);
        String slug = inputs.get("index") instanceof String s && !s.isBlank() ? s.trim().toLowerCase(Locale.ROOT) : "ndvi";
        double threshold = inputs.get("dropThreshold") instanceof Number n && n.doubleValue() > 0 ? n.doubleValue() : DEFAULT_DROP_THRESHOLD;
        int observations = inputs.get("baselineObservations") instanceof Number n
                ? Math.max(1, Math.min(MAX_BASELINE_OBSERVATIONS, n.intValue())) : DEFAULT_BASELINE_OBSERVATIONS;

        return queries.read(Duration.ofSeconds(25), jdbc -> {
            List<Map<String, Object>> index = SatelliteHubSeriesData.indices(jdbc, List.of(slug), false);
            if (index.isEmpty()) throw new IllegalArgumentException("Satellite Hub has no index '" + slug + "'");
            SatelliteHubSeriesData.Areas areas = SatelliteHubSeriesData.areas(jdbc, window);
            List<SatelliteHubSeriesData.Point> history = SatelliteHubSeriesData.series(jdbc, index.get(0).get("id"), areas.ids(),
                    window.wholeHistory());

            Map<String, Object> out = analyse(history, window, threshold, observations);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("index", SatelliteHubSeriesData.indexInfo(index.get(0)));
            result.putAll(out);
            result.put("areas", areas.items());
            result.put("areaCount", areas.items().size());
            result.put("chart", chart(out, index.get(0).get("name"), observations));
            result.put("truncated", areas.truncated());
            return result;
        });
    }

    /** Every output but the index, the areas and the chart, from the whole history (oldest first). */
    static Map<String, Object> analyse(List<SatelliteHubSeriesData.Point> history, SatelliteHubSeriesData.Window window,
                                       double threshold, int observations) {
        List<Double> values = history.stream().map(SatelliteHubSeriesData.Point::mean).toList();
        List<Double> baselines = SeriesMath.rollingMedian(values, observations, Math.min(MIN_BASELINE_OBSERVATIONS, observations));
        LocalDate start = periodStart(history, window);

        List<Map<String, Object>> points = new ArrayList<>();
        List<Map<String, Object>> events = new ArrayList<>();
        for (int i = 0; i < history.size(); i++) {
            LocalDate date = history.get(i).date();
            if (start != null && date.isBefore(start) || window.to() != null && date.isAfter(window.to())) continue;
            Double baseline = SatelliteHubSeriesData.round(baselines.get(i), 4);
            Double delta = baseline == null ? null : SatelliteHubSeriesData.round(values.get(i) - baselines.get(i), 4);
            boolean drop = delta != null && values.get(i) - baselines.get(i) <= -threshold;
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("date", date.toString());
            point.put("value", values.get(i));
            point.put("baseline", baseline);
            point.put("delta", delta);
            point.put("isDrop", drop);
            points.add(point);
            if (drop) {
                Map<String, Object> event = new LinkedHashMap<>(point);
                event.remove("isDrop");
                events.add(event);
            }
        }

        Map<String, Object> last = points.isEmpty() ? null : points.get(points.size() - 1);
        Map<String, Object> seasonal = null;
        if (last != null) {
            LocalDate latest = LocalDate.parse((String) last.get("date"));
            List<SeriesMath.Value> upToLatest = history.stream().filter(p -> !p.date().isAfter(latest))
                    .map(p -> new SeriesMath.Value(p.date(), p.mean())).toList();
            SeriesMath.SeasonalMean mean = SeriesMath.sameMonthEarlierYears(upToLatest, latest, SEASONAL_MIN_YEARS);
            if (mean != null) {
                List<Map<String, Object>> years = new ArrayList<>();
                mean.byYear().forEach((year, m) -> years.add(Map.of("year", year, "mean", SatelliteHubSeriesData.round(m, 4))));
                double latestValue = (Double) last.get("value");
                seasonal = new LinkedHashMap<>();
                seasonal.put("month", mean.month());
                seasonal.put("years", years);
                seasonal.put("mean", SatelliteHubSeriesData.round(mean.mean(), 4));
                seasonal.put("observations", mean.observations());
                seasonal.put("latestValue", latestValue);
                seasonal.put("anomaly", SatelliteHubSeriesData.round(latestValue - mean.mean(), 4));
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("points", points);
        out.put("count", points.size());
        out.put("events", events);
        out.put("eventCount", events.size());
        out.put("hasDrops", !events.isEmpty());
        out.put("lastDropDate", events.isEmpty() ? null : events.get(events.size() - 1).get("date"));
        out.put("latestStatus", last == null ? "no-data" : Boolean.TRUE.equals(last.get("isDrop")) ? "drop" : "normal");
        out.put("latestDate", last == null ? null : last.get("date"));
        out.put("latestValue", last == null ? null : last.get("value"));
        out.put("latestDelta", last == null ? null : last.get("delta"));
        out.put("seasonalBaseline", seasonal);
        out.put("hasSeasonalBaseline", seasonal != null);
        return out;
    }

    /**
     * First day shown: 'from'; else 'days' back from 'to' (inclusive) or from the latest image. Null
     * shows everything (an empty history).
     */
    static LocalDate periodStart(List<SatelliteHubSeriesData.Point> history, SatelliteHubSeriesData.Window window) {
        if (window.from() != null) return window.from();
        if (window.to() != null) return window.to().plusDays(1).minusDays(window.days());
        if (history.isEmpty()) return null;
        return history.get(history.size() - 1).date().minusDays(window.days()).plusDays(1);
    }

    @SuppressWarnings("unchecked")
    private String chart(Map<String, Object> out, Object indexName, int observations) {
        List<IndexSeriesChart.Point> values = new ArrayList<>();
        List<IndexSeriesChart.Point> baselines = new ArrayList<>();
        List<IndexSeriesChart.Marker> drops = new ArrayList<>();
        for (Map<String, Object> p : (List<Map<String, Object>>) out.get("points")) {
            LocalDate date = LocalDate.parse((String) p.get("date"));
            double value = (Double) p.get("value");
            values.add(new IndexSeriesChart.Point(date, value, value, value));
            if (p.get("baseline") instanceof Double b) baselines.add(new IndexSeriesChart.Point(date, b, b, b));
            if (Boolean.TRUE.equals(p.get("isDrop"))) drops.add(new IndexSeriesChart.Marker(date, value, DROP));
        }
        List<IndexSeriesChart.Line> lines = List.of(
                new IndexSeriesChart.Line(String.valueOf(indexName), VALUE, values, false, false, false),
                new IndexSeriesChart.Line("baseline (median of " + observations + " previous)", BASELINE, baselines, true, false, false));
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(chart.renderLines(lines, drops, CHART_WIDTH, CHART_HEIGHT));
    }
}
