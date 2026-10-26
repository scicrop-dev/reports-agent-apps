package com.infinitestack.reports.engine.flow;

import java.util.Map;

/**
 * One step of a Flow: an instance of a Block. Its id is also the namespace its outputs get in the
 * context and in the template ({{nodeId.port}}).
 *
 * @param values fixed values for input ports, by port name
 */
public record FlowNode(String id, String block, Map<String, Object> values) {

    public FlowNode {
        values = values == null ? Map.of() : values;
    }
}
