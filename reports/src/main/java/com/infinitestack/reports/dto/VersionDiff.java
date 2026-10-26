package com.infinitestack.reports.dto;

import java.util.List;

import com.infinitestack.reports.diff.LineDiff;

/** What changed between two versions of a report, part by part. */
public record VersionDiff(long reportId, int from, int to, List<Part> parts) {

    /** One compared part: inputs, flow, page, template HTML or template CSS. */
    public record Part(String name, boolean changed, List<LineDiff.Line> lines) {}
}
