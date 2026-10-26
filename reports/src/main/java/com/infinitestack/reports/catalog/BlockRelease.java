package com.infinitestack.reports.catalog;

import java.time.Instant;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * An admin's decision that a Block may be used. {@code availability} is the catalog diagnosis at
 * that moment, kept as evidence of what the data looked like when the Block was released.
 */
public record BlockRelease(String blockId, String blockVersion, String releasedBy, String releasedByName,
                           Instant releasedAt, JsonNode availability) {
}
