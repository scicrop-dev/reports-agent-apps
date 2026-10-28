package com.infinitestack.reports.engine.flow;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.infinitestack.reports.config.ReportsLimits;
import com.infinitestack.reports.engine.DataType;
import com.infinitestack.reports.engine.block.BlockDefinition;
import com.infinitestack.reports.engine.block.BlockPort;
import com.infinitestack.reports.engine.block.BlockRegistry;
import com.infinitestack.reports.engine.block.ReportBlock;
import com.infinitestack.reports.report.InputDefinition;

/**
 * Checks a Flow before anything runs: ids, Blocks, ports, types, required inputs and cycles. It
 * collects every problem so that the person editing the Flow fixes them in one pass.
 */
@Component
public class FlowValidator {

    private static final Pattern NODE_ID = Pattern.compile("[a-zA-Z][a-zA-Z0-9_]*");

    private final BlockRegistry registry;
    private final ReportsLimits limits;

    public FlowValidator(BlockRegistry registry, ReportsLimits limits) {
        this.registry = registry;
        this.limits = limits;
    }

    public ValidatedFlow validate(FlowDefinition flow, List<InputDefinition> reportInputs) throws FlowValidationException {
        List<String> problems = new ArrayList<>();
        if (flow.nodes().size() > limits.maxNodesPerFlow()) {
            problems.add("the flow has " + flow.nodes().size() + " nodes; the limit is " + limits.maxNodesPerFlow());
        }

        Map<String, InputDefinition> inputs = new HashMap<>();
        for (InputDefinition input : reportInputs) inputs.put(input.name(), input);

        // Nodes and their Blocks
        Map<String, FlowNode> nodes = new LinkedHashMap<>();
        Map<String, ReportBlock> blocks = new HashMap<>();
        for (FlowNode node : flow.nodes()) {
            if (node.id() == null || !NODE_ID.matcher(node.id()).matches()) {
                problems.add("node id '" + node.id() + "' is invalid (letters, digits and _; starts with a letter)");
                continue;
            }
            if (FlowDefinition.INPUTS.equals(node.id()) || FlowDefinition.REPORT.equals(node.id())) {
                problems.add("node id '" + node.id() + "' is reserved");
                continue;
            }
            if (nodes.putIfAbsent(node.id(), node) != null) {
                problems.add("duplicate node id '" + node.id() + "'");
                continue;
            }
            Optional<ReportBlock> block = registry.find(node.block());
            if (block.isEmpty()) {
                problems.add("node '" + node.id() + "': unknown block '" + node.block() + "'");
            } else {
                blocks.put(node.id(), block.get());
            }
        }

        // Bindings: fixed values first, then connections
        Map<String, Map<String, Binding>> bindings = new HashMap<>();
        for (FlowNode node : nodes.values()) {
            ReportBlock block = blocks.get(node.id());
            Map<String, Binding> nodeBindings = new LinkedHashMap<>();
            bindings.put(node.id(), nodeBindings);
            if (block == null) continue;
            for (Map.Entry<String, Object> value : node.values().entrySet()) {
                Optional<BlockPort> port = block.definition().input(value.getKey());
                if (port.isEmpty()) {
                    problems.add("node '" + node.id() + "': block " + node.block() + " has no input '" + value.getKey() + "'");
                    continue;
                }
                try {
                    nodeBindings.put(value.getKey(), new Binding.Fixed(port.get().type().coerce(value.getValue())));
                } catch (IllegalArgumentException e) {
                    problems.add("node '" + node.id() + "', input '" + value.getKey() + "': " + e.getMessage());
                }
            }
        }

        Map<String, Set<String>> dependsOn = new HashMap<>();
        for (String id : nodes.keySet()) dependsOn.put(id, new LinkedHashSet<>());

        for (FlowConnection connection : flow.connections()) {
            String label = "connection " + connection.from() + " -> " + connection.to();
            PortRef to = PortRef.parse(connection.to());
            PortRef from = PortRef.parse(connection.from());
            if (to == null || from == null) {
                problems.add(label + ": use 'node.port' (or 'inputs.name' as source)");
                continue;
            }
            ReportBlock target = blocks.get(to.node());
            if (target == null) {
                if (!nodes.containsKey(to.node())) problems.add(label + ": unknown target node '" + to.node() + "'");
                continue;
            }
            Optional<BlockPort> targetPort = target.definition().input(to.port());
            if (targetPort.isEmpty()) {
                problems.add(label + ": block " + target.definition().id() + " has no input '" + to.port() + "'");
                continue;
            }
            Map<String, Binding> targetBindings = bindings.get(to.node());
            if (targetBindings.containsKey(to.port())) {
                problems.add(label + ": input " + to + " is already bound");
                continue;
            }

            DataType sourceType;
            Binding binding;
            if (FlowDefinition.INPUTS.equals(from.node())) {
                InputDefinition input = inputs.get(from.port());
                if (input == null) {
                    problems.add(label + ": the report has no input '" + from.port() + "'");
                    continue;
                }
                sourceType = input.type();
                binding = new Binding.FromInput(from.port());
            } else {
                ReportBlock source = blocks.get(from.node());
                if (source == null) {
                    if (!nodes.containsKey(from.node())) problems.add(label + ": unknown source node '" + from.node() + "'");
                    continue;
                }
                Optional<BlockPort> sourcePort = source.definition().output(from.port());
                if (sourcePort.isEmpty()) {
                    problems.add(label + ": block " + source.definition().id() + " has no output '" + from.port() + "'");
                    continue;
                }
                if (from.node().equals(to.node())) {
                    problems.add(label + ": a node cannot feed itself");
                    continue;
                }
                sourceType = sourcePort.get().type();
                binding = new Binding.FromNode(from.node(), from.port());
                dependsOn.get(to.node()).add(from.node());
            }
            if (sourceType != targetPort.get().type()) {
                problems.add(label + ": type mismatch, " + sourceType.json() + " into " + targetPort.get().type().json());
                continue;
            }
            targetBindings.put(to.port(), binding);
        }

        // Required inputs
        for (FlowNode node : nodes.values()) {
            ReportBlock block = blocks.get(node.id());
            if (block == null) continue;
            for (BlockPort port : block.definition().inputs()) {
                if (port.required() && !bindings.get(node.id()).containsKey(port.name())) {
                    problems.add("node '" + node.id() + "': required input '" + port.name() + "' is not bound");
                }
            }
        }

        List<String> order = topologicalOrder(nodes.keySet(), dependsOn, problems);

        if (!problems.isEmpty()) throw new FlowValidationException(problems);

        List<ValidatedFlow.Step> steps = new ArrayList<>();
        for (String id : order) {
            steps.add(new ValidatedFlow.Step(nodes.get(id), blocks.get(id), Map.copyOf(bindings.get(id))));
        }
        return new ValidatedFlow(steps);
    }

    /**
     * Kahn's algorithm, always taking the ready node declared first, so the execution order is
     * stable and readable, and a slow node declared last (an image download) runs after the others.
     */
    private static List<String> topologicalOrder(Set<String> ids, Map<String, Set<String>> dependsOn, List<String> problems) {
        Map<String, Integer> pending = new LinkedHashMap<>();
        for (String id : ids) pending.put(id, dependsOn.get(id).size());
        List<String> declared = new ArrayList<>(ids);
        List<String> order = new ArrayList<>();
        PriorityQueue<String> ready = new PriorityQueue<>(Comparator.comparingInt(declared::indexOf));
        pending.forEach((id, count) -> { if (count == 0) ready.add(id); });
        Set<String> done = new HashSet<>();
        while (!ready.isEmpty()) {
            String id = ready.poll();
            order.add(id);
            done.add(id);
            for (String other : ids) {
                if (!done.contains(other) && !ready.contains(other) && dependsOn.get(other).contains(id)) {
                    int left = pending.merge(other, -1, Integer::sum);
                    if (left == 0) ready.add(other);
                }
            }
        }
        if (order.size() < ids.size()) {
            List<String> inCycle = ids.stream().filter(id -> !done.contains(id)).toList();
            problems.add("the flow has a cycle among nodes " + inCycle);
        }
        return order;
    }
}
