package com.infinitestack.reports.catalog;

import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Gives a recent view of the data catalog. Inside the host (internal token present) it asks the
 * host; outside, it falls back to the database's information_schema. A snapshot is reused for a
 * short while so that listing Blocks does not hit the host on every request. When a read fails
 * the previous snapshot is kept, marked with the error.
 */
@Service
public class CatalogService {

    private static final Logger log = LoggerFactory.getLogger(CatalogService.class);

    private final CatalogClient client;
    private final Duration cacheFor;
    private volatile CatalogSnapshot current;

    @Autowired
    public CatalogService(JdbcTemplate jdbc,
                          @Value("${infinitestack.host.base-url:http://127.0.0.1:8081}") String hostBaseUrl,
                          @Value("${infinitestack.plugin.runtime-id:reports}") String pluginId,
                          @Value("${infinitestack.plugin.internal-token:}") String internalToken,
                          @Value("${reports.catalog.cache-seconds:60}") long cacheSeconds) {
        this(internalToken == null || internalToken.isBlank()
                        ? new InformationSchemaCatalogClient(jdbc)
                        : new HostCatalogClient(RestClient.builder().baseUrl(hostBaseUrl).build(), pluginId, internalToken),
                Duration.ofSeconds(cacheSeconds));
        log.info("[reports] data catalog origin: {}", client.origin());
    }

    CatalogService(CatalogClient client, Duration cacheFor) {
        this.client = client;
        this.cacheFor = cacheFor;
    }

    public CatalogSnapshot snapshot() {
        CatalogSnapshot snapshot = current;
        if (snapshot != null && snapshot.error() == null
                && snapshot.fetchedAt().plus(cacheFor).isAfter(Instant.now())) {
            return snapshot;
        }
        return refresh();
    }

    public synchronized CatalogSnapshot refresh() {
        try {
            current = new CatalogSnapshot(client.origin(), Instant.now(), client.fetchTables(), null);
        } catch (Exception e) {
            String error = "could not read the data catalog (" + client.origin() + "): " + e.getMessage();
            log.warn("[reports] {}", error);
            CatalogSnapshot previous = current;
            current = previous == null
                    ? new CatalogSnapshot(client.origin(), Instant.now(), null, error)
                    : new CatalogSnapshot(previous.origin(), previous.fetchedAt(), previous.tables(), error);
        }
        return current;
    }
}
