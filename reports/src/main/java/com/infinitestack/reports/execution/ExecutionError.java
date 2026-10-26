package com.infinitestack.reports.execution;

import java.util.List;

/**
 * Why an execution failed, with enough to find the cause (ARCHITECTURE section 26): the stage,
 * the node and Block when one was involved, and the individual problems.
 */
public record ExecutionError(ExecutionStatus stage, String nodeId, String blockId, String message, List<String> details) {

    public ExecutionError {
        details = details == null ? List.of() : List.copyOf(details);
    }
}
