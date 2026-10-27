package com.infinitestack.reports.blocks.skillslab;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.infinitestack.reports.engine.DataType;
import com.infinitestack.reports.engine.block.BlockDefinition;
import com.infinitestack.reports.engine.block.BlockDocs;
import com.infinitestack.reports.engine.block.BlockPort;
import com.infinitestack.reports.engine.block.ExecutionMetadata;
import com.infinitestack.reports.engine.block.ReportBlock;

/**
 * {@code skillslab-python}: runs any Python script of the Skills Lab, through a workflow of its
 * project, and hands what the script printed as its result to the report.
 *
 * <p>The run goes through the host's workflow port (infinitestack-back#1280): it is an ordinary run
 * (Workloads, {@code lsruns}, the {@code proclimits} queue), started on behalf of the user running
 * the report. Which workflows the app may run is granted per instance, by the IS admin, in
 * {@code /opt/infinitestack/etc/reports-workflows.json}; nothing is granted by default.
 *
 * <p>The inputs reach the script's {@code --context} JSON. The script returns its result by printing
 * one line {@code IS_REPORTS_RESULT <json object>}; the last such line wins.
 */
@Component
public class SkillsLabPythonBlock implements ReportBlock {

    static final String RESULT_MARKER = "IS_REPORTS_RESULT ";

    /** The ports passed to the script under their own name, when bound. */
    static final List<String> VALUE_PORTS = List.of("text", "number", "date", "geometry", "data", "items");

    static final int DEFAULT_TIMEOUT_SECONDS = 100;
    static final int MIN_TIMEOUT_SECONDS = 5;
    static final int MAX_TIMEOUT_SECONDS = 110;
    /** Above the script's own limit, so the Block still stops the run and reads the log after it. */
    static final Duration BLOCK_TIMEOUT = Duration.ofSeconds(118);

    static final int SLICE_BYTES = 262_144;
    /** Only the end of a longer log is read: the result line is printed at the end. */
    static final long MAX_LOG_BYTES = 8L * 1024 * 1024;
    static final int LOG_TAIL_CHARS = 4_000;
    static final int ERROR_TAIL_CHARS = 1_500;

    private final WorkflowRuns runs;
    private final Duration pollInterval;
    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    public SkillsLabPythonBlock(WorkflowRuns runs) {
        this(runs, Duration.ofSeconds(1));
    }

    SkillsLabPythonBlock(WorkflowRuns runs, Duration pollInterval) {
        this.runs = runs;
        this.pollInterval = pollInterval;
    }

    @Override
    public BlockDefinition definition() {
        return new BlockDefinition("skillslab-python", "Python script (Skills Lab)",
                "Runs a Python script of the Skills Lab through a workflow of its project and returns the JSON the script printed",
                "1.0.0",
                List.of(
                        BlockPort.required("projectId", DataType.STRING, "Skills Lab project id"),
                        BlockPort.required("workflow", DataType.STRING,
                                "id of the project's workflow that runs the script; it must be granted to the reports app"),
                        BlockPort.optional("params", DataType.OBJECT,
                                "JSON object merged into the script's --context (fixed values)"),
                        BlockPort.optional("text", DataType.STRING, "passed to the script as context.text"),
                        BlockPort.optional("number", DataType.NUMBER, "passed to the script as context.number"),
                        BlockPort.optional("date", DataType.DATE, "passed to the script as context.date"),
                        BlockPort.optional("geometry", DataType.GEOMETRY, "passed to the script as context.geometry (GeoJSON)"),
                        BlockPort.optional("data", DataType.OBJECT, "passed to the script as context.data"),
                        BlockPort.optional("items", DataType.ARRAY, "passed to the script as context.items"),
                        BlockPort.optional("timeoutSeconds", DataType.NUMBER,
                                "how long to wait for the script, " + MIN_TIMEOUT_SECONDS + " to " + MAX_TIMEOUT_SECONDS
                                        + " seconds; default " + DEFAULT_TIMEOUT_SECONDS + "; the run is stopped after it")),
                List.of(
                        BlockPort.optional("result", DataType.OBJECT, "the JSON object of the last IS_REPORTS_RESULT line the script printed"),
                        BlockPort.required("hasResult", DataType.BOOLEAN, "the script printed a result"),
                        BlockPort.required("status", DataType.STRING, "how the run ended: SUCCESS or WARNING (any other ending fails the Block)"),
                        BlockPort.required("triggerId", DataType.STRING, "the run id, as shown in Workloads"),
                        BlockPort.required("log", DataType.STRING, "the end of the script's output, without the result line"),
                        BlockPort.required("durationSeconds", DataType.NUMBER, "from the start of the run to its end, including the queue")),
                List.of(),
                BLOCK_TIMEOUT);
    }

    @Override
    public BlockDocs docs() {
        return new BlockDocs("Skills Lab",
                "Runs any Python script of the Skills Lab while the report is generated and puts what the script returns in the "
                        + "report: numbers, tables, texts, images as data: URIs.",
                List.of(
                        "Starts a run of the workflow (projectId + workflow) that runs the script, on behalf of the user running the report; it shows in Workloads like any run.",
                        "params, and the text, number, date, geometry, data and items inputs that are bound, are merged into the --context JSON of the workflow's first script, for this run only.",
                        "Waits for the run to end, up to timeoutSeconds; after that the run is stopped and the Block fails.",
                        "Reads the run's output and takes the last line IS_REPORTS_RESULT <json object> as result; the rest of the output becomes log.",
                        "A run that does not end in SUCCESS or WARNING fails the Block, with the end of the output as the reason."),
                List.of(
                        "projectId and workflow: fixed values; the workflow id is the one in the project's Schedules.",
                        "In the script: context = resolve_agent_context() from infinitestack.agent (or json.loads of the argument after --context); then print('IS_REPORTS_RESULT ' + json.dumps(result)).",
                        "text, number, date, geometry, data, items: wire any output or report input of that type, e.g. inputs.codImovel into text or car-property-geometry.geometry into geometry.",
                        "Use the result in the template: {{<node>.result.field}}; test {{#<node>.hasResult}} first."),
                List.of(
                        "The IS admin grants each workflow to the reports app in /opt/infinitestack/etc/reports-workflows.json; a workflow not granted fails with 403.",
                        "The report waits for the script: it must end in about a minute and a half (the execution limit is 120 s). Heavy processing belongs in a scheduled script that writes a gold table, read by a database Block.",
                        "The run waits in the host's process queue (proclimits) when it is full, and that wait counts in the timeout.",
                        "The --context the script receives is capped at 64 KB: pass a key (a property code, an area id), not big data.",
                        "The script runs the code it has at that moment: changing the script changes the reports that use it.",
                        "Print the result line with json.dumps defaults (ASCII only) and in one line; only the last 8 MB of the output are read.",
                        "Works only inside the IS (it needs the host's internal token)."),
                "A Python script of a Skills Lab project, run by the IS workflow engine (infinitestack-back, internal workflow port)",
                "{{#<node>.hasResult}}<p>{{<node>.result.summary}}</p>{{/<node>.hasResult}}");
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> inputs, ExecutionMetadata metadata) throws Exception {
        String projectId = requiredText(inputs, "projectId");
        String workflow = requiredText(inputs, "workflow");
        Duration timeout = Duration.ofSeconds(timeoutSeconds(inputs.get("timeoutSeconds")));
        Map<String, Object> params = runParams(inputs);

        Instant startedAt = Instant.now();
        String triggerId = runs.start(projectId, workflow, params, metadata == null ? null : metadata.userId());

        WorkflowRuns.RunState state;
        try {
            state = waitUntilDone(projectId, triggerId, startedAt.plus(timeout));
        } catch (InterruptedException e) {
            // The execution gave up on this Block (its own timeout): do not leave the script running.
            stopQuietly(projectId, triggerId);
            Thread.currentThread().interrupt();
            throw e;
        }
        if (state == null) {
            stopQuietly(projectId, triggerId);
            throw new IllegalStateException("the script did not finish within " + timeout.toSeconds() + "s; run "
                    + triggerId + " was stopped");
        }
        double durationSeconds = Math.round(Duration.between(startedAt, Instant.now()).toMillis() / 100.0) / 10.0;

        ScriptOutput output = parse(readLog(projectId, triggerId));
        if (!"SUCCESS".equals(state.result()) && !"WARNING".equals(state.result())) {
            throw new IllegalStateException("Skills Lab run " + triggerId + " ended " + state.result()
                    + (output.log().isBlank() ? "" : ": " + tail(output.log(), ERROR_TAIL_CHARS)));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("result", output.result());
        out.put("hasResult", output.result() != null);
        out.put("status", state.result());
        out.put("triggerId", triggerId);
        out.put("log", tail(output.log(), LOG_TAIL_CHARS));
        out.put("durationSeconds", durationSeconds);
        return out;
    }

    /** {@code params} first, then each bound value port under its own name. */
    static Map<String, Object> runParams(Map<String, Object> inputs) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (inputs.get("params") instanceof Map<?, ?> fixed) {
            fixed.forEach((key, value) -> params.put(String.valueOf(key), value));
        }
        for (String port : VALUE_PORTS) {
            if (inputs.get(port) != null) params.put(port, inputs.get(port));
        }
        return params;
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

    /** The run's output; for a log over {@link #MAX_LOG_BYTES}, only its end. */
    private String readLog(String projectId, String triggerId) throws IOException {
        WorkflowRuns.OutputSlice slice = runs.output(projectId, triggerId, 0, SLICE_BYTES);
        if (!slice.exists()) return "";
        if (slice.size() > MAX_LOG_BYTES) {
            slice = runs.output(projectId, triggerId, slice.size() - MAX_LOG_BYTES, SLICE_BYTES);
        }
        StringBuilder log = new StringBuilder(slice.content());
        while (slice.more()) {
            slice = runs.output(projectId, triggerId, slice.next(), SLICE_BYTES);
            log.append(slice.content());
        }
        return log.toString();
    }

    record ScriptOutput(Map<String, Object> result, String log) {}

    /** Splits the output into the last result line and everything else. */
    ScriptOutput parse(String output) {
        Map<String, Object> result = null;
        StringBuilder log = new StringBuilder();
        for (String line : output.split("\r?\n", -1)) {
            int at = line.indexOf(RESULT_MARKER);
            if (at < 0) {
                log.append(line).append('\n');
                continue;
            }
            String text = line.substring(at + RESULT_MARKER.length()).trim();
            try {
                result = json.readValue(text, new TypeReference<LinkedHashMap<String, Object>>() {});
            } catch (Exception e) {
                throw new IllegalArgumentException("the script printed " + RESULT_MARKER.trim()
                        + " with something that is not a JSON object: " + tail(text, 200));
            }
        }
        return new ScriptOutput(result, log.toString().strip());
    }

    private void stopQuietly(String projectId, String triggerId) {
        try {
            runs.stop(projectId, triggerId);
        } catch (Exception ignored) {
            // Best effort: the run stays visible in Workloads, where it can be stopped by hand.
        }
    }

    static int timeoutSeconds(Object value) {
        if (!(value instanceof Number n)) return DEFAULT_TIMEOUT_SECONDS;
        return (int) Math.max(MIN_TIMEOUT_SECONDS, Math.min(MAX_TIMEOUT_SECONDS, n.longValue()));
    }

    private static String requiredText(Map<String, Object> inputs, String port) {
        Object value = inputs.get(port);
        if (value == null || value.toString().isBlank()) throw new IllegalArgumentException(port + " is required");
        return value.toString().trim();
    }

    private static String tail(String text, int chars) {
        return text.length() <= chars ? text : "..." + text.substring(text.length() - chars);
    }
}
