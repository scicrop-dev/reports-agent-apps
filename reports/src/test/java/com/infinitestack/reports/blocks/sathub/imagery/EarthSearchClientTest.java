package com.infinitestack.reports.blocks.sathub.imagery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

/**
 * Parses a real Earth Search answer (trimmed fixture: 3 items over Acre, 2026-10, the 2nd and 3rd
 * without the -jp2 assets) served by a local HTTP server. The live test runs only with
 * {@code SENTINEL_NETWORK_TEST=1}.
 */
class EarthSearchClientTest {

    static final String FIXTURE = "/sathub-imagery/earth-search-response.json";
    static final double[] BBOX = {-70.30, -8.30, -70.10, -8.10};

    HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    /** Serves {@code statuses} in turn (the last one repeats), with the fixture on 200. */
    String serve(AtomicReference<String> body, AtomicInteger calls, int... statuses) throws IOException {
        byte[] fixture;
        try (InputStream in = getClass().getResourceAsStream(FIXTURE)) {
            fixture = in.readAllBytes();
        }
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/search", exchange -> {
            int call = calls.getAndIncrement();
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            int status = statuses[Math.min(call, statuses.length - 1)];
            byte[] answer = status == 200 ? fixture : "{\"code\":\"oops\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/geo+json");
            exchange.sendResponseHeaders(status, answer.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(answer);
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/";
    }

    @Test
    void parsesTheFixtureAndSendsTheSearchBody() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        String url = serve(body, new AtomicInteger(), 200);

        List<SceneItem> items = new EarthSearchClient(url).search(BBOX, LocalDate.of(2026, 6, 1), 40, 3);

        JsonNode sent = new ObjectMapper().readTree(body.get());
        assertEquals("sentinel-2-l2a", sent.path("collections").path(0).asText());
        assertEquals(-70.30, sent.path("bbox").path(0).asDouble(), 0);
        assertEquals("2026-06-01T00:00:00Z/..", sent.path("datetime").asText());
        assertEquals(3, sent.path("limit").asInt());
        assertEquals("properties.datetime", sent.path("sortby").path(0).path("field").asText());
        assertEquals("desc", sent.path("sortby").path(0).path("direction").asText());
        assertEquals(40, sent.path("query").path("eo:cloud_cover").path("lt").asDouble(), 0);

        assertEquals(3, items.size());
        SceneItem first = items.get(0);
        assertEquals("S2C_19LCL_20261025_0_L2A", first.id());
        assertEquals(Instant.parse("2026-10-25T15:05:21.353Z"), first.datetime());
        assertEquals(3.312939, first.cloudCover(), 1e-9);
        assertEquals(32719, first.epsg());
        assertEquals("sentinel-2c", first.platform());
        assertEquals("19LCL", first.mgrsTile());
        assertEquals("https://earth-search.aws.element84.com/v1/collections/sentinel-2-l2a/items/S2C_19LCL_20261025_0_L2A",
                first.selfHref());
        assertEquals("Polygon", first.footprint().get("type"));
        assertEquals("19MCM", items.get(1).mgrsTile());

        Map<String, SceneAsset> assets = first.assets();
        assertTrue(assets.keySet().stream().noneMatch(k -> k.endsWith("-jp2")), assets.keySet().toString());
        assertFalse(assets.containsKey("cloud"), "jp2 quality masks are not COGs");
        assertFalse(assets.containsKey("thumbnail"));
        SceneAsset red = assets.get("red");
        assertTrue(red.href().startsWith("https://") && red.href().endsWith("/B04.tif"), red.href());
        assertEquals(0.0001, red.scale(), 0);
        assertEquals(-0.1, red.offset(), 0);
        assertEquals(10, red.resolution(), 0);
        assertEquals(20, assets.get("swir16").resolution(), 0);
        assertEquals(60, assets.get("coastal").resolution(), 0);
        SceneAsset scl = assets.get("scl");
        assertEquals(1, scl.scale(), 0);
        assertEquals(0, scl.offset(), 0);
        assertEquals(20, scl.resolution(), 0);
        assertNotNull(assets.get("visual"));
    }

    @Test
    void retriesOnceOn5xx() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        String url = serve(new AtomicReference<>(), calls, 502, 200);
        assertEquals(3, new EarthSearchClient(url).search(BBOX, null, 40, 3).size());
        assertEquals(2, calls.get());
    }

    @Test
    void failsWithAReadableMessage() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        String url = serve(new AtomicReference<>(), calls, 503);
        IOException e = assertThrows(IOException.class, () -> new EarthSearchClient(url).search(BBOX, null, 40, 3));
        assertTrue(e.getMessage().contains("answered HTTP 503"), e.getMessage());
        assertEquals(2, calls.get());

        AtomicInteger badCalls = new AtomicInteger();
        stop();
        String badUrl = serve(new AtomicReference<>(), badCalls, 400);
        IOException bad = assertThrows(IOException.class, () -> new EarthSearchClient(badUrl).search(BBOX, null, 40, 3));
        assertTrue(bad.getMessage().contains("HTTP 400") && bad.getMessage().contains("oops"), bad.getMessage());
        assertEquals(1, badCalls.get(), "4xx is not retried");
    }

    @Test
    void projCodeAndMgrsFallbacks() throws Exception {
        String json = """
                {"type":"FeatureCollection","features":[{"id":"x","geometry":null,"links":[],
                 "properties":{"datetime":"2026-10-01T10:00:00Z","proj:code":"EPSG:32722",
                   "mgrs:utm_zone":9,"mgrs:latitude_band":"L","mgrs:grid_square":"CL"},
                 "assets":{"red":{"href":"s3://b/B04.tif","gsd":10},
                           "nir":{"href":"https://x/B08.tif","raster:bands":[{"scale":0.0001}]}}}]}""";
        SceneItem item = EarthSearchClient.parse(json).get(0);
        assertEquals(32722, item.epsg());
        assertEquals("09LCL", item.mgrsTile());
        assertEquals(10, item.assets().get("red").resolution(), 0);
        assertEquals(1, item.assets().get("red").scale(), 0);
        assertEquals(0.0001, item.assets().get("nir").scale(), 0);
        assertEquals(10, item.assets().get("nir").resolution(), 0);
        assertEquals(null, item.footprint());
        assertThrows(IOException.class, () -> EarthSearchClient.parse("{\"type\":\"oops\"}"));
        assertThrows(IOException.class, () -> EarthSearchClient.parse("not json"));
    }

    @Test
    void liveEarthSearch() throws Exception {
        assumeTrue("1".equals(System.getenv("SENTINEL_NETWORK_TEST")), "SENTINEL_NETWORK_TEST not set");
        List<SceneItem> items = new EarthSearchClient(EarthSearchClient.DEFAULT_STAC_URL)
                .search(BBOX, LocalDate.now().minusDays(60), 40, 5);
        assertFalse(items.isEmpty());
        for (int i = 1; i < items.size(); i++) {
            assertFalse(items.get(i).datetime().isAfter(items.get(i - 1).datetime()), "newest first");
        }
        SceneItem first = items.get(0);
        assertTrue(first.epsg() > 32600, "" + first.epsg());
        assertNotNull(first.mgrsTile());
        assertTrue(first.assets().keySet().containsAll(List.of("red", "green", "blue", "nir", "scl")));
        assertNotNull(first.footprint());
        System.out.println("Earth Search newest: " + first.id() + " " + first.datetime() + " cloud " + first.cloudCover());
    }
}
