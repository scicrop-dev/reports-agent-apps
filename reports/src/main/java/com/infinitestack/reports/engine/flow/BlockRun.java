package com.infinitestack.reports.engine.flow;

import java.time.Instant;
import java.util.Map;

/** What happened to one node during an execution (ARCHITECTURE section 25). */
public record BlockRun(
        int position,
        String nodeId,
        String blockId,
        String blockVersion,
        Status status,
        Map<String, Object> inputs,
        Map<String, Object> outputs,
        String error,
        Instant startedAt,
        Instant finishedAt,
        long durationMs) {

    /**
     * SKIPPED: an optional node whose required input had no value. An optional node that fails keeps
     * FAILED or TIMED_OUT, but the execution goes on (FlowNode#optional).
     */
    public enum Status { COMPLETED, SKIPPED, FAILED, TIMED_OUT }
}
