package com.infinitestack.reports.blocks.isgeosam;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.infinitestack.reports.blocks.skillslab.WorkflowRuns;
import com.infinitestack.reports.engine.DataType;
import com.infinitestack.reports.engine.block.BlockDefinition;
import com.infinitestack.reports.engine.block.BlockDocs;
import com.infinitestack.reports.engine.block.BlockPort;
import com.infinitestack.reports.engine.block.BlockQueries;
import com.infinitestack.reports.engine.block.ExecutionMetadata;
import com.infinitestack.reports.engine.block.ReportBlock;

/**
 * {@code isgeosam-field-candidates}: field (talhão) candidates that SAM proposes over the latest
 * satellite image of an area, through the Skills Lab package is-geo-sam (scripts/is-geo-sam).
 *
 * <p>SAM on CPU takes about a minute per 1024 px tile, close to the report's 120 s, so the Block does
 * not depend on finishing in time:
 * <ol>
 * <li>it builds the workflow JSON is-geo-sam reads from the image outputs ({@link SceneRequest}),
 * deterministic, so the same image is the same scene for the script;</li>
 * <li>a finished run of that scene in {@code gold.is_geo_sam_run} is shown at once, with no new run;</li>
 * <li>otherwise it writes the JSON to the lake staging and starts the is-geo-sam workflow through the
 * host's workflow port (as {@code skillslab-python}), then waits up to {@code waitSeconds};</li>
 * <li>a run still going after that is left running: the Block answers PROCESSING, and the next
 * execution of the report finds the result in gold.</li>
 * </ol>
 * The script owns the gold tables and deletes the staging file when it ends.
 */
@Component
public class IsGeoSamFieldCandidatesBlock implements ReportBlock {

    static final String RESULT_MARKER = "IS_GEO_SAM_RESULT ";
    static final String GRANTS_FILE = "/opt/infinitestack/etc/reports-workflows.json";

    static final String PROCESSING = "PROCESSING";
    static final String NO_IMAGE = "NO_IMAGE";
    static final List<String> COMPLETED = List.of("COMPLETED_WITH_REVIEW_REQUIRED", "COMPLETED_NO_CANDIDATES");
    static final String REJECTED = "REJECTED";

    static final int DEFAULT_WAIT_SECONDS = 60;
    static final int MAX_WAIT_SECONDS = 90;
    static final Duration BLOCK_TIMEOUT = Duration.ofSeconds(100);
    /** A request or a RUNNING run older than this is taken as dead (backend restarted) and asked again. */
    static final Duration STALE_AFTER = Duration.ofMinutes(30);
    static final int MAX_CANDIDATES = 300;
    static final int LOG_TAIL_BYTES = 65_536;

    private final WorkflowRuns runs;
    private final SceneResults results;
    private final IsGeoSamSettings settings;
    private final Duration pollInterval;
    private final CandidateMapRenderer renderer = new CandidateMapRenderer();
    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    public IsGeoSamFieldCandidatesBlock(WorkflowRuns runs, BlockQueries queries, IsGeoSamSettings settings) {
        this(runs, new GoldSceneResults(queries), settings, Duration.ofSeconds(2));
    }

    IsGeoSamFieldCandidatesBlock(WorkflowRuns runs, SceneResults results, IsGeoSamSettings settings, Duration pollInterval) {
        this.runs = runs;
        this.results = results;
        this.settings = settings;
        this.pollInterval = pollInterval;
    }

    @Override
    public BlockDefinition definition() {
        return new BlockDefinition("isgeosam-field-candidates", "Field candidates (SAM)",
                "Field (talhao) candidates that SAM proposes over the latest satellite image of an area, for human review",
                "1.0.0",
                List.of(
                        BlockPort.optional("trueColor", DataType.STRING, "connect sathub-latest-image.trueColor; without it the Block answers NO_IMAGE"),
                        BlockPort.optional("imageCrs", DataType.STRING, "connect sathub-latest-image.imageCrs"),
                        BlockPort.optional("imageTransform", DataType.ARRAY, "connect sathub-latest-image.imageTransform"),
                        BlockPort.optional("imageBounds", DataType.ARRAY, "connect sathub-latest-image.imageBounds"),
                        BlockPort.optional("widthPx", DataType.NUMBER, "connect sathub-latest-image.widthPx"),
                        BlockPort.optional("heightPx", DataType.NUMBER, "connect sathub-latest-image.heightPx"),
                        BlockPort.optional("pixelSizeM", DataType.NUMBER, "connect sathub-latest-image.pixelSizeM"),
                        BlockPort.optional("scene", DataType.OBJECT, "connect sathub-latest-image.scene"),
                        BlockPort.optional("geometry", DataType.GEOMETRY,
                                "the area (GeoJSON, lon/lat); candidates are clipped to it; connect report-area.geometry"),
                        BlockPort.optional("codImovel", DataType.STRING, "CAR property code, kept as metadata of the scene"),
                        BlockPort.optional("projectId", DataType.STRING,
                                "Skills Lab project with is-geo-sam; default: the project of the is-geo-sam grant of this app"),
                        BlockPort.optional("workflow", DataType.STRING, "workflow that runs is-geo-sam/main.py; default is-geo-sam"),
                        BlockPort.optional("waitSeconds", DataType.NUMBER,
                                "how long to wait for a new segmentation, 0 to " + MAX_WAIT_SECONDS + " seconds; default "
                                        + DEFAULT_WAIT_SECONDS + "; the run goes on after it")),
                List.of(
                        BlockPort.required("status", DataType.STRING,
                                "COMPLETED_WITH_REVIEW_REQUIRED, COMPLETED_NO_CANDIDATES, REJECTED, PROCESSING or NO_IMAGE"),
                        BlockPort.required("ready", DataType.BOOLEAN, "the segmentation finished (COMPLETED_*): candidates and map are final"),
                        BlockPort.required("processing", DataType.BOOLEAN, "the segmentation is still running: generate the report again later"),
                        BlockPort.required("hasCandidates", DataType.BOOLEAN, "at least one candidate"),
                        BlockPort.optional("message", DataType.STRING, "why there is no result (rejected image, still running, no image)"),
                        BlockPort.required("candidates", DataType.ARRAY,
                                "largest first: {number, objectId, areaHa, modelScore, stabilityScore, status, reviewFlags, hasFlags}"),
                        BlockPort.required("candidateCount", DataType.NUMBER, "candidates of the run (all, even past the list limit)"),
                        BlockPort.required("totalAreaHa", DataType.NUMBER, "sum of the listed candidates' areas, hectares"),
                        BlockPort.required("truncated", DataType.BOOLEAN, "the list stops at " + MAX_CANDIDATES + " candidates"),
                        BlockPort.optional("features", DataType.OBJECT, "the listed candidates as a GeoJSON FeatureCollection (EPSG:4326)"),
                        BlockPort.optional("map", DataType.STRING, "the image with the numbered candidates, PNG data URI"),
                        BlockPort.required("reused", DataType.BOOLEAN, "the result came from an earlier segmentation of the same image"),
                        BlockPort.optional("runId", DataType.STRING, "is-geo-sam run (gold.is_geo_sam_run.run_id)"),
                        BlockPort.optional("triggerId", DataType.STRING, "the workflow run, as shown in Workloads"),
                        BlockPort.optional("model", DataType.STRING, "the model that segmented, e.g. sam2.1_hiera_small"),
                        BlockPort.optional("segmentedAt", DataType.STRING, "when the segmentation finished, UTC ISO-8601")),
                GoldSceneResults.REQUIREMENTS,
                BLOCK_TIMEOUT);
    }

    @Override
    public BlockDocs docs() {
        return new BlockDocs("Skills Lab",
                "Proposes field (talhao) outlines over the latest satellite image of an area with SAM (Segment Anything), "
                        + "through the Skills Lab package is-geo-sam. Every candidate is PENDING_REVIEW: a proposal to check, not a field boundary.",
                List.of(
                        "Builds the workflow JSON is-geo-sam reads from the image (sathub-latest-image outputs) and the area.",
                        "Looks in gold.is_geo_sam_run for a finished segmentation of that same image; if there is one, uses it right away.",
                        "Otherwise writes the JSON to the lake staging and starts the is-geo-sam workflow on behalf of the user, like skillslab-python.",
                        "Waits up to waitSeconds. SAM takes about a minute per 1024 px tile on CPU: if it has not finished, the run goes on "
                                + "and the Block answers PROCESSING; the next execution of the report reads the result from gold.",
                        "Reads the candidates of the run (largest first) and draws them, numbered, over the image."),
                List.of(
                        "Connect trueColor, imageCrs, imageTransform, imageBounds, widthPx, heightPx, pixelSizeM and scene to the outputs of "
                                + "the same name of sathub-latest-image, and geometry to report-area.geometry (or car-property-geometry.geometry).",
                        "Keep sathub-latest-image's sizePx at 1024 or less: one SAM tile, the only size that may end within the report.",
                        "In the template: {{#<node>.ready}} map and table {{/<node>.ready}}, {{#<node>.processing}} a notice {{/<node>.processing}}."),
                List.of(
                        "Candidates are proposals of a model for human review (PENDING_REVIEW): not a CAR, cadastral or legal boundary.",
                        "The first report of a new image usually comes out PROCESSING; generate it again a few minutes later.",
                        "The image changes when a newer cloud-free scene appears: that is a new segmentation.",
                        "One segmentation at a time on the instance (lock): requests queue behind each other.",
                        "Needs, on the instance: is-geo-sam installed in a Skills Lab project with a workflow is-geo-sam running "
                                + "is-geo-sam/main.py, that workflow granted to the reports app in " + GRANTS_FILE
                                + " (the app takes the project from there), and write access to the lake staging. The app creates "
                                + "the gold.is_geo_sam_* tables itself once granted (schemaOnly), which lets the Block be released.",
                        "Works only inside the IS (it needs the host's internal token)."),
                "is-geo-sam (Skills Lab, SAM 2.1 on CPU) over the Sentinel-2 image of sathub-latest-image; gold.is_geo_sam_*",
                "{{#<node>.ready}}<img src=\"{{<node>.map}}\">{{#<node>.candidates}}<p>{{number}}: {{areaHa}} ha</p>{{/<node>.candidates}}{{/<node>.ready}}");
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> inputs, ExecutionMetadata metadata) throws Exception {
        if (inputs.get("trueColor") == null) {
            return empty(NO_IMAGE, "there is no satellite image of the area to segment", false, null, null);
        }
        SceneRequest request = SceneRequest.of(inputs, geometry(inputs.get("geometry")), text(inputs.get("codImovel")));

        Optional<SceneResults.SceneRun> known = results.latestRun(request.sceneSha256());
        if (known.isPresent() && isFinished(known.get())) return fromRun(inputs, known.get(), true, null);
        if (known.isPresent() && "RUNNING".equals(known.get().status())
                && known.get().ageSeconds() < STALE_AFTER.toSeconds()) {
            return empty(PROCESSING, "this image is being segmented (started "
                    + Math.round(known.get().ageSeconds()) + " s ago); generate the report again in a few minutes",
                    false, known.get().runId(), known.get().triggerId());
        }
        Path file = settings.stagingDir().resolve(request.fileName());
        if (isPending(file)) {
            return empty(PROCESSING, "this image is waiting to be segmented; generate the report again in a few minutes",
                    false, null, null);
        }

        String projectId = text(inputs.get("projectId")) != null ? text(inputs.get("projectId"))
                : settings.projectId().orElseThrow(() -> new IllegalArgumentException(settings.missingProjectMessage()));
        String workflow = text(inputs.get("workflow")) != null ? text(inputs.get("workflow")) : settings.workflow();
        requireGranted(projectId, workflow);

        write(file, request.json());
        Instant startedAt = Instant.now();
        String triggerId;
        try {
            triggerId = runs.start(projectId, workflow, settings.params(Map.of("file", request.fileName())),
                    metadata == null ? null : metadata.userId());
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(file);
            throw e;
        }

        // An interruption (the execution gave up on the Block) leaves the run going on purpose: its
        // result lands in gold and the next report reads it.
        WorkflowRuns.RunState state = waitUntilDone(projectId, triggerId, startedAt.plusSeconds(waitSeconds(inputs.get("waitSeconds"))));
        if (state == null) {
            return empty(PROCESSING, "the segmentation did not finish within the wait (run " + triggerId
                    + " goes on); generate the report again in a few minutes", false, null, triggerId);
        }
        Optional<SceneResults.SceneRun> done = results.latestRun(request.sceneSha256());
        if (done.isPresent() && isFinished(done.get())) return fromRun(inputs, done.get(), false, triggerId);
        throw new IllegalStateException("is-geo-sam run " + triggerId + " ended " + state.result() + ": "
                + failure(projectId, triggerId));
    }

    private Map<String, Object> fromRun(Map<String, Object> inputs, SceneResults.SceneRun run, boolean reused, String triggerId) {
        if (REJECTED.equals(run.status())) {
            Map<String, Object> out = empty(REJECTED, "is-geo-sam rejected the image: " + run.error(), reused, run.runId(),
                    triggerId != null ? triggerId : run.triggerId());
            out.put("model", run.model());
            out.put("segmentedAt", run.finishedAt());
            return out;
        }
        List<SceneResults.Candidate> found = results.candidates(run.runId(), MAX_CANDIDATES);
        List<Map<String, Object>> list = new ArrayList<>();
        List<Map<String, Object>> features = new ArrayList<>();
        List<CandidateMapRenderer.Shape> shapes = new ArrayList<>();
        double total = 0;
        for (SceneResults.Candidate c : found) {
            int number = list.size() + 1;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("number", number);
            item.put("objectId", c.objectId());
            item.put("areaHa", round(c.areaHa(), 2));
            item.put("modelScore", round(c.modelScore(), 3));
            item.put("stabilityScore", round(c.stabilityScore(), 3));
            item.put("status", c.status());
            item.put("reviewFlags", String.join(", ", c.reviewFlags()));
            item.put("hasFlags", !c.reviewFlags().isEmpty());
            list.add(item);
            total += c.areaHa() == null ? 0 : c.areaHa();
            shapes.add(new CandidateMapRenderer.Shape(number, c.geometry()));
            features.add(feature(item, c.geometry()));
        }
        int count = run.candidateCount() != null ? run.candidateCount() : list.size();

        Map<String, Object> out = base(run.status(), null, reused, run.runId(), triggerId != null ? triggerId : run.triggerId());
        out.put("ready", true);
        out.put("hasCandidates", !list.isEmpty());
        out.put("candidates", list);
        out.put("candidateCount", count);
        out.put("totalAreaHa", round(total, 2));
        out.put("truncated", count > list.size());
        out.put("features", Map.of("type", "FeatureCollection", "features", features));
        out.put("map", renderer.render(text(inputs.get("trueColor")), text(inputs.get("imageCrs")),
                inputs.get("imageTransform") instanceof List<?> l ? l : null, shapes));
        out.put("model", run.model());
        out.put("segmentedAt", run.finishedAt());
        return out;
    }

    private Map<String, Object> empty(String status, String message, boolean reused, String runId, String triggerId) {
        Map<String, Object> out = base(status, message, reused, runId, triggerId);
        out.put("ready", false);
        out.put("hasCandidates", false);
        out.put("candidates", List.of());
        out.put("candidateCount", 0);
        out.put("totalAreaHa", 0.0);
        out.put("truncated", false);
        out.put("features", null);
        out.put("map", null);
        out.put("model", null);
        out.put("segmentedAt", null);
        return out;
    }

    private static Map<String, Object> base(String status, String message, boolean reused, String runId, String triggerId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", status);
        out.put("processing", PROCESSING.equals(status));
        out.put("message", message);
        out.put("reused", reused);
        out.put("runId", runId);
        out.put("triggerId", triggerId);
        return out;
    }

    private Map<String, Object> feature(Map<String, Object> properties, String geometry) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", "Feature");
        try {
            out.put("geometry", json.readValue(geometry, Map.class));
        } catch (Exception e) {
            out.put("geometry", null);
        }
        out.put("properties", properties);
        return out;
    }

    private static boolean isFinished(SceneResults.SceneRun run) {
        return COMPLETED.contains(run.status()) || REJECTED.equals(run.status());
    }

    /** A request for this image already waits in staging (the script deletes the file when it ends). */
    private static boolean isPending(Path file) throws IOException {
        if (!Files.isRegularFile(file)) return false;
        Instant written = Files.getLastModifiedTime(file).toInstant();
        return written.isAfter(Instant.now().minus(STALE_AFTER));
    }

    /** Write then rename, so the script never reads half a file. */
    private void write(Path file, byte[] content) throws IOException {
        if (!Files.isDirectory(settings.stagingDir())) {
            throw new IOException("the lake staging " + settings.stagingDir() + " does not exist on this host (reports.isgeosam.staging-dir)");
        }
        Path part = file.resolveSibling(file.getFileName() + ".part");
        Files.write(part, content);
        Files.move(part, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private void requireGranted(String projectId, String workflow) throws IOException {
        List<WorkflowRuns.GrantedWorkflow> granted = runs.granted(projectId);
        for (WorkflowRuns.GrantedWorkflow grant : granted) {
            if (!grant.workflow().equals(workflow)) continue;
            if (!grant.exists()) {
                throw new IllegalArgumentException("workflow '" + workflow + "' is granted to the reports app but project "
                        + projectId + " has no such workflow any more");
            }
            return;
        }
        String others = granted.stream().map(WorkflowRuns.GrantedWorkflow::workflow).collect(Collectors.joining(", "));
        throw new IllegalArgumentException("workflow '" + workflow + "' of project " + projectId
                + " is not granted to the reports app: the IS admin lists it in " + GRANTS_FILE
                + " (granted in this project: " + (others.isEmpty() ? "none" : others) + ")");
    }

    /** @return the final state, or null when the deadline passed first */
    private WorkflowRuns.RunState waitUntilDone(String projectId, String triggerId, Instant deadline)
            throws IOException, InterruptedException {
        while (true) {
            WorkflowRuns.RunState state = runs.get(projectId, triggerId);
            if (state.done()) return state;
            if (!Instant.now().plus(pollInterval).isBefore(deadline)) return null;
            Thread.sleep(pollInterval.toMillis());
        }
    }

    /** The error of the script's last IS_GEO_SAM_RESULT line, or the end of its log. */
    private String failure(String projectId, String triggerId) {
        try {
            WorkflowRuns.OutputSlice slice = runs.output(projectId, triggerId, 0, 1);
            slice = runs.output(projectId, triggerId, Math.max(0, slice.size() - LOG_TAIL_BYTES), LOG_TAIL_BYTES);
            String log = slice.content();
            int at = log.lastIndexOf(RESULT_MARKER);
            if (at >= 0) {
                String line = log.substring(at + RESULT_MARKER.length()).lines().findFirst().orElse("");
                JsonNode result = json.readTree(line);
                return result.path("status").asText("?") + (result.path("error").isTextual() ? ", " + result.path("error").asText() : "");
            }
            return log.length() > 1_500 ? "..." + log.substring(log.length() - 1_500) : log;
        } catch (Exception e) {
            return "the run's output could not be read (" + e.getMessage() + ")";
        }
    }

    static int waitSeconds(Object value) {
        if (!(value instanceof Number n)) return DEFAULT_WAIT_SECONDS;
        return (int) Math.max(0, Math.min(MAX_WAIT_SECONDS, n.longValue()));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> geometry(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    private static String text(Object value) {
        return value == null || value.toString().isBlank() ? null : value.toString().trim();
    }

    private static Double round(Double value, int digits) {
        if (value == null) return null;
        double scale = Math.pow(10, digits);
        return Math.round(value * scale) / scale;
    }
}
