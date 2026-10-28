package com.infinitestack.reports.engine.flow;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One step of a Flow: an instance of a Block. Its id is also the namespace its outputs get in the
 * context and in the template ({{nodeId.port}}).
 *
 * @param values   fixed values for input ports, by port name
 * @param position where the visual editor draws the node; layout only, the engine ignores it
 * @param optional the report does not depend on this node: when a required input has no value (an
 *                 optional report input left empty, or an optional node upstream that produced
 *                 nothing) it is skipped, and when it fails or times out the failure is recorded in
 *                 the execution and the Flow goes on. Either way its outputs are all null and the
 *                 template shows the empty branch.
 */
public record FlowNode(String id, String block, Map<String, Object> values,
                       @JsonInclude(JsonInclude.Include.NON_NULL) Position position,
                       @JsonInclude(JsonInclude.Include.NON_DEFAULT) boolean optional) {

    public FlowNode {
        values = values == null ? Map.of() : values;
    }

    public FlowNode(String id, String block, Map<String, Object> values) {
        this(id, block, values, null, false);
    }

    public FlowNode(String id, String block, Map<String, Object> values, Position position) {
        this(id, block, values, position, false);
    }

    /** Canvas coordinates of the node's top-left corner, in pixels at zoom 1. */
    public record Position(double x, double y) {}
}
