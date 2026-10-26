package com.infinitestack.reports.dto;

/** Body of POST/PUT /api/reports. */
public record ReportRequest(String name, String description) {
}
