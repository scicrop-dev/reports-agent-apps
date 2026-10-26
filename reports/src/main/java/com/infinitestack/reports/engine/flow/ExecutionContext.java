package com.infinitestack.reports.engine.flow;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The memory of one execution (MASTER section 11): the report inputs and, per node id, the outputs
 * that node produced. Namespaces keep two Blocks with the same output name apart.
 */
public final class ExecutionContext {

    private final Map<String, Object> inputs;
    private final Map<String, Map<String, Object>> values = new LinkedHashMap<>();

    public ExecutionContext(Map<String, Object> inputs) {
        this.inputs = Collections.unmodifiableMap(new LinkedHashMap<>(inputs));
    }

    public Map<String, Object> inputs() {
        return inputs;
    }

    public Map<String, Map<String, Object>> values() {
        return Collections.unmodifiableMap(values);
    }

    void put(String nodeId, Map<String, Object> outputs) {
        values.put(nodeId, Collections.unmodifiableMap(new LinkedHashMap<>(outputs)));
    }

    Object read(Binding binding) {
        if (binding instanceof Binding.Fixed fixed) return fixed.value();
        if (binding instanceof Binding.FromInput input) return inputs.get(input.inputName());
        Binding.FromNode node = (Binding.FromNode) binding;
        Map<String, Object> outputs = values.get(node.nodeId());
        return outputs == null ? null : outputs.get(node.port());
    }

    /** What the template sees: {@code inputs.*} plus one namespace per node. */
    public Map<String, Object> templateModel() {
        return templateModel(inputs, values);
    }

    public static Map<String, Object> templateModel(Map<String, Object> inputs, Map<String, ? extends Map<String, Object>> values) {
        Map<String, Object> model = new LinkedHashMap<>(values);
        model.put(FlowDefinition.INPUTS, inputs);
        return model;
    }
}
