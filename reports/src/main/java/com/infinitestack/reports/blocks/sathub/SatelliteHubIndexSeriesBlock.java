package com.infinitestack.reports.blocks.sathub;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    static final String SCHEMA = "gold";
    static final String AREAS = "satellite_hub_areas";
    static final String INDICES = "satellite_hub_analytics_indices";
    static final String VALUES = "satellite_hub_analytics_values";

    /** The collection of an index saved before Satellite Hub had several (its own default). */
    static final String LEGACY_COLLECTION = "sentinel-2-l2a";

    /** Satellite Hub collection id to the name shown in the report. */
    static final Map<String, String> COLLECTION_NAMES = Map.of(
            "sentinel-2-l2a", "Sentinel-2 L2A",
            "landsat-c2-l2", "Landsat 8/9 Collection 2 Level-2",
            "sentinel-1-rtc", "Sentinel-1 RTC (radar)");

    static final int MAX_AREAS = 50;
    static final int DEFAULT_DAYS = 365;
    static final double DEFAULT_MIN_VALID_PERCENT = 50;
    static final int CHART_WIDTH = 900;
    static final int CHART_HEIGHT = 300;

    private static final ObjectMapper JSON = new ObjectMapper();

    static final String INDEX_SQL = "SELECT id, slug, name, description, COALESCE(collection, '" + LEGACY_COLLECTION + "') AS collection"
            + " FROM " + SCHEMA + "." + INDICES + " WHERE slug = ? AND enabled";

    static final String AREAS_BY_GEOMETRY_SQL = "WITH input AS ("
            + " SELECT ST_Transform(ST_CollectionExtract(ST_MakeValid(ST_SetSRID(ST_GeomFromGeoJSON(?), 4674)), 3), 4326) AS geom)"
            + " SELECT a.id, a.farm_name, a.block_name, a.parcel_name, a.area_ha,"
            + " ST_Area(ST_Intersection(ST_MakeValid(a.geom), input.geom)::geography) / 1e4 AS inside_ha"
            + " FROM input JOIN " + SCHEMA + "." + AREAS + " a ON a.geom && input.geom AND ST_Intersects(a.geom, input.geom)"
            + " ORDER BY inside_ha DESC, a.id LIMIT " + (MAX_AREAS + 1);

    static final String AREA_BY_ID_SQL = "SELECT a.id, a.farm_name, a.block_name, a.parcel_name, a.area_ha,"
            + " a.area_ha AS inside_ha FROM " + SCHEMA + "." + AREAS + " a WHERE a.id = ?";

    /** {@code %s} is the list of area id placeholders. */
    static final String SERIES_SQL = "WITH v AS ("
            + " SELECT v.* FROM " + SCHEMA + "." + VALUES + " v WHERE v.index_id = ? AND v.area_id IN (%s)"
            + " AND v.valid_pixels > 0 AND v.valid_area_pct >= ?),"
            + " last AS (SELECT max(acquired_at) AS at FROM v)"
            + " SELECT v.acquired_at::date AS day,"
            + " sum(v.mean * v.valid_pixels) / sum(v.valid_pixels) AS mean,"
            + " min(v.min_value) AS min_value, max(v.max_value) AS max_value,"
            + " sum(v.p10 * v.valid_pixels) / sum(v.valid_pixels) AS p10,"
            + " sum(v.p90 * v.valid_pixels) / sum(v.valid_pixels) AS p90,"
            + " avg(v.valid_area_pct) AS valid_pct, count(DISTINCT v.area_id) AS areas"
            + " FROM v, last WHERE v.acquired_at > last.at - make_interval(days => ?)"
            + " GROUP BY 1 ORDER BY 1";

    private final BlockQueries queries;
    private final IndexSeriesChart chart = new IndexSeriesChart();

    public SatelliteHubIndexSeriesBlock(BlockQueries queries) {
        this.queries = queries;
    }

    @Override
    public BlockDefinition definition() {
        return new BlockDefinition("sathub-index-series", "Vegetation index series (Satellite Hub)",
                "Vegetation index time series (Sentinel-2, Landsat or Sentinel-1) computed by Satellite Hub for the fields in an area, with a chart",
                "1.0.0",
                List.of(
                        BlockPort.optional("geometry", DataType.GEOMETRY,
                                "area whose Satellite Hub fields are combined; connect car-property-geometry.geometry"),
                        BlockPort.optional("areaId", DataType.NUMBER, "one Satellite Hub area by id, instead of a geometry"),
                        BlockPort.optional("index", DataType.STRING, "index slug: ndvi (default), evi, savi, ndre, gndvi, ndmi, ndwi, nbr (Sentinel-2), ndvi_landsat, ndmi_landsat (Landsat), rvi_s1 (Sentinel-1) or any other enabled in Satellite Hub"),
                        BlockPort.optional("days", DataType.NUMBER, "length of the series, counted back from the latest image; default 365"),
                        BlockPort.optional("minValidPercent", DataType.NUMBER,
                                "drop images with less than this % of valid (cloud-free) area; default 50")),
                List.of(
                        BlockPort.required("index", DataType.OBJECT,
                                "{slug, name, description, collection, collectionName}: collection is the Satellite Hub id (sentinel-2-l2a, landsat-c2-l2, sentinel-1-rtc)"),
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
                        "Keeps the last 'days' days counted from the latest image and draws the chart: the mean line over the band between the 10th and 90th percentiles."),
                List.of(
                        "geometry: connect car-property-geometry.geometry to follow the fields inside a CAR property.",
                        "areaId: a report input of type number when the report is about one field.",
                        "index: a fixed value (ndvi, evi, ndmi, ndvi_landsat, rvi_s1...) or a report input, to let the reader choose; {{<node>.index.collectionName}} says which satellite it comes from.",
                        "chart goes straight into the template: <img src=\"{{<node>.chart}}\">."),
                List.of(
                        "Only areas registered in Satellite Hub have data: a CAR property with no field there returns an empty series.",
                        "The index describes vegetation vigour; it does not identify the crop or prove deforestation.",
                        "Sentinel-2 L2A is 10 m and Landsat 30 m, both with clouds masked by Satellite Hub (haze and shadows can remain); Sentinel-1 is radar, sees through clouds and its index (RVI) is on another scale (0 to 4).",
                        "Landsat and Sentinel-1 series exist only where the Satellite Hub admin enabled those indices."),
                "Satellite Hub agent app (Sentinel-2 L2A, Landsat 8/9 C2 L2, Sentinel-1 RTC), tables gold.satellite_hub_areas, _analytics_indices and _analytics_values",
                "<img src=\"{{<node>.chart}}\"> NDVI atual: {{<node>.latestMean}} ({{<node>.latestDate}})");
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> inputs, ExecutionMetadata metadata) {
        Object geometry = inputs.get("geometry");
        Long areaId = inputs.get("areaId") instanceof Number n ? n.longValue() : null;
        if (geometry == null && areaId == null) throw new IllegalArgumentException("give a geometry or an areaId");
        String slug = inputs.get("index") instanceof String s && !s.isBlank() ? s.trim().toLowerCase() : "ndvi";
        int days = inputs.get("days") instanceof Number n ? Math.max(1, n.intValue()) : DEFAULT_DAYS;
        double minValid = inputs.get("minValidPercent") instanceof Number n ? n.doubleValue() : DEFAULT_MIN_VALID_PERCENT;

        return queries.read(Duration.ofSeconds(25), jdbc -> {
            List<Map<String, Object>> index = jdbc.queryForList(INDEX_SQL, slug);
            if (index.isEmpty()) throw new IllegalArgumentException("Satellite Hub has no enabled index '" + slug + "'");

            List<Map<String, Object>> areaRows = geometry != null
                    ? jdbc.queryForList(AREAS_BY_GEOMETRY_SQL, geoJson(geometry))
                    : jdbc.queryForList(AREA_BY_ID_SQL, areaId);
            if (geometry == null && areaRows.isEmpty()) throw new IllegalArgumentException("Satellite Hub area " + areaId + " not found");
            boolean truncated = areaRows.size() > MAX_AREAS;
            List<Map<String, Object>> areas = new ArrayList<>();
            for (Map<String, Object> row : areaRows.subList(0, Math.min(MAX_AREAS, areaRows.size()))) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", ((Number) row.get("id")).longValue());
                item.put("farmName", row.get("farm_name"));
                item.put("blockName", row.get("block_name"));
                item.put("parcelName", row.get("parcel_name"));
                item.put("areaHa", round(row.get("area_ha"), 4));
                item.put("insideHa", round(row.get("inside_ha"), 4));
                areas.add(item);
            }

            List<Map<String, Object>> series = new ArrayList<>();
            List<IndexSeriesChart.Point> points = new ArrayList<>();
            if (!areas.isEmpty()) {
                List<Object> args = new ArrayList<>();
                args.add(index.get(0).get("id"));
                areas.forEach(a -> args.add(a.get("id")));
                args.add(minValid);
                args.add(days);
                String placeholders = String.join(", ", Collections.nCopies(areas.size(), "?"));
                for (Map<String, Object> row : jdbc.queryForList(String.format(SERIES_SQL, placeholders), args.toArray())) {
                    LocalDate day = LocalDate.parse(row.get("day").toString());
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("date", day.toString());
                    item.put("mean", round(row.get("mean"), 4));
                    item.put("min", round(row.get("min_value"), 4));
                    item.put("max", round(row.get("max_value"), 4));
                    item.put("p10", round(row.get("p10"), 4));
                    item.put("p90", round(row.get("p90"), 4));
                    item.put("validAreaPercent", round(row.get("valid_pct"), 1));
                    item.put("areas", ((Number) row.get("areas")).intValue());
                    series.add(item);
                    points.add(new IndexSeriesChart.Point(day, (Double) item.get("mean"), (Double) item.get("p10"), (Double) item.get("p90")));
                }
            }

            Map<String, Object> indexOut = new LinkedHashMap<>();
            indexOut.put("slug", index.get(0).get("slug"));
            indexOut.put("name", index.get(0).get("name"));
            indexOut.put("description", index.get(0).get("description"));
            String collection = String.valueOf(index.get(0).get("collection"));
            indexOut.put("collection", collection);
            indexOut.put("collectionName", COLLECTION_NAMES.getOrDefault(collection, collection));

            Map<String, Object> last = series.isEmpty() ? null : series.get(series.size() - 1);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("index", indexOut);
            out.put("areas", areas);
            out.put("areaCount", areas.size());
            out.put("series", series);
            out.put("count", series.size());
            out.put("latestDate", last == null ? null : last.get("date"));
            out.put("latestMean", last == null ? null : last.get("mean"));
            out.put("periodMean", series.isEmpty() ? null
                    : round(series.stream().mapToDouble(p -> (Double) p.get("mean")).average().orElse(0), 4));
            out.put("periodMin", series.stream().map(p -> (Double) p.get("mean")).min(Double::compare).orElse(null));
            out.put("periodMax", series.stream().map(p -> (Double) p.get("mean")).max(Double::compare).orElse(null));
            out.put("chart", "data:image/png;base64," + Base64.getEncoder().encodeToString(chart.render(points, CHART_WIDTH, CHART_HEIGHT)));
            out.put("truncated", truncated);
            return out;
        });
    }

    private static String geoJson(Object geometry) {
        try {
            return JSON.writeValueAsString(geometry);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("geometry is not valid GeoJSON", e);
        }
    }

    private static Double round(Object value, int scale) {
        if (!(value instanceof Number n)) return null;
        double factor = Math.pow(10, scale);
        return Math.round(n.doubleValue() * factor) / factor;
    }
}
