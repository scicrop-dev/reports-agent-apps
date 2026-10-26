package com.infinitestack.reports.blocks.sathub.imagery;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * {@link SceneSearch} on the Earth Search STAC API (Element 84, the catalog Satellite Hub uses):
 * POST {stacUrl}/search with collections [sentinel-2-l2a], bbox, datetime range, eo:cloud_cover
 * filter and sortby datetime desc. Reads proj:epsg (or proj:code), s2:mgrs_tile / grid:code,
 * platform and, per asset, href and raster:bands[0].scale/offset/spatial_resolution.
 *
 * <p>Only the GeoTIFF (COG) assets are kept: Earth Search also lists each band as {@code <name>-jp2}
 * (JPEG 2000 on a requester-pays bucket), which we cannot read by range. One retry on 5xx or I/O error.
 */
public class EarthSearchClient implements SceneSearch {

    public static final String DEFAULT_STAC_URL = "https://earth-search.aws.element84.com/v1";
    static final String COLLECTION = "sentinel-2-l2a";
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final String stacUrl;
    private final HttpClient http;

    public EarthSearchClient(String stacUrl) {
        this(stacUrl, HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    /** With a given client (tests). */
    public EarthSearchClient(String stacUrl, HttpClient http) {
        String url = stacUrl == null || stacUrl.isBlank() ? DEFAULT_STAC_URL : stacUrl.trim();
        this.stacUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        this.http = http;
    }

    @Override
    public List<SceneItem> search(double[] bboxLonLat, LocalDate since, double maxCloud, int limit) throws IOException {
        if (bboxLonLat == null || bboxLonLat.length != 4) {
            throw new IllegalArgumentException("bbox must be {minLon, minLat, maxLon, maxLat}");
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(stacUrl + "/search"))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Accept", "application/geo+json, application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody(bboxLonLat, since, maxCloud, limit)))
                .build();
        return parse(send(request));
    }

    /** The search body; datetime is open-ended from {@code since}. */
    static String requestBody(double[] bbox, LocalDate since, double maxCloud, int limit) {
        ObjectNode body = JSON.createObjectNode();
        body.putArray("collections").add(COLLECTION);
        ArrayNode b = body.putArray("bbox");
        for (double v : bbox) b.add(v);
        if (since != null) body.put("datetime", since + "T00:00:00Z/..");
        body.put("limit", Math.max(1, limit));
        body.putArray("sortby").addObject().put("field", "properties.datetime").put("direction", "desc");
        body.putObject("query").putObject("eo:cloud_cover").put("lt", maxCloud);
        return body.toString();
    }

    /** Sends with one retry on 5xx or I/O error; any other non-2xx is an error right away. */
    private String send(HttpRequest request) throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            HttpResponse<String> response;
            try {
                response = http.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("STAC search at " + request.uri() + " interrupted", e);
            } catch (IOException e) {
                last = new IOException("STAC search at " + request.uri() + " failed: " + describe(e), e);
                continue;
            }
            int status = response.statusCode();
            if (status >= 200 && status < 300) return response.body();
            last = new IOException("STAC search at " + request.uri() + " answered HTTP " + status
                    + ": " + abbreviate(response.body()));
            if (status < 500) throw last;
        }
        throw last;
    }

    private static String describe(IOException e) {
        String message = e.getMessage();
        return e.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : " (" + message + ")");
    }

    private static String abbreviate(String body) {
        if (body == null) return "";
        String s = body.strip();
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }

    /** Maps a FeatureCollection answer to scenes, in the order the API returned them. */
    static List<SceneItem> parse(String json) throws IOException {
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IOException("STAC search answer is not valid JSON: " + e.getOriginalMessage(), e);
        }
        JsonNode features = root == null ? null : root.get("features");
        if (features == null || !features.isArray()) {
            throw new IOException("STAC search answer has no 'features' array");
        }
        List<SceneItem> items = new ArrayList<>(features.size());
        for (JsonNode feature : features) items.add(toItem(feature));
        return items;
    }

    private static SceneItem toItem(JsonNode feature) throws IOException {
        String id = feature.path("id").asText(null);
        JsonNode props = feature.path("properties");
        String datetime = props.path("datetime").asText(null);
        if (id == null || datetime == null) {
            throw new IOException("STAC item without id or properties.datetime: " + abbreviate(feature.toString()));
        }
        Map<String, Object> footprint = feature.hasNonNull("geometry")
                ? JSON.convertValue(feature.get("geometry"), MAP) : null;
        return new SceneItem(id, Instant.parse(datetime), props.path("eo:cloud_cover").asDouble(Double.NaN),
                epsg(props), props.path("platform").asText(null), mgrsTile(props), footprint,
                assets(feature.path("assets")), selfHref(feature));
    }

    /** proj:epsg, else the number of proj:code ("EPSG:32719"); 0 when absent. */
    static int epsg(JsonNode props) {
        if (props.path("proj:epsg").canConvertToInt()) return props.path("proj:epsg").asInt();
        String code = props.path("proj:code").asText("");
        if (code.regionMatches(true, 0, "EPSG:", 0, 5)) {
            try {
                return Integer.parseInt(code.substring(5).trim());
            } catch (NumberFormatException ignored) {
                // falls through to 0
            }
        }
        return 0;
    }

    /** s2:mgrs_tile, else grid:code ("MGRS-19LCL"), else mgrs:utm_zone + latitude_band + grid_square. */
    static String mgrsTile(JsonNode props) {
        String tile = props.path("s2:mgrs_tile").asText("");
        if (!tile.isBlank()) return tile;
        String grid = props.path("grid:code").asText("");
        if (grid.startsWith("MGRS-")) return grid.substring(5);
        if (props.has("mgrs:utm_zone")) {
            return String.format("%02d", props.path("mgrs:utm_zone").asInt())
                    + props.path("mgrs:latitude_band").asText("") + props.path("mgrs:grid_square").asText("");
        }
        return null;
    }

    private static Map<String, SceneAsset> assets(JsonNode assets) {
        Map<String, SceneAsset> out = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = assets.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> e = fields.next();
            String href = e.getValue().path("href").asText("");
            if (e.getKey().endsWith("-jp2") || !href.toLowerCase().endsWith(".tif")) continue;
            JsonNode band = e.getValue().path("raster:bands").path(0);
            double gsd = e.getValue().path("gsd").asDouble(10);
            out.put(e.getKey(), new SceneAsset(href, band.path("scale").asDouble(1), band.path("offset").asDouble(0),
                    band.path("spatial_resolution").asDouble(gsd)));
        }
        return out;
    }

    private static String selfHref(JsonNode feature) {
        for (JsonNode link : feature.path("links")) {
            if ("self".equals(link.path("rel").asText())) return link.path("href").asText(null);
        }
        return null;
    }
}
