package com.infinitestack.reports.report;

import java.time.Instant;

/**
 * A report definition (MASTER section 3.4). Its content lives in {@link ReportVersion}s.
 *
 * @param readyMadeId      the ready-made report it was created from; null for a report built from scratch
 * @param readyMadeVersion the version of that ready-made at the time of the copy
 */
public record Report(
        long id,
        String name,
        String description,
        String ownerId,
        String ownerName,
        Integer publishedVersion,
        Instant createdAt,
        Instant updatedAt,
        String readyMadeId,
        String readyMadeVersion) {
}
