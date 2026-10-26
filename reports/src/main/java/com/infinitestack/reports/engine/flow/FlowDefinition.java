package com.infinitestack.reports.engine.flow;

import java.util.List;

/** A Flow as stored in a report version (ARCHITECTURE section 8). */
public record FlowDefinition(List<FlowNode> nodes, List<FlowConnection> connections) {

    /** Namespace of the report inputs; no node may use it as id. */
    public static final String INPUTS = "inputs";

    public FlowDefinition {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        connections = connections == null ? List.of() : List.copyOf(connections);
    }

    public static FlowDefinition empty() {
        return new FlowDefinition(List.of(), List.of());
    }
}
