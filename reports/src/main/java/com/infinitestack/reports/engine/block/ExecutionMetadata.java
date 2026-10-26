package com.infinitestack.reports.engine.block;

/**
 * What a Block may know about the execution it runs in. It deliberately carries no other Block's
 * values and nothing about the template: a Block only sees its own inputs (ARCHITECTURE section 10).
 */
public record ExecutionMetadata(Long executionId, long reportId, int reportVersion, String userId, String userRole) {
}
