package com.infinitestack.reports.dto;

import java.util.Map;

/**
 * Body of POST /api/reports/{id}/execute and /preview.
 *
 * @param version null to run the published version
 */
public record ExecuteRequest(Integer version, Map<String, Object> inputs) {
}
