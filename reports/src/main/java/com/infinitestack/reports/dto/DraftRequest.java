package com.infinitestack.reports.dto;

import java.util.List;

import com.infinitestack.reports.engine.flow.FlowDefinition;
import com.infinitestack.reports.report.InputDefinition;

/** Body of PUT /api/reports/{id}/versions/{n}: the whole content of a draft. */
public record DraftRequest(List<InputDefinition> inputs, FlowDefinition flow, String templateHtml, String templateCss) {
}
