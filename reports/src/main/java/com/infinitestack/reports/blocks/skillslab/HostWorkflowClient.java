package com.infinitestack.reports.blocks.skillslab;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * {@link WorkflowRuns} over the host's internal port, authenticated by the plugin id and the internal
 * token the host injected (IS_PLUGIN_ID, IS_PLUGIN_INTERNAL_TOKEN), like {@code HostCatalogClient}.
 * Without the token (the app running outside the IS) every call fails with a clear message.
 */
public class HostWorkflowClient implements WorkflowRuns {

    static final String PATH = "/api/extensions/internal/workflows/projects/{projectId}";

    private final RestClient http;
    private final String pluginId;
    private final String token;
    private final ObjectMapper json = new ObjectMapper();

    public HostWorkflowClient(RestClient http, String pluginId, String token) {
        this.http = http;
        this.pluginId = pluginId;
        this.token = token;
    }

    @Override
    public List<GrantedWorkflow> granted(String projectId) throws IOException {
        JsonNode answer;
        try {
            answer = call(() -> http.get().uri(PATH + "/workflows", projectId)
                    .headers(this::authenticate).retrieve().body(JsonNode.class));
        } catch (NotFound e) {
            throw new IOException("the IS host has no workflow port for agent apps (it needs infinitestack-back#1281): update the IS back", e);
        }
        List<GrantedWorkflow> out = new ArrayList<>();
        for (JsonNode row : answer.path("workflows")) {
            out.add(new GrantedWorkflow(row.path("workflow").asText(""), row.path("exists").asBoolean(false),
                    row.path("name").asText(null)));
        }
        return out;
    }

    @Override
    public String start(String projectId, String workflow, Map<String, Object> params, String onBehalfOf) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("params", params);
        if (onBehalfOf != null && !onBehalfOf.isBlank()) body.put("onBehalfOf", onBehalfOf);
        JsonNode answer = call(() -> http.post().uri(PATH + "/workflows/{workflow}/runs", projectId, workflow)
                .headers(this::authenticate).body(body).retrieve().body(JsonNode.class));
        String triggerId = answer.path("triggerId").asText(null);
        if (triggerId == null || triggerId.isBlank()) throw new IOException("the host started no run (no triggerId)");
        return triggerId;
    }

    @Override
    public RunState get(String projectId, String triggerId) throws IOException {
        JsonNode run = call(() -> http.get().uri(PATH + "/runs/{triggerId}", projectId, triggerId)
                .headers(this::authenticate).retrieve().body(JsonNode.class));
        return new RunState(run.path("done").asBoolean(false), run.path("result").asText("UNKNOWN"));
    }

    @Override
    public OutputSlice output(String projectId, String triggerId, long from, int max) throws IOException {
        JsonNode slice = call(() -> http.get().uri(PATH + "/runs/{triggerId}/output?from={from}&max={max}", projectId, triggerId, from, max)
                .headers(this::authenticate).retrieve().body(JsonNode.class));
        return new OutputSlice(slice.path("exists").asBoolean(false), slice.path("size").asLong(0),
                slice.path("next").asLong(from), slice.path("more").asBoolean(false), slice.path("content").asText(""));
    }

    @Override
    public boolean stop(String projectId, String triggerId) throws IOException {
        try {
            call(() -> http.post().uri(PATH + "/runs/{triggerId}/stop", projectId, triggerId)
                    .headers(this::authenticate).retrieve().body(JsonNode.class));
            return true;
        } catch (NotRunning e) {
            return false;
        }
    }

    private void authenticate(org.springframework.http.HttpHeaders headers) {
        headers.set("X-IS-Plugin-Id", pluginId);
        headers.set("X-IS-Plugin-Internal-Token", token);
    }

    /** Turns the host's error answers into one readable message (its "message"/"error" field). */
    private JsonNode call(HostCall call) throws IOException {
        if (token == null || token.isBlank()) {
            throw new IOException("Skills Lab runs need the IS host (no IS_PLUGIN_INTERNAL_TOKEN): this app is running outside the IS");
        }
        try {
            JsonNode answer = call.run();
            return answer == null ? json.createObjectNode() : answer;
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 409) throw new NotRunning();
            if (status == 404) throw new NotFound(reason(e.getResponseBodyAsString(), status));
            throw new IOException("the host answered " + status + ": " + reason(e.getResponseBodyAsString(), status), e);
        } catch (RestClientException e) {
            throw new IOException("could not reach the IS host: " + e.getMessage(), e);
        }
    }

    /**
     * The host's own message when it sent one. In production it does not (server.error.include-message
     * is on only in the dev profile): the body says just "Forbidden", so the status explains it.
     */
    private String reason(String body, int status) {
        if (body != null && !body.isBlank()) {
            try {
                JsonNode node = json.readTree(body);
                String message = node.path("message").asText("");
                if (!message.isBlank() && !message.equals(node.path("error").asText())) return message;
            } catch (Exception ignored) {
                // not JSON: show it as it came
                return body.length() > 300 ? body.substring(0, 300) + "..." : body;
            }
        }
        return switch (status) {
            case 401, 403 -> "the workflow is not granted to the reports app, or the user running the report has no access to the project";
            case 404 -> "the project, the workflow or the run was not found";
            case 400 -> "the host refused the request (params must be a JSON object of at most 64 KB)";
            default -> "no details";
        };
    }

    @FunctionalInterface
    private interface HostCall {
        JsonNode run();
    }

    /** 404 from any route; the list turns it into "the host is too old". */
    private static final class NotFound extends IOException {
        NotFound(String message) {
            super("the host answered 404: " + message);
        }
    }

    /** 409 from the stop route: the run had already finished. */
    private static final class NotRunning extends IOException {
        NotRunning() {
            super("run is not running");
        }
    }
}
