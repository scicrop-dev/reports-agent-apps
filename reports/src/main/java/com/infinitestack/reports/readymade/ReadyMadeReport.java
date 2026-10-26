package com.infinitestack.reports.readymade;

import java.util.List;
import java.util.Objects;

import com.infinitestack.reports.engine.flow.FlowDefinition;
import com.infinitestack.reports.engine.flow.FlowNode;
import com.infinitestack.reports.report.InputDefinition;
import com.infinitestack.reports.report.PageSettings;

/**
 * A report shipped with the app, ready to be copied into a user's own report: the same content a
 * report version holds (inputs, Flow, template, page), plus an id, a semver version and catalog
 * metadata. Loaded from {@code classpath:ready-made/<id>.json} by {@link ReadyMadeCatalog}.
 */
public record ReadyMadeReport(
        String id,
        String version,
        String name,
        String description,
        String category,
        List<InputDefinition> inputs,
        FlowDefinition flow,
        String templateHtml,
        String templateCss,
        PageSettings page) {

    public ReadyMadeReport {
        inputs = inputs == null ? List.of() : List.copyOf(inputs);
        flow = flow == null ? FlowDefinition.empty() : flow;
        templateHtml = templateHtml == null ? "" : templateHtml;
        templateCss = templateCss == null ? "" : templateCss;
        page = PageSettings.orDefaults(page);
    }

    /** The distinct Block ids the Flow uses, in node order. */
    public List<String> requiredBlocks() {
        return flow.nodes().stream().map(FlowNode::block).filter(Objects::nonNull).distinct().toList();
    }
}
