package com.infinitestack.reports.blocks.isgeosam;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.infinitestack.reports.blocks.sathub.imagery.Utm;
import com.infinitestack.reports.blocks.skillslab.WorkflowRuns;
import com.infinitestack.reports.engine.block.BlockRegistry;
import com.infinitestack.reports.engine.block.ExecutionMetadata;

class IsGeoSamFieldCandidatesBlockTest {

    static final ExecutionMetadata META = new ExecutionMetadata(7L, 1, 2, "user-42", "USER");
    static final int EPSG = 32721;
    /** 100 x 80 px of 10 m, top-left corner at (654770, 8596060). */
    static final List<Double> TRANSFORM = List.of(10.0, 0.0, 654770.0, 0.0, -10.0, 8596060.0);

    @TempDir
    Path staging;

    @TempDir
    Path etc;

    /** A host whose run ends after {@code pollsUntilDone} polls; {@code onDone} is what the script wrote. */
    static final class FakeRuns implements WorkflowRuns {
        String result = "SUCCESS";
        String log = "";
        int pollsUntilDone = 2;
        Runnable onDone = () -> {};
        List<GrantedWorkflow> grants = List.of(new GrantedWorkflow("is-geo-sam", true, "is-geo-sam"));
        int polls;
        int starts;
        String startedProject;
        boolean stopped;
        Map<String, Object> startedParams;
        String startedFor;

        @Override
        public List<GrantedWorkflow> granted(String projectId) {
            return grants;
        }

        @Override
        public String start(String projectId, String workflow, Map<String, Object> params, String onBehalfOf) {
            starts++;
            startedProject = projectId;
            startedParams = params;
            startedFor = onBehalfOf;
            return "t-1";
        }

        @Override
        public RunState get(String projectId, String triggerId) {
            if (++polls < pollsUntilDone) return new RunState(false, "RUNNING");
            onDone.run();
            return new RunState(true, result);
        }

        @Override
        public OutputSlice output(String projectId, String triggerId, long from, int max) {
            int start = (int) Math.min(from, log.length());
            int end = Math.min(log.length(), start + max);
            return new OutputSlice(true, log.length(), end, end < log.length(), log.substring(start, end));
        }

        @Override
        public boolean stop(String projectId, String triggerId) {
            stopped = true;
            return true;
        }
    }

    static final class FakeResults implements SceneResults {
        final Map<String, SceneRun> runs = new LinkedHashMap<>();
        final List<Candidate> candidates = new ArrayList<>();
        final List<String> asked = new ArrayList<>();

        @Override
        public Optional<SceneRun> latestRun(String sceneSha256) {
            asked.add(sceneSha256);
            return Optional.ofNullable(runs.get(sceneSha256));
        }

        @Override
        public List<Candidate> candidates(String runId, int limit) {
            return candidates.stream().limit(limit).toList();
        }
    }

    static SceneResults.SceneRun finished(String status, Integer count, String error) {
        return new SceneResults.SceneRun("run-1", status, error, count, "sam2.1_hiera_small", "t-0", "2026-10-28T15:02:11Z", 500);
    }

    /** A square of 20 x 20 px whose top-left pixel is (col, row), as lon/lat GeoJSON. */
    static String square(int col, int row) {
        StringBuilder ring = new StringBuilder();
        int[][] corners = {{col, row}, {col + 20, row}, {col + 20, row + 20}, {col, row + 20}, {col, row}};
        for (int[] c : corners) {
            double[] lonLat = Utm.inverse(654770.0 + c[0] * 10, 8596060.0 - c[1] * 10, EPSG);
            if (ring.length() > 0) ring.append(',');
            ring.append('[').append(lonLat[0]).append(',').append(lonLat[1]).append(']');
        }
        return "{\"type\":\"MultiPolygon\",\"coordinates\":[[[" + ring + "]]]}";
    }

    static String grayPng() throws Exception {
        BufferedImage image = new BufferedImage(100, 80, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 80; y++) for (int x = 0; x < 100; x++) image.setRGB(x, y, 0x404040);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
    }

    static Map<String, Object> inputs() throws Exception {
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("trueColor", grayPng());
        in.put("imageCrs", "EPSG:" + EPSG);
        in.put("imageTransform", TRANSFORM);
        in.put("imageBounds", List.of(-55.57, -12.71, -55.56, -12.70));
        in.put("widthPx", 100);
        in.put("heightPx", 80);
        in.put("pixelSizeM", 10.0);
        in.put("scene", Map.of("id", "S2B_21LXF_20261022_0_L2A"));
        in.put("geometry", Map.of("type", "Polygon", "coordinates", List.of()));
        in.put("codImovel", "MT-5107065-ABC");
        in.put("projectId", "p-1");
        return in;
    }

    IsGeoSamFieldCandidatesBlock block(FakeRuns runs, FakeResults results) {
        return new IsGeoSamFieldCandidatesBlock(runs, results, settings(), Duration.ofMillis(5));
    }

    IsGeoSamSettings settings() {
        return new IsGeoSamSettings("reports", "is-geo-sam", "", etc.resolve("reports-workflows.json"), staging);
    }

    static String sha(Map<String, Object> in) {
        @SuppressWarnings("unchecked")
        Map<String, Object> geometry = (Map<String, Object>) in.get("geometry");
        return SceneRequest.of(in, geometry, (String) in.get("codImovel")).sceneSha256();
    }

    @Test
    void definitionIsValidForTheRegistry() {
        new BlockRegistry(List.of(block(new FakeRuns(), new FakeResults())));
    }

    @Test
    void sceneIdentityIsTheOneOfIsGeoSam() {
        // is_geo_sam.scene._sha256([Path("reports-isgeosam-test.json")]) of a file holding [{"block_id":"x"}]
        assertEquals("bc7a4520498817b0623ad28bcb67b591895fab14baa529fc8ebeae3c0a30f3a6",
                SceneRequest.sceneSha256("reports-isgeosam-test.json", "[{\"block_id\":\"x\"}]".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void theSameImageIsTheSameRequestWhateverTheNumberTypes() throws Exception {
        Map<String, Object> a = inputs();
        Map<String, Object> b = inputs();
        b.put("widthPx", 100.0);
        b.put("imageTransform", List.of(10, 0, 654770, 0, -10, 8596060));
        SceneRequest first = SceneRequest.of(a, null, "X");
        SceneRequest second = SceneRequest.of(b, null, "X");
        assertArrayEquals(first.json(), second.json());
        assertEquals(first.fileName(), second.fileName());
        assertTrue(first.fileName().matches("reports-isgeosam-[0-9a-f]{32}\\.json"), first.fileName());

        b.put("trueColor", "data:image/png;base64,AAAA");
        assertNotEquals(first.sceneSha256(), SceneRequest.of(b, null, "X").sceneSha256());
    }

    @Test
    void theRequestCarriesWhatIsGeoSamReads() throws Exception {
        String json = new String(SceneRequest.of(inputs(), Map.of("type", "Point", "coordinates", List.of(1, 2)), "MT-1").json(),
                StandardCharsets.UTF_8);
        assertTrue(json.startsWith("[{\"block_id\":\"car-property\",\"outputs\":{\"codImovel\":\"MT-1\"}}"), json);
        assertTrue(json.contains("{\"block_id\":\"car-property-geometry\",\"outputs\":{\"geometry\":{"), json);
        assertTrue(json.contains("\"block_id\":\"sathub-latest-image\",\"outputs\":{\"found\":true,\"trueColor\":\"data:image/png;base64,"), json);
        assertTrue(json.contains("\"imageCrs\":\"EPSG:32721\",\"imageTransform\":[10.0,0.0,654770.0,0.0,-10.0,8596060.0]"), json);
        assertTrue(json.contains("\"widthPx\":100,\"heightPx\":80,\"pixelSizeM\":10.0"), json);
        assertFalse(json.contains("execution_id"), "nothing that changes per execution");
    }

    @Test
    void withoutAnImageNothingRuns() throws Exception {
        FakeRuns runs = new FakeRuns();
        FakeResults results = new FakeResults();
        Map<String, Object> in = inputs();
        in.put("trueColor", null);
        Map<String, Object> out = block(runs, results).execute(in, META);
        assertEquals("NO_IMAGE", out.get("status"));
        assertEquals(false, out.get("ready"));
        assertEquals(List.of(), out.get("candidates"));
        assertEquals(0, runs.starts);
        assertTrue(results.asked.isEmpty());
    }

    @Test
    void aFinishedSegmentationOfTheSameImageIsShownWithoutRunning() throws Exception {
        FakeRuns runs = new FakeRuns();
        FakeResults results = new FakeResults();
        Map<String, Object> in = inputs();
        results.runs.put(sha(in), finished("COMPLETED_WITH_REVIEW_REQUIRED", 2, null));
        results.candidates.add(new SceneResults.Candidate(7, 4.0, 0.9612, 0.98, "PENDING_REVIEW", List.of(), square(10, 10)));
        results.candidates.add(new SceneResults.Candidate(3, 2.456, 0.9, 0.95, "PENDING_REVIEW",
                List.of("touches_image_edge", "clipped_by_aoi"), square(60, 40)));

        Map<String, Object> out = block(runs, results).execute(in, META);

        assertEquals(0, runs.starts);
        assertEquals("COMPLETED_WITH_REVIEW_REQUIRED", out.get("status"));
        assertEquals(true, out.get("ready"));
        assertEquals(true, out.get("reused"));
        assertEquals(true, out.get("hasCandidates"));
        assertEquals(2, out.get("candidateCount"));
        assertEquals(6.46, out.get("totalAreaHa"));
        assertEquals("run-1", out.get("runId"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) out.get("candidates");
        assertEquals(1, list.get(0).get("number"));
        assertEquals(0.961, list.get(0).get("modelScore"));
        assertEquals(false, list.get(0).get("hasFlags"));
        assertEquals("touches_image_edge, clipped_by_aoi", list.get(1).get("reviewFlags"));
        assertNotNull(out.get("map"));
        assertTrue(Files.list(staging).findAny().isEmpty(), "nothing written to staging");
    }

    @Test
    void aNewImageIsSentToTheScriptAndReadWhenItEnds() throws Exception {
        FakeRuns runs = new FakeRuns();
        FakeResults results = new FakeResults();
        Map<String, Object> in = inputs();
        String sha = sha(in);
        runs.onDone = () -> results.runs.put(sha, finished("COMPLETED_NO_CANDIDATES", 0, null));

        Map<String, Object> out = block(runs, results).execute(in, META);

        assertEquals(1, runs.starts);
        String file = (String) runs.startedParams.get("file");
        assertEquals(Map.of("file", file, "appId", "reports"), runs.startedParams, "appId: owner of the tables until IS_APP_ID");
        assertEquals("user-42", runs.startedFor);
        assertTrue(Files.isRegularFile(staging.resolve(file)), "the script deletes it, not the Block");
        assertEquals(SceneRequest.sceneSha256(file, Files.readAllBytes(staging.resolve(file))), sha);
        assertEquals("COMPLETED_NO_CANDIDATES", out.get("status"));
        assertEquals(true, out.get("ready"));
        assertEquals(false, out.get("hasCandidates"));
        assertEquals(false, out.get("reused"));
        assertEquals("t-1", out.get("triggerId"));
    }

    @Test
    void aSlowSegmentationIsLeftRunning() throws Exception {
        FakeRuns runs = new FakeRuns();
        runs.pollsUntilDone = Integer.MAX_VALUE;
        Map<String, Object> in = inputs();
        in.put("waitSeconds", 0);

        Map<String, Object> out = block(runs, new FakeResults()).execute(in, META);

        assertEquals("PROCESSING", out.get("status"));
        assertEquals(true, out.get("processing"));
        assertEquals(false, out.get("ready"));
        assertEquals("t-1", out.get("triggerId"));
        assertFalse(runs.stopped);
        assertEquals(1, Files.list(staging).count());
    }

    @Test
    void aRequestAlreadyWaitingOrRunningIsNotSentAgain() throws Exception {
        FakeRuns runs = new FakeRuns();
        FakeResults results = new FakeResults();
        Map<String, Object> in = inputs();
        @SuppressWarnings("unchecked")
        SceneRequest same = SceneRequest.of(in, (Map<String, Object>) in.get("geometry"), (String) in.get("codImovel"));
        Files.write(staging.resolve(same.fileName()), same.json());

        assertEquals("PROCESSING", block(runs, results).execute(in, META).get("status"));
        assertEquals(0, runs.starts);

        Files.delete(staging.resolve(same.fileName()));
        results.runs.put(same.sceneSha256(), new SceneResults.SceneRun("run-2", "RUNNING", null, null, "m", "t-5", null, 40));
        Map<String, Object> out = block(runs, results).execute(in, META);
        assertEquals("PROCESSING", out.get("status"));
        assertEquals("t-5", out.get("triggerId"));
        assertEquals(0, runs.starts);
    }

    @Test
    void aStaleRunningRunIsAskedAgain() throws Exception {
        FakeRuns runs = new FakeRuns();
        FakeResults results = new FakeResults();
        Map<String, Object> in = inputs();
        String sha = sha(in);
        results.runs.put(sha, new SceneResults.SceneRun("run-2", "RUNNING", null, null, "m", "t-5", null, 7200));
        runs.onDone = () -> results.runs.put(sha, finished("COMPLETED_NO_CANDIDATES", 0, null));

        assertEquals("COMPLETED_NO_CANDIDATES", block(runs, results).execute(in, META).get("status"));
        assertEquals(1, runs.starts);
    }

    @Test
    void aRejectedImageIsAnAnswerNotAFailure() throws Exception {
        FakeResults results = new FakeResults();
        Map<String, Object> in = inputs();
        results.runs.put(sha(in), finished("REJECTED", null, "imageBounds disagree with imageTransform"));
        Map<String, Object> out = block(new FakeRuns(), results).execute(in, META);
        assertEquals("REJECTED", out.get("status"));
        assertEquals(false, out.get("ready"));
        assertTrue(((String) out.get("message")).contains("imageBounds disagree"));
    }

    @Test
    void aFailedRunFailsTheBlockWithTheScriptsReason() throws Exception {
        FakeRuns runs = new FakeRuns();
        runs.result = "FAILED";
        runs.log = "INFO start\nIS_GEO_SAM_RESULT {\"status\": \"FAILED\", \"error\": \"out of memory\"}\n";
        Exception e = assertThrows(IllegalStateException.class, () -> block(runs, new FakeResults()).execute(inputs(), META));
        assertTrue(e.getMessage().contains("FAILED, out of memory"), e.getMessage());
    }

    @Test
    void aWorkflowNotGrantedFailsBeforeWritingAnything() throws Exception {
        FakeRuns runs = new FakeRuns();
        runs.grants = List.of(new WorkflowRuns.GrantedWorkflow("other", true, null));
        Exception e = assertThrows(IllegalArgumentException.class, () -> block(runs, new FakeResults()).execute(inputs(), META));
        assertTrue(e.getMessage().contains("not granted"), e.getMessage());
        assertTrue(Files.list(staging).findAny().isEmpty());
        assertEquals(0, runs.starts);
    }

    @Test
    void withoutAGrantTheBlockSaysWhatToWrite() throws Exception {
        Map<String, Object> in = inputs();
        in.remove("projectId");
        Exception e = assertThrows(IllegalArgumentException.class, () -> block(new FakeRuns(), new FakeResults()).execute(in, META));
        assertTrue(e.getMessage().contains("reports-workflows.json") && e.getMessage().contains("\"workflow\":\"is-geo-sam\""),
                e.getMessage());
    }

    @Test
    void theProjectComesFromTheGrantOfIsGeoSam() throws Exception {
        Files.writeString(etc.resolve("reports-workflows.json"), "{\"workflows\":[{\"projectId\":\"p-other\",\"workflow\":\"wf-report\"},"
                + "{\"projectId\":\"p-sam\",\"workflow\":\"is-geo-sam\"}]}");
        FakeRuns runs = new FakeRuns();
        runs.pollsUntilDone = Integer.MAX_VALUE;
        Map<String, Object> in = inputs();
        in.remove("projectId");
        in.put("waitSeconds", 0);
        block(runs, new FakeResults()).execute(in, META);
        assertEquals("p-sam", runs.startedProject);

        IsGeoSamSettings fixed = new IsGeoSamSettings("reports", "is-geo-sam", "p-env", etc.resolve("reports-workflows.json"), staging);
        assertEquals("p-env", fixed.projectId().orElseThrow(), "reports.isgeosam.project-id wins");
    }

    @Test
    void theMapDrawsEachCandidateWhereItIs() throws Exception {
        String map = new CandidateMapRenderer().render(grayPng(), "EPSG:" + EPSG, TRANSFORM,
                List.of(new CandidateMapRenderer.Shape(1, square(10, 10))));
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(map.substring(map.indexOf(',') + 1))));
        assertEquals(100, image.getWidth());
        assertNotEquals(0x404040, image.getRGB(10, 20) & 0xFFFFFF, "outline on the square's left edge");
        assertNotEquals(0x404040, image.getRGB(15, 15) & 0xFFFFFF, "light fill inside");
        assertEquals(0x404040, image.getRGB(70, 70) & 0xFFFFFF, "untouched outside");
        assertNull(new CandidateMapRenderer().render(grayPng(), "EPSG:31982", TRANSFORM, List.of()), "only UTM WGS 84 or lon/lat");
    }
}
