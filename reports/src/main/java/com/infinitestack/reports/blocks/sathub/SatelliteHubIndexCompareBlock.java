package com.infinitestack.reports.blocks.sathub;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

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
 * {@code sathub-index-compare}: several Satellite Hub index series of the same area side by side,
 * typically one per satellite (Sentinel-2 NDVI, Landsat NDVI, Sentinel-1 RVI), with how much the
 * series on the same scale agree (bias and correlation on matched dates) and one chart with all of
 * them. Indices on another scale (the radar RVI, 0 to 4) go to the chart's right axis and are not
 * paired with the optical ones (-1 to 1): a difference or correlation between them would mean nothing.
 */
@Component
public class SatelliteHubIndexCompareBlock implements ReportBlock {

    static final String DEFAULT_INDICES = "ndvi,ndvi_landsat,rvi_s1";
    static final int MAX_INDICES = 6;
    static final int DEFAULT_MAX_DAY_GAP = 3;
    static final int CHART_WIDTH = 900;
    static final int CHART_HEIGHT = 320;

    private final BlockQueries queries;
    private final IndexSeriesChart chart = new IndexSeriesChart();

    public SatelliteHubIndexCompareBlock(BlockQueries queries) {
        this.queries = queries;
    }

    @Override
    public BlockDefinition definition() {
        return new BlockDefinition("sathub-index-compare", "Index comparison across satellites (Satellite Hub)",
                "Several Satellite Hub index series of an area side by side (e.g. Sentinel-2 NDVI, Landsat NDVI, Sentinel-1 RVI), "
                        + "with bias and correlation between the ones on the same scale and one chart",
                "1.0.0",
                List.of(
                        BlockPort.optional("geometry", DataType.GEOMETRY,
                                "area whose Satellite Hub fields are combined; connect car-property-geometry.geometry"),
                        BlockPort.optional("areaId", DataType.NUMBER, "one Satellite Hub area by id, instead of a geometry"),
                        BlockPort.optional("indices", DataType.STRING,
                                "comma-separated index slugs, up to 6, e.g. ndvi,ndvi_landsat,rvi_s1; default " + DEFAULT_INDICES
                                        + " (the ones that exist and have data)"),
                        BlockPort.optional("days", DataType.NUMBER,
                                "length of the period, counted back from 'to' or, without it, from the latest image of any of the indices; default 365"),
                        BlockPort.optional("from", DataType.DATE, "first day of the period; when given, 'days' is ignored"),
                        BlockPort.optional("to", DataType.DATE, "last day of the period; default up to the latest image"),
                        BlockPort.optional("minValidPercent", DataType.NUMBER,
                                "drop images with less than this % of valid (cloud-free) area; default 50"),
                        BlockPort.optional("maxDayGap", DataType.NUMBER,
                                "two images of different indices count as the same date when at most this many days apart; default 3")),
                List.of(
                        BlockPort.required("series", DataType.ARRAY,
                                "one per index with data, in the requested order: {index {slug, name, description, collection, collectionName, enabled}, "
                                        + "points [{date, mean, p10, p90}], count, latest {date, mean}, periodMean, rightAxis}"),
                        BlockPort.required("seriesCount", DataType.NUMBER, "number of indices with data in the period"),
                        BlockPort.required("hasSeries", DataType.BOOLEAN, "at least one index has data in the period"),
                        BlockPort.required("withoutData", DataType.ARRAY,
                                "slugs of the requested indices that exist but have no image in the period"),
                        BlockPort.required("pairs", DataType.ARRAY,
                                "pairs of indices on the same scale: {a, b, aName, bName, matchedDates, meanDiff, correlation}; "
                                        + "meanDiff is the mean of a - b on the matched dates (bias), correlation is Pearson's r (null under 3 matches)"),
                        BlockPort.required("areas", DataType.ARRAY,
                                "Satellite Hub areas used, largest share first (up to 50): {id, farmName, blockName, parcelName, areaHa, insideHa}"),
                        BlockPort.required("areaCount", DataType.NUMBER, "number of areas used"),
                        BlockPort.required("chart", DataType.STRING,
                                "PNG chart (one line per index; other scales on the right axis) as a data:image/png;base64 URL"),
                        BlockPort.required("truncated", DataType.BOOLEAN, "more than 50 areas in the geometry")),
                List.of(
                        DataRequirement.table(SatelliteHubSeriesData.SCHEMA, SatelliteHubSeriesData.AREAS,
                                "id", "farm_name", "block_name", "parcel_name", "area_ha", "geom"),
                        DataRequirement.table(SatelliteHubSeriesData.SCHEMA, SatelliteHubSeriesData.INDICES,
                                "id", "slug", "name", "description", "collection", "enabled", "clamp_min", "clamp_max"),
                        DataRequirement.table(SatelliteHubSeriesData.SCHEMA, SatelliteHubSeriesData.VALUES, "index_id", "area_id",
                                "acquired_at", "mean", "min_value", "max_value", "p10", "p90", "valid_pixels", "valid_area_pct")),
                Duration.ofSeconds(40));
    }

    @Override
    public BlockDocs docs() {
        return new BlockDocs("Satellite Hub",
                "Puts the index series of the same area from different satellites side by side (Sentinel-2, Landsat 8/9, "
                        + "Sentinel-1 radar), to show whether they tell the same story and fill each other's gaps: one chart and, "
                        + "for the indices on the same scale, how far apart they are (bias) and how well they move together (correlation).",
                List.of(
                        "Finds the Satellite Hub areas that intersect the geometry, or takes the one given by areaId, as sathub-index-series does.",
                        "Reads each index's series (mean weighted by valid pixels per date, images under minValidPercent of clear area dropped) in the same period: "
                                + "between 'from' and 'to' or, by default, the last 'days' days counted from the latest image of any of the indices.",
                        "Without 'indices' it tries ndvi, ndvi_landsat and rvi_s1 and keeps the ones that exist and have data; a disabled index still shows the history it has.",
                        "Groups the indices by value range (clamp_min/clamp_max in Satellite Hub): the most common range is the left axis, the others (the radar RVI, 0 to 4) the right axis.",
                        "For each pair on the same range, matches the dates (nearest image of the other index at most maxDayGap days away, each image used once) and computes the mean difference a - b and Pearson's correlation."),
                List.of(
                        "geometry: connect car-property-geometry.geometry; or areaId as a report input for one field.",
                        "indices: a fixed list such as ndvi,ndvi_landsat,rvi_s1 or ndvi,evi,ndre (several Sentinel-2 indices also work).",
                        "chart goes straight into the template: <img src=\"{{<node>.chart}}\">; the table of pairs with {{#<node>.pairs}}...{{/<node>.pairs}}."),
                List.of(
                        "Only areas registered in Satellite Hub have data, and Landsat and Sentinel-1 only where those indices were enabled (they are installed disabled).",
                        "Satellites rarely pass on the same day: a pair compares images up to maxDayGap days apart, so part of a difference can be real change in those days.",
                        "Sentinel-2 is 10 m and Landsat 30 m: on small fields Landsat mixes the border, which shows up as bias.",
                        "RVI (radar) is not paired with optical indices: different physics and scale. Read it on the chart, as a trend.",
                        "Indices without clamp_min/clamp_max in Satellite Hub have no known range: they are drawn but not paired.",
                        "With three or more different ranges, all but the most common share the right axis."),
                "Satellite Hub agent app (Sentinel-2 L2A, Landsat 8/9 C2 L2, Sentinel-1 RTC), tables gold.satellite_hub_areas, _analytics_indices and _analytics_values",
                "<img src=\"{{<node>.chart}}\">{{#<node>.pairs}}<p>{{aName}} × {{bName}}: diferença média {{meanDiff}}, "
                        + "correlação {{correlation}} ({{matchedDates}} datas)</p>{{/<node>.pairs}}");
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> inputs, ExecutionMetadata metadata) {
        SatelliteHubSeriesData.Window window = SatelliteHubSeriesData.Window.of(inputs);
        boolean explicit = inputs.get("indices") instanceof String s && !s.isBlank();
        List<String> slugs = slugs(explicit ? (String) inputs.get("indices") : DEFAULT_INDICES);
        if (slugs.size() > MAX_INDICES) throw new IllegalArgumentException("compare at most " + MAX_INDICES + " indices, got " + slugs.size());
        int maxDayGap = inputs.get("maxDayGap") instanceof Number n ? Math.max(0, n.intValue()) : DEFAULT_MAX_DAY_GAP;

        return queries.read(Duration.ofSeconds(35), jdbc -> {
            Map<String, Map<String, Object>> bySlug = new HashMap<>();
            for (Map<String, Object> row : SatelliteHubSeriesData.indices(jdbc, slugs, true)) bySlug.put(String.valueOf(row.get("slug")), row);
            List<String> unknown = slugs.stream().filter(s -> !bySlug.containsKey(s)).toList();
            if (explicit && !unknown.isEmpty()) throw new IllegalArgumentException("Satellite Hub has no index " + String.join(", ", unknown));

            SatelliteHubSeriesData.Areas areas = SatelliteHubSeriesData.areas(jdbc, window);
            // One period for all: without from/to, 'days' counts back from the latest image of any index,
            // not from each index's own latest image.
            SatelliteHubSeriesData.Window period = window;
            List<Object> indexIds = slugs.stream().filter(bySlug::containsKey).map(s -> bySlug.get(s).get("id")).toList();
            if (window.from() == null && window.to() == null) {
                LocalDate latest = SatelliteHubSeriesData.latestDate(jdbc, indexIds, areas.ids(), window.minValidPercent());
                if (latest != null) period = window.withTo(latest);
            }
            List<Found> found = new ArrayList<>();
            List<String> withoutData = new ArrayList<>();
            for (String slug : slugs) {
                Map<String, Object> row = bySlug.get(slug);
                if (row == null) continue;
                List<SatelliteHubSeriesData.Point> points = SatelliteHubSeriesData.series(jdbc, row.get("id"), areas.ids(), period);
                if (points.isEmpty()) withoutData.add(slug);
                else found.add(new Found(row, points, scaleKey(row)));
            }
            String mainScale = mainScale(found);

            List<Map<String, Object>> series = new ArrayList<>();
            List<IndexSeriesChart.Line> lines = new ArrayList<>();
            for (int i = 0; i < found.size(); i++) {
                Found f = found.get(i);
                boolean rightAxis = !Objects.equals(f.scale(), mainScale);
                Map<String, Object> index = SatelliteHubSeriesData.indexInfo(f.row());
                List<Map<String, Object>> points = new ArrayList<>();
                List<IndexSeriesChart.Point> chartPoints = new ArrayList<>();
                for (SatelliteHubSeriesData.Point p : f.points()) {
                    Map<String, Object> point = new LinkedHashMap<>();
                    point.put("date", p.date().toString());
                    point.put("mean", p.mean());
                    point.put("p10", p.p10());
                    point.put("p90", p.p90());
                    points.add(point);
                    chartPoints.add(new IndexSeriesChart.Point(p.date(), p.mean(), p.mean(), p.mean()));
                }
                SatelliteHubSeriesData.Point last = f.points().get(f.points().size() - 1);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("index", index);
                item.put("points", points);
                item.put("count", points.size());
                item.put("latest", Map.of("date", last.date().toString(), "mean", last.mean()));
                item.put("periodMean", SatelliteHubSeriesData.round(
                        f.points().stream().mapToDouble(SatelliteHubSeriesData.Point::mean).average().orElse(0), 4));
                item.put("rightAxis", rightAxis);
                series.add(item);
                lines.add(new IndexSeriesChart.Line(index.get("name") + " (" + index.get("collectionName") + ")",
                        IndexSeriesChart.PALETTE.get(i % IndexSeriesChart.PALETTE.size()), chartPoints, false, false, rightAxis));
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("series", series);
            out.put("seriesCount", series.size());
            out.put("hasSeries", !series.isEmpty());
            out.put("withoutData", withoutData);
            out.put("pairs", pairs(found, maxDayGap));
            out.put("areas", areas.items());
            out.put("areaCount", areas.items().size());
            out.put("chart", "data:image/png;base64," + Base64.getEncoder().encodeToString(
                    chart.renderLines(lines, List.of(), CHART_WIDTH, CHART_HEIGHT)));
            out.put("truncated", areas.truncated());
            return out;
        });
    }

    /** An index with data: its definition row, its series and its value range. */
    record Found(Map<String, Object> row, List<SatelliteHubSeriesData.Point> points, String scale) {}

    /** "min..max" of the index's clamp range; null when Satellite Hub does not give both ends. */
    static String scaleKey(Map<String, Object> row) {
        if (!(row.get("clamp_min") instanceof Number min) || !(row.get("clamp_max") instanceof Number max)) return null;
        return min.doubleValue() + ".." + max.doubleValue();
    }

    /** The range most indices share (the first one on a tie): the left axis of the chart. */
    static String mainScale(List<Found> found) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Found f : found) counts.merge(String.valueOf(f.scale()), 1, Integer::sum);
        String best = null;
        int bestCount = 0;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getValue() > bestCount) {
                best = e.getKey();
                bestCount = e.getValue();
            }
        }
        return "null".equals(best) ? null : best;
    }

    private static List<Map<String, Object>> pairs(List<Found> found, int maxDayGap) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < found.size(); i++) {
            for (int j = i + 1; j < found.size(); j++) {
                Found a = found.get(i);
                Found b = found.get(j);
                if (a.scale() == null || !a.scale().equals(b.scale())) continue;
                List<SeriesMath.Match> matches = SeriesMath.matchDates(values(a), values(b), maxDayGap);
                Map<String, Object> pair = new LinkedHashMap<>();
                pair.put("a", a.row().get("slug"));
                pair.put("b", b.row().get("slug"));
                pair.put("aName", a.row().get("name"));
                pair.put("bName", b.row().get("name"));
                pair.put("matchedDates", matches.size());
                pair.put("meanDiff", SatelliteHubSeriesData.round(SeriesMath.meanDifference(matches), 4));
                pair.put("correlation", SatelliteHubSeriesData.round(SeriesMath.correlation(matches), 3));
                out.add(pair);
            }
        }
        return out;
    }

    private static List<SeriesMath.Value> values(Found f) {
        return f.points().stream().map(p -> new SeriesMath.Value(p.date(), p.mean())).toList();
    }

    static List<String> slugs(String text) {
        return Arrays.stream(text.split(",")).map(s -> s.trim().toLowerCase(Locale.ROOT)).filter(s -> !s.isEmpty())
                .distinct().collect(Collectors.toList());
    }
}
