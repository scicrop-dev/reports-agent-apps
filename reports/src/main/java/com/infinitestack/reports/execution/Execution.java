package com.infinitestack.reports.execution;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.infinitestack.reports.engine.flow.BlockRun;

/**
 * A concrete run of a report version (MASTER section 3.5). It records the exact versions used so
 * that it can be reproduced (ARCHITECTURE section 36).
 *
 * @param id null for a preview, which is never stored
 * @param values outputs per node id, i.e. the execution context without the inputs
 * @param blockVersions block id to the version that ran
 */
public record Execution(
        Long id,
        long reportId,
        int reportVersion,
        ExecutionStatus status,
        Map<String, Object> inputs,
        Map<String, Map<String, Object>> values,
        Map<String, String> blockVersions,
        ExecutionError error,
        String startedBy,
        Instant createdAt,
        Instant finishedAt,
        Long durationMs,
        List<BlockRun> blocks) {
}
