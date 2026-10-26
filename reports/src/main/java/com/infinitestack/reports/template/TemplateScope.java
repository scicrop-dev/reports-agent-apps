package com.infinitestack.reports.template;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.infinitestack.reports.engine.DataType;
import com.infinitestack.reports.engine.block.BlockPort;
import com.infinitestack.reports.engine.block.BlockRegistry;
import com.infinitestack.reports.engine.block.ReportBlock;
import com.infinitestack.reports.engine.flow.FlowDefinition;
import com.infinitestack.reports.engine.flow.FlowNode;
import com.infinitestack.reports.report.InputDefinition;

/**
 * The variables a template may use, known before anything runs: {@code inputs.<name>} for each
 * report input and {@code <node>.<port>} for each output the Flow's Blocks declare.
 */
public final class TemplateScope {

    private final Map<String, Map<String, Field>> namespaces = new LinkedHashMap<>();

    public record Field(DataType type, String source) {}

    /** One variable as offered to whoever writes the template. */
    public record Variable(String path, DataType type, String source) {}

    public static TemplateScope of(List<InputDefinition> inputs, FlowDefinition flow, BlockRegistry registry) {
        TemplateScope scope = new TemplateScope();
        Map<String, Field> inputFields = new LinkedHashMap<>();
        for (InputDefinition input : inputs) inputFields.put(input.name(), new Field(input.type(), "report input"));
        scope.namespaces.put(FlowDefinition.INPUTS, inputFields);
        for (FlowNode node : flow.nodes()) {
            Optional<ReportBlock> block = registry.find(node.block());
            if (block.isEmpty() || node.id() == null) continue;
            Map<String, Field> fields = new LinkedHashMap<>();
            for (BlockPort port : block.get().definition().outputs()) {
                fields.put(port.name(), new Field(port.type(), node.block()));
            }
            scope.namespaces.putIfAbsent(node.id(), fields);
        }
        return scope;
    }

    public boolean hasNamespace(String namespace) {
        return namespaces.containsKey(namespace);
    }

    public Optional<Field> field(String namespace, String name) {
        Map<String, Field> fields = namespaces.get(namespace);
        return Optional.ofNullable(fields == null ? null : fields.get(name));
    }

    public List<Variable> variables() {
        List<Variable> out = new ArrayList<>();
        namespaces.forEach((ns, fields) -> fields.forEach((name, field) ->
                out.add(new Variable(ns + "." + name, field.type(), field.source()))));
        return out;
    }
}
