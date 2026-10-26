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

    public enum Status { COMPLETED, FAILED, TIMED_OUT }
}
