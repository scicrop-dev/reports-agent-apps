package com.infinitestack.reports.dto;

import java.util.List;

/**
 * Everything that would stop a version from running, grouped by where it comes from. A draft can be
 * saved with problems; it can only be published without them.
 */
public record ValidationReport(List<String> inputs, List<String> flow, List<String> template, List<String> blocks) {

    public boolean valid() {
        return inputs.isEmpty() && flow.isEmpty() && template.isEmpty() && blocks.isEmpty();
    }
}
