package com.infinitestack.reports.dto;

import com.infinitestack.reports.report.ReportVersion;

/** A version together with its current validation. */
public record VersionView(ReportVersion version, ValidationReport validation) {
}
