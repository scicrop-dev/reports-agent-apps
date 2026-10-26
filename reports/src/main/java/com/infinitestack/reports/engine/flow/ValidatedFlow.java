package com.infinitestack.reports.engine.flow;

import java.util.List;
import java.util.Map;

import com.infinitestack.reports.engine.block.ReportBlock;

/**
 * A Flow that passed {@link FlowValidator}: nodes in execution (topological) order, each with its
 * Block resolved and every input port bound.
 */
public record ValidatedFlow(List<Step> steps) {

    public record Step(FlowNode node, ReportBlock block, Map<String, Binding> bindings) {}
}
