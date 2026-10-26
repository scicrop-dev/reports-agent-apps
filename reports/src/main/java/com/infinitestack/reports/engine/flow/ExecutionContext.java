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

    /** What the template sees: {@code inputs.*}, {@code report.*} and one namespace per node. */
    public Map<String, Object> templateModel(Map<String, Object> report) {
        return templateModel(inputs, values, report);
    }

    public static Map<String, Object> templateModel(Map<String, Object> inputs, Map<String, ? extends Map<String, Object>> values,
                                                    Map<String, Object> report) {
        Map<String, Object> model = new LinkedHashMap<>(values);
        model.put(FlowDefinition.INPUTS, inputs);
        model.put(FlowDefinition.REPORT, report);
        return model;
    }

    /**
     * The {@code report} namespace: name, version, execution number (null in a preview) and when it
     * was generated, formatted for reading (dd/MM/yyyy HH:mm, America/Sao_Paulo) and as ISO-8601.
     */
    public static Map<String, Object> reportMetadata(String name, int version, Long executionId, java.time.Instant generatedAt) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("name", name);
        report.put("version", version);
        report.put("executionId", executionId);
        report.put("generatedAt", java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
                .withZone(java.time.ZoneId.of("America/Sao_Paulo")).format(generatedAt));
        report.put("generatedAtIso", generatedAt.toString());
        return report;
    }
}
