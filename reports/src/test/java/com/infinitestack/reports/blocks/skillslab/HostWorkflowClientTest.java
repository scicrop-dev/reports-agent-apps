package com.infinitestack.reports.blocks.skillslab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

/** The client against a local server that answers like the host's internal workflow port. */
class HostWorkflowClientTest {

    HttpServer server;
    final List<String> requests = new CopyOnWriteArrayList<>();
    volatile String lastBody;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    HostWorkflowClient client(String token) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/extensions/internal/workflows/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String query = exchange.getRequestURI().getQuery();
            requests.add(exchange.getRequestMethod() + " " + path + (query == null ? "" : "?" + query)
                    + " id=" + exchange.getRequestHeaders().getFirst("X-IS-Plugin-Id")
                    + " token=" + exchange.getRequestHeaders().getFirst("X-IS-Plugin-Internal-Token"));
            lastBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            int status = 200;
            String answer;
            if (path.endsWith("/workflows/not-granted/runs")) {
                status = 403;
                answer = "{\"status\":403,\"error\":\"Forbidden\",\"message\":\"Workflow not-granted of project p1 is not granted to app reports\"}";
            } else if (path.endsWith("/runs")) {
                status = 202;
                answer = "{\"triggerId\":\"t-9\",\"projectId\":\"p1\",\"workflow\":\"wf\"}";
            } else if (path.endsWith("/output")) {
                answer = "{\"trigger_id\":\"t-9\",\"exists\":true,\"size\":12,\"from\":0,\"next\":12,\"more\":false,\"content\":\"hello\\nworld\\n\",\"running\":false}";
            } else if (path.endsWith("/stop")) {
                status = 409;
                answer = "{\"triggerId\":\"t-9\",\"stopped\":false,\"error\":\"Run is not running\"}";
            } else {
                answer = "{\"trigger_id\":\"t-9\",\"status\":\"COMPLETED\",\"done\":true,\"result\":\"SUCCESS\"}";
            }
            byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        return new HostWorkflowClient(RestClient.builder().baseUrl(base).build(), "reports", token);
    }

    @Test
    void startsFollowsAndReadsARun() throws Exception {
        HostWorkflowClient client = client("secret");

        String triggerId = client.start("p1", "wf", Map.of("text", "AC-1"), "user-42");
        JsonNode sent = new ObjectMapper().readTree(lastBody);
        WorkflowRuns.RunState state = client.get("p1", triggerId);
        WorkflowRuns.OutputSlice slice = client.output("p1", triggerId, 0, 1024);

        assertEquals("t-9", triggerId);
        assertEquals("AC-1", sent.path("params").path("text").asText());
        assertEquals("user-42", sent.path("onBehalfOf").asText());
        assertTrue(state.done());
        assertEquals("SUCCESS", state.result());
        assertTrue(slice.exists());
        assertEquals("hello\nworld\n", slice.content());
        assertFalse(slice.more());
        assertEquals(List.of(
                "POST /api/extensions/internal/workflows/projects/p1/workflows/wf/runs id=reports token=secret",
                "GET /api/extensions/internal/workflows/projects/p1/runs/t-9 id=reports token=secret",
                "GET /api/extensions/internal/workflows/projects/p1/runs/t-9/output?from=0&max=1024 id=reports token=secret"),
                requests);
    }

    @Test
    void aRefusalCarriesTheHostMessage() throws Exception {
        HostWorkflowClient client = client("secret");

        IOException e = assertThrows(IOException.class, () -> client.start("p1", "not-granted", Map.of(), null));

        assertTrue(e.getMessage().contains("403"), e.getMessage());
        assertTrue(e.getMessage().contains("is not granted to app reports"), e.getMessage());
    }

    @Test
    void stoppingAFinishedRunIsNotAnError() throws Exception {
        assertFalse(client("secret").stop("p1", "t-9"));
    }

    @Test
    void withoutTheTokenNothingIsCalled() throws Exception {
        HostWorkflowClient client = client("");

        IOException e = assertThrows(IOException.class, () -> client.start("p1", "wf", Map.of(), null));

        assertTrue(e.getMessage().contains("outside the IS"), e.getMessage());
        assertTrue(requests.isEmpty());
    }
}
