package com.infinitestack.reports;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The app must boot and answer health even when the database is unreachable: the host completes
 * the installation on runtime-health, and the schema is prepared in the background.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:postgresql://127.0.0.1:1/unreachable",
        "infinitestack.plugin.internal-token="})
@AutoConfigureMockMvc
class ApplicationBootTest {

    private static final String BASE = "/api/plugins/reports";

    @Autowired MockMvc mvc;

    @Test
    void bootsAndServesThePanelWithoutADatabase() throws Exception {
        mvc.perform(get(BASE + "/api/runtime-health")).andExpect(content().string("ok"));
        mvc.perform(get(BASE + "/")).andExpect(status().isOk()).andExpect(content().string(containsString("/agents/apps")));
        mvc.perform(get(BASE + "/status")).andExpect(status().isOk());
        mvc.perform(get(BASE + "/api/me").header("X-IS-User-Id", "7").header("X-IS-Role", "scicrop"))
                .andExpect(jsonPath("$.id").value("7"))
                .andExpect(jsonPath("$.admin").value(true))
                .andExpect(jsonPath("$.databaseReady").value(false));
        mvc.perform(get(BASE + "/api/me")).andExpect(jsonPath("$.role").value("USER"));
        mvc.perform(get(BASE + "/api/reports")).andExpect(status().isServiceUnavailable());
    }
}
