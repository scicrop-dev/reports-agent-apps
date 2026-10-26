package com.infinitestack.reports.report;

import java.time.Instant;

/** A report definition (MASTER section 3.4). Its content lives in {@link ReportVersion}s. */
public record Report(
        long id,
        String name,
        String description,
        String ownerId,
        String ownerName,
        Integer publishedVersion,
        Instant createdAt,
        Instant updatedAt) {
}
