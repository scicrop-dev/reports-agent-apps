package com.infinitestack.reports.blocks.sathub;

import java.time.Duration;
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
 * {@code sathub-index-series}: the time series of a vegetation index (NDVI, EVI, NDMI, ...) that the
 * Satellite Hub app computed from the scenes of the index's collection (Sentinel-2, Landsat 8/9 or
 * Sentinel-1), for the Satellite Hub areas (fields, parcels)
 * inside an area or for one area by id. Per acquisition date, the areas are combined weighting each
 * one by its valid pixels, so a big field counts more than a small one and cloudy pixels not at all.
 *
 * <p>Reads only what Satellite Hub already published in {@code gold}; it does not ask for new
 * processing. Satellite Hub stores geometries in WGS 84 (4326), so the input is transformed.
 */
@Component
public class SatelliteHubIndexSeriesBlock implements ReportBlock {

    static final String SCHEMA = SatelliteHubSeriesData.SCHEMA;
    static final String AREAS = SatelliteHubSeriesData.AREAS;
    static final String INDICES = SatelliteHubSeriesData.INDICES;
    static final String VALUES = SatelliteHubSeriesData.VALUES;

    static final int CHART_WIDTH = 900;
    static final int CHART_HEIGHT = 300;

    private final BlockQueries queries;
    private final IndexSeriesChart chart = new IndexSeriesChart();

    public SatelliteHubIndexSeriesBlock(BlockQueries queries) {
        this.queries = queries;
    }

    @Override
    public BlockDefinition definition() {
        return new BlockDefinition("sathub-index-series", "Vegetation index series (Satellite Hub)",
                "Vegetation index time series (Sentinel-2, Landsat or Sentinel-1) computed by Satellite Hub for the fields in an area, with a chart",
                "1.1.0",
                List.of(
                        BlockPort.optional("geometry", DataType.GEOMETRY,
                                "area whose Satellite Hub fields are combined; connect car-property-geometry.geometry"),
                        BlockPort.optional("areaId", DataType.NUMBER, "one Satellite Hub area by id, instead of a geometry"),
                        BlockPort.optional("index", DataType.STRING, "index slug: ndvi (default), evi, savi, ndre, gndvi, ndmi, ndwi, nbr (Sentinel-2), ndvi_landsat, ndmi_landsat (Landsat), rvi_s1 (Sentinel-1) or any other of Satellite Hub; a disabled index is read while it has data for the areas"),
                        BlockPort.optional("days", DataType.NUMBER,
                                "length of the series, counted back from 'to' or, without it, from the latest image; default 365"),
                        BlockPort.optional("from", DataType.DATE, "first day of the series; when given, 'days' is ignored"),
                        BlockPort.optional("to", DataType.DATE, "last day of the series; default up to the latest image"),
                        BlockPort.optional("minValidPercent", DataType.NUMBER,
                                "drop images with less than this % of valid (cloud-free) area; default 50")),
                List.of(
                        BlockPort.required("index", DataType.OBJECT,
                                "{slug, name, description, collection, collectionName, enabled}: collection is the Satellite Hub id (sentinel-2-l2a, landsat-c2-l2, sentinel-1-rtc)"),
                        BlockPort.required("indexEnabled", DataType.BOOLEAN,
                                "the index is enabled in Satellite Hub; false = a disabled index, its series is the history computed while it was on"),
                        BlockPort.required("areas", DataType.ARRAY,
                                "Satellite Hub areas used, largest share first (up to 50): {id, farmName, blockName, parcelName, areaHa, insideHa}"),
                        BlockPort.required("areaCount", DataType.NUMBER, "number of areas used"),
                        BlockPort.required("series", DataType.ARRAY,
                                "one point per image date, oldest first: {date, mean, min, max, p10, p90, validAreaPercent, areas}"),
                        BlockPort.required("count", DataType.NUMBER, "number of points in the series"),
                        BlockPort.optional("latestDate", DataType.DATE, "date of the latest image"),
                        BlockPort.optional("latestMean", DataType.NUMBER, "index mean on the latest image"),
                        BlockPort.optional("periodMean", DataType.NUMBER, "mean of the series"),
                        BlockPort.optional("periodMin", DataType.NUMBER, "lowest point mean of the series"),
                        BlockPort.optional("periodMax", DataType.NUMBER, "highest point mean of the series"),
                        BlockPort.required("chart", DataType.STRING, "PNG chart as a data:image/png;base64 URL, for <img src>"),
                        BlockPort.required("truncated", DataType.BOOLEAN, "more than 50 areas in the geometry")),
                List.of(
                        DataRequirement.table(SCHEMA, AREAS, "id", "farm_name", "block_name", "parcel_name", "area_ha", "geom"),
                        DataRequirement.table(SCHEMA, INDICES, "id", "slug", "name", "description", "collection", "enabled"),
                        DataRequirement.table(SCHEMA, VALUES, "index_id", "area_id", "acquired_at", "mean", "min_value",
                                "max_value", "p10", "p90", "valid_pixels", "valid_area_pct")),
                Duration.ofSeconds(30));
    }

    @Override
    public BlockDocs docs() {
        return new BlockDocs("Satellite Hub",
                "Shows how the vegetation of an area behaved over time, from the indices that Satellite Hub "
                        + "computes for each registered field (Sentinel-2, Landsat 8/9 or Sentinel-1 radar, depending on the index): a table of dates and values and a chart ready for the PDF.",
                List.of(
                        "Finds the Satellite Hub areas (farms, blocks, parcels) that intersect the geometry, or takes the one given by areaId.",
                        "Reads the index statistics Satellite Hub stored per image and area (mean, min, max, percentiles, valid pixels).",
                        "Drops images with less than minValidPercent of cloud-free area.",
                        "Combines the areas per date with a mean weighted by valid pixels.",
                        "Keeps the images between 'from' and 'to' or, by default, the last 'days' days counted from the latest image, and draws the chart: the mean line over the band between the 10th and 90th percentiles."),
                List.of(
                        "geometry: connect car-property-geometry.geometry to follow the fields inside a CAR property.",
                        "areaId: a report input of type number when the report is about one field.",
                        "index: a fixed value (ndvi, evi, ndmi, ndvi_landsat, rvi_s1...) or a report input, to let the reader choose; {{<node>.index.collectionName}} says which satellite it comes from.",
                        "chart goes straight into the template: <img src=\"{{<node>.chart}}\">."),
                List.of(
                        "Only areas registered in Satellite Hub have data: a CAR property with no field there returns an empty series.",
                        "The index describes vegetation vigour; it does not identify the crop or prove deforestation.",
                        "Sentinel-2 L2A is 10 m and Landsat 30 m, both with clouds masked by Satellite Hub (haze and shadows can remain); Sentinel-1 is radar, sees through clouds and its index (RVI) is on another scale (0 to 4).",
                        "Landsat and Sentinel-1 series exist only where the Satellite Hub admin enabled those indices (they are installed disabled).",
                        "A disabled index keeps its history: the series shows what was computed while it was enabled (indexEnabled = false) and stops there; a disabled index with no data for the areas is an error."),
                "Satellite Hub agent app (Sentinel-2 L2A, Landsat 8/9 C2 L2, Sentinel-1 RTC), tables gold.satellite_hub_areas, _analytics_indices and _analytics_values",
                "<img src=\"{{<node>.chart}}\"> NDVI atual: {{<node>.latestMean}} ({{<node>.latestDate}})");
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> inputs, ExecutionMetadata metadata) {
        SatelliteHubSeriesData.Window window = SatelliteHubSeriesData.Window.of(inputs);
        String slug = inputs.get("index") instanceof String s && !s.isBlank() ? s.trim().toLowerCase(Locale.ROOT) : "ndvi";

        return queries.read(Duration.ofSeconds(25), jdbc -> {
            List<Map<String, Object>> index = SatelliteHubSeriesData.indices(jdbc, List.of(slug), false);
            if (index.isEmpty()) throw new IllegalArgumentException("Satellite Hub has no index '" + slug + "'");
            Map<String, Object> indexOut = SatelliteHubSeriesData.indexInfo(index.get(0));
            boolean enabled = (Boolean) indexOut.get("enabled");

            SatelliteHubSeriesData.Areas areas = SatelliteHubSeriesData.areas(jdbc, window);
            List<SatelliteHubSeriesData.Point> data = SatelliteHubSeriesData.series(jdbc, index.get(0).get("id"), areas.ids(), window);
            // Before 1.1.0 a disabled index was refused; now its history is shown, but a disabled index
            // with nothing to show is still an error, so a typo or a never-computed index stays visible.
            if (!enabled && data.isEmpty()) {
                throw new IllegalArgumentException("Satellite Hub index '" + slug + "' is disabled and has no data for these areas in the period");
            }

            List<Map<String, Object>> series = new ArrayList<>();
            List<IndexSeriesChart.Point> points = new ArrayList<>();
            for (SatelliteHubSeriesData.Point p : data) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("date", p.date().toString());
                item.put("mean", p.mean());
                item.put("min", p.min());
                item.put("max", p.max());
                item.put("p10", p.p10());
                item.put("p90", p.p90());
                item.put("validAreaPercent", p.validAreaPercent());
                item.put("areas", p.areas());
                series.add(item);
                points.add(new IndexSeriesChart.Point(p.date(), p.mean(), p.p10() == null ? p.mean() : p.p10(),
                        p.p90() == null ? p.mean() : p.p90()));
            }

            Map<String, Object> last = series.isEmpty() ? null : series.get(series.size() - 1);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("index", indexOut);
            out.put("indexEnabled", enabled);
            out.put("areas", areas.items());
            out.put("areaCount", areas.items().size());
            out.put("series", series);
            out.put("count", series.size());
            out.put("latestDate", last == null ? null : last.get("date"));
            out.put("latestMean", last == null ? null : last.get("mean"));
            out.put("periodMean", series.isEmpty() ? null
                    : SatelliteHubSeriesData.round(data.stream().mapToDouble(SatelliteHubSeriesData.Point::mean).average().orElse(0), 4));
            out.put("periodMin", data.stream().map(SatelliteHubSeriesData.Point::mean).min(Double::compare).orElse(null));
            out.put("periodMax", data.stream().map(SatelliteHubSeriesData.Point::mean).max(Double::compare).orElse(null));
            out.put("chart", "data:image/png;base64," + Base64.getEncoder().encodeToString(chart.render(points, CHART_WIDTH, CHART_HEIGHT)));
            out.put("truncated", areas.truncated());
            return out;
        });
    }
}
