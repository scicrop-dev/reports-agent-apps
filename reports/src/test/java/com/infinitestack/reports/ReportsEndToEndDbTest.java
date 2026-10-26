package com.infinitestack.reports;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.infinitestack.reports.catalog.CatalogService;
import com.infinitestack.reports.engine.block.ReportBlock;
import com.infinitestack.reports.support.TestBlocks;

/**
 * The whole path against a real Postgres: create a report, write a draft, see the Block gated by
 * the catalog, release it, preview, publish, execute, re-render the result, revoke.
 *
 * <p>Runs only when REPORTS_TEST_DB_URL (plus REPORTS_TEST_DB_USER and REPORTS_TEST_DB_PASSWORD)
 * points to a disposable database; skipped otherwise, so the build does not need one. It creates
 * the app tables in the schema {@code reports_test} and the table {@code gold.reports_test_area},
 * and drops both before the app boots. The catalog is read from information_schema (no host token).
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReportsEndToEndDbTest {

    private static final String URL = System.getenv("REPORTS_TEST_DB_URL");

    @BeforeAll
    static void requireDatabase() throws Exception {
        assumeTrue(URL != null && !URL.isBlank(), "REPORTS_TEST_DB_URL not set: end-to-end test skipped");
        // Before the Spring context starts: the app prepares its schema in the background on boot.
        try (java.sql.Connection c = java.sql.DriverManager.getConnection(URL,
                System.getenv().getOrDefault("REPORTS_TEST_DB_USER", "postgres"),
                System.getenv().getOrDefault("REPORTS_TEST_DB_PASSWORD", ""));
             java.sql.Statement st = c.createStatement()) {
            st.execute("DROP SCHEMA IF EXISTS reports_test CASCADE");
            st.execute("CREATE SCHEMA IF NOT EXISTS gold");
            st.execute("DROP TABLE IF EXISTS gold.reports_test_area");
        }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> URL == null ? "jdbc:postgresql://localhost:1/none" : URL);
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("REPORTS_TEST_DB_USER", "postgres"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("REPORTS_TEST_DB_PASSWORD", ""));
        registry.add("infinitestack.app.schema", () -> "reports_test");
        registry.add("infinitestack.plugin.internal-token", () -> "");
    }

    @TestConfiguration
    static class Blocks {
        @Bean ReportBlock upper() { return new TestBlocks.Upper(); }
        @Bean ReportBlock area() { return new TestBlocks.NeedsTable(); }
    }

    private static final String BASE = "/api/plugins/reports/api";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired CatalogService catalog;
    @Autowired ObjectMapper mapper;

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String role) {
        return request.header("X-IS-User-Id", role.toLowerCase()).header("X-IS-Username", role.toLowerCase())
                .header("X-IS-Role", role).header("X-IS-Principal-Type", "user").contentType(MediaType.APPLICATION_JSON);
    }

    private JsonNode json(MockHttpServletRequestBuilder request, int expectedStatus) throws Exception {
        String body = mvc.perform(request).andExpect(status().is(expectedStatus)).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return body.isEmpty() ? null : mapper.readTree(body);
    }

    /** The CAR Blocks are not in this context: the ready-made is listed as not runnable but can still be copied. */
    @Test
    void instantiatesAReadyMadeReport() throws Exception {
        JsonNode list = json(as(get(BASE + "/ready-made"), "USER"), 200);
        JsonNode laudo = null;
        for (JsonNode item : list) if ("car-socioenvironmental".equals(item.get("id").asText())) laudo = item;
        org.junit.jupiter.api.Assertions.assertNotNull(laudo, list.toString());
        org.junit.jupiter.api.Assertions.assertFalse(laudo.get("runnable").asBoolean());
        org.junit.jupiter.api.Assertions.assertEquals(6, laudo.get("blocks").size());
        org.junit.jupiter.api.Assertions.assertFalse(laudo.at("/blocks/0/usable").asBoolean());
        org.junit.jupiter.api.Assertions.assertTrue(laudo.at("/blocks/0/problem").asText().contains("does not exist"));

        mvc.perform(as(get(BASE + "/ready-made/car-socioenvironmental"), "USER"))
                .andExpect(jsonPath("$.content.flow.nodes.length()").value(6))
                .andExpect(jsonPath("$.content.templateHtml", containsString("{{property.codImovel}}")));
        mvc.perform(as(get(BASE + "/ready-made/nope"), "USER")).andExpect(status().isNotFound());
        mvc.perform(as(post(BASE + "/ready-made/nope/instantiate"), "USER").content("{}")).andExpect(status().isNotFound());

        JsonNode created = json(as(post(BASE + "/ready-made/car-socioenvironmental/instantiate"), "USER")
                .content("{\"name\":\"Laudo da fazenda\"}"), 201);
        long id = created.get("id").asLong();
        org.junit.jupiter.api.Assertions.assertEquals("Laudo da fazenda", created.get("name").asText());
        org.junit.jupiter.api.Assertions.assertEquals("user", created.get("ownerId").asText());
        org.junit.jupiter.api.Assertions.assertEquals("car-socioenvironmental", created.get("readyMadeId").asText());
        org.junit.jupiter.api.Assertions.assertEquals(laudo.get("version").asText(), created.get("readyMadeVersion").asText());

        // Version 1 is a draft holding the ready-made content; it cannot run until the Blocks exist and are released.
        mvc.perform(as(get(BASE + "/reports/" + id + "/versions/1"), "USER"))
                .andExpect(jsonPath("$.version.status").value("DRAFT"))
                .andExpect(jsonPath("$.version.inputs[0].name").value("codImovel"))
                .andExpect(jsonPath("$.version.flow.nodes.length()").value(6))
                .andExpect(jsonPath("$.version.page.marginMm").value(18))
                .andExpect(jsonPath("$.version.templateCss", containsString("counter(pages)")));

        // Without a body the name is the ready-made's own.
        mvc.perform(as(post(BASE + "/ready-made/car-socioenvironmental/instantiate"), "OTHER"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value(laudo.get("name").asText()))
                .andExpect(jsonPath("$.ownerId").value("other"));
        mvc.perform(as(get(BASE + "/reports"), "USER")).andExpect(jsonPath("$[*].readyMadeId", hasItem("car-socioenvironmental")));
    }

    @Test
    void fromDraftToRenderedResult() throws Exception {
        catalog.refresh();

        mvc.perform(as(get(BASE + "/me"), "USER")).andExpect(jsonPath("$.databaseReady").value(true));

        long id = json(as(post(BASE + "/reports"), "USER").content("{\"name\":\"Area report\"}"), 201).get("id").asLong();

        String draft = mapper.writeValueAsString(java.util.Map.of(
                "inputs", List.of(java.util.Map.of("name", "code", "type", "string", "required", true, "label", "Code")),
                "flow", java.util.Map.of(
                        "nodes", List.of(java.util.Map.of("id", "area", "block", "test-area"),
                                java.util.Map.of("id", "title", "block", "test-upper")),
                        "connections", List.of(java.util.Map.of("from", "inputs.code", "to", "area.code"),
                                java.util.Map.of("from", "area.code", "to", "title.text"))),
                "templateHtml", "<h1>{{title.text}}</h1><p>{{area.details.name}}</p><script>x()</script>",
                "templateCss", "h1 { color: #333; }"));

        // Saved with problems: the Blocks are not released, and test-area has no data yet.
        JsonNode saved = json(as(put(BASE + "/reports/" + id + "/versions/1"), "USER").content(draft), 200);
        org.junit.jupiter.api.Assertions.assertFalse(saved.at("/validation/blocks").isEmpty(), saved.toString());

        // A USER sees no Block; an admin sees test-area as MISSING_DATA and cannot release it.
        mvc.perform(as(get(BASE + "/blocks"), "USER")).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(as(get(BASE + "/blocks/test-area"), "ADMIN"))
                .andExpect(jsonPath("$.availability.status").value("MISSING_DATA"));
        mvc.perform(as(post(BASE + "/blocks/test-area/release"), "ADMIN")).andExpect(status().isConflict());
        mvc.perform(as(post(BASE + "/blocks/test-upper/release"), "USER")).andExpect(status().isForbidden());

        // The data arrives in the catalog; now both Blocks can be released.
        jdbc.execute("CREATE TABLE gold.reports_test_area (code TEXT, name TEXT)");
        mvc.perform(as(post(BASE + "/catalog/refresh"), "ADMIN")).andExpect(status().isOk());
        mvc.perform(as(post(BASE + "/blocks/test-area/release"), "ADMIN")).andExpect(jsonPath("$.usable").value(true));
        mvc.perform(as(post(BASE + "/blocks/test-upper/release"), "SCICROP")).andExpect(status().isOk());
        mvc.perform(as(get(BASE + "/blocks"), "USER")).andExpect(jsonPath("$[*].definition.id", hasItem("test-area")));

        // Preview stores nothing and returns sanitized HTML.
        mvc.perform(as(post(BASE + "/reports/" + id + "/preview"), "USER").content("{\"version\":1,\"inputs\":{\"code\":\"ab\"}}"))
                .andExpect(jsonPath("$.execution.status").value("COMPLETED"))
                .andExpect(jsonPath("$.html", containsString("<h1>AB</h1>")))
                .andExpect(jsonPath("$.html", containsString("<p>Area ab</p>")))
                .andExpect(jsonPath("$.html", not(containsString("<script"))));
        mvc.perform(as(post(BASE + "/reports/" + id + "/preview"), "USER").content("{\"version\":1,\"inputs\":{}}"))
                .andExpect(status().isUnprocessableEntity());

        // Publish, then the version is frozen and is the default to execute.
        mvc.perform(as(post(BASE + "/reports/" + id + "/versions/1/publish"), "USER")).andExpect(status().isOk());
        mvc.perform(as(put(BASE + "/reports/" + id + "/versions/1"), "USER").content(draft)).andExpect(status().isConflict());
        mvc.perform(as(put(BASE + "/reports/" + id), "OTHER").content("{\"name\":\"x\"}")).andExpect(status().isForbidden());

        JsonNode execution = json(as(post(BASE + "/reports/" + id + "/execute"), "USER").content("{\"inputs\":{\"code\":\"zz\"}}"), 200);
        long executionId = execution.get("id").asLong();
        org.junit.jupiter.api.Assertions.assertEquals("COMPLETED", execution.get("status").asText(), execution.toString());
        org.junit.jupiter.api.Assertions.assertEquals(2, execution.get("blocks").size());
        org.junit.jupiter.api.Assertions.assertEquals("1.0.0", execution.at("/blockVersions/test-area").asText());

        mvc.perform(as(get(BASE + "/executions/" + executionId + "/result"), "USER"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy", containsString("default-src 'none'")))
                .andExpect(content().string(containsString("<h1>ZZ</h1>")));
        mvc.perform(as(get(BASE + "/executions/" + executionId + "/result?format=pdf"), "USER"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"));
        mvc.perform(as(post(BASE + "/reports/" + id + "/preview/pdf"), "USER").content("{\"inputs\":{\"code\":\"ab\"}}"))
                .andExpect(status().isOk());

        // Re-running reproduces the same values with the same block versions.
        JsonNode rerun = json(as(post(BASE + "/executions/" + executionId + "/rerun"), "USER"), 200);
        org.junit.jupiter.api.Assertions.assertTrue(rerun.get("identicalValues").asBoolean(), rerun.toString());
        org.junit.jupiter.api.Assertions.assertEquals(0, rerun.get("warnings").size());
        org.junit.jupiter.api.Assertions.assertEquals(executionId, rerun.at("/execution/rerunOf").asLong());

        // The data disappears: the released Block stops being usable and the execution says why.
        jdbc.execute("DROP TABLE gold.reports_test_area");
        catalog.refresh();
        JsonNode failed = json(as(post(BASE + "/reports/" + id + "/execute"), "USER").content("{\"inputs\":{\"code\":\"zz\"}}"), 200);
        org.junit.jupiter.api.Assertions.assertEquals("FAILED", failed.get("status").asText());
        org.junit.jupiter.api.Assertions.assertEquals("VALIDATING", failed.at("/error/stage").asText());
        org.junit.jupiter.api.Assertions.assertTrue(failed.at("/error/details/0").asText().contains("not in the catalog"), failed.toString());

        mvc.perform(as(delete(BASE + "/blocks/test-upper/release"), "ADMIN")).andExpect(status().isNoContent());
        mvc.perform(as(get(BASE + "/reports/" + id + "/executions"), "USER")).andExpect(jsonPath("$.length()").value(3));
        mvc.perform(as(post(BASE + "/reports/" + id + "/versions"), "USER")).andExpect(jsonPath("$.version").value(2));
        mvc.perform(as(post(BASE + "/reports/" + id + "/versions"), "USER")).andExpect(status().isConflict());
        String changed = draft.replace("<h1>{{title.text}}</h1>", "<h2>{{title.text}}</h2>");
        mvc.perform(as(put(BASE + "/reports/" + id + "/versions/2"), "USER").content(changed)).andExpect(status().isOk());
        mvc.perform(as(get(BASE + "/reports/" + id + "/versions/1/diff/2"), "USER"))
                .andExpect(jsonPath("$.parts[?(@.name == 'templateHtml')].changed").value(true))
                .andExpect(jsonPath("$.parts[?(@.name == 'flow')].changed").value(false));
        mvc.perform(as(get(BASE + "/reports/" + id + "/versions/2/variables"), "USER"))
                .andExpect(jsonPath("$[*].path", hasItem("area.details")));
    }
}
