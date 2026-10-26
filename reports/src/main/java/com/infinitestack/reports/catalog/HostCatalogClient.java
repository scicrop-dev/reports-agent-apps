package com.infinitestack.reports.catalog;

import java.util.List;

import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Reads the data catalog from the host through the internal plugin port, authenticated by the
 * plugin id and the internal token the host injected (IS_PLUGIN_ID, IS_PLUGIN_INTERNAL_TOKEN).
 */
public class HostCatalogClient implements CatalogClient {

    static final String PATH = "/api/extensions/internal/catalog/tables";

    private final RestClient http;
    private final String pluginId;
    private final String token;

    public HostCatalogClient(RestClient http, String pluginId, String token) {
        this.http = http;
        this.pluginId = pluginId;
        this.token = token;
    }

    @Override
    public List<CatalogTable> fetchTables() {
        TablesResponse response = http.get().uri(PATH)
                .header("X-IS-Plugin-Id", pluginId)
                .header("X-IS-Plugin-Internal-Token", token)
                .retrieve()
                .body(TablesResponse.class);
        return response == null || response.tables() == null ? List.of() : response.tables();
    }

    @Override
    public String origin() {
        return "host";
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TablesResponse(List<CatalogTable> tables) {}
}
