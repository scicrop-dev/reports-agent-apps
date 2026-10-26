package com.infinitestack.reports.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Engine limits (ARCHITECTURE sections 27-28). They exist so that one report cannot hold a thread,
 * the database or the host's memory indefinitely.
 */
@ConfigurationProperties("reports.limits")
public record ReportsLimits(
        @DefaultValue("50") int maxNodesPerFlow,
        @DefaultValue("30") int blockTimeoutSeconds,
        @DefaultValue("120") int executionTimeoutSeconds,
        @DefaultValue("262144") int maxTemplateBytes,
        @DefaultValue("5242880") int maxRenderedBytes) {

    public static ReportsLimits defaults() {
        return new ReportsLimits(50, 30, 120, 262144, 5242880);
    }
}
