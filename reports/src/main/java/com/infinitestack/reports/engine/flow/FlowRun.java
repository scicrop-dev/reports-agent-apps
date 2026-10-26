package com.infinitestack.reports.engine.flow;

import java.util.List;

/** Result of running a validated Flow. {@code failure} is null when every node completed. */
public record FlowRun(ExecutionContext context, List<BlockRun> blocks, Failure failure) {

    public boolean completed() {
        return failure == null;
    }

    /** Identifies what failed (ARCHITECTURE section 26). */
    public record Failure(String nodeId, String blockId, String message) {}
}
