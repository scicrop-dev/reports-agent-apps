package com.infinitestack.reports.report;

import java.time.Instant;
import java.util.List;

import com.infinitestack.reports.engine.flow.FlowDefinition;

/**
 * A snapshot of what a report is: its inputs, Flow and template. Flow and template live inside the
 * version (and not as separately versioned entities) so that one row is enough to reproduce an
 * execution; reusable Flows and Templates come after the MVP (ROADMAP, Phase 1).
 */
public record ReportVersion(
        long reportId,
        int version,
        VersionStatus status,
        List<InputDefinition> inputs,
        FlowDefinition flow,
        String templateHtml,
        String templateCss,
        PageSettings page,
        String createdBy,
        Instant createdAt,
        Instant updatedAt,
        Instant publishedAt) {
}
