package com.infinitestack.reports.dto;

import com.infinitestack.reports.execution.Execution;

/**
 * A run that is not stored. {@code html} is the sanitized report document, null when the run failed.
 */
public record PreviewResult(Execution execution, String html) {
}
