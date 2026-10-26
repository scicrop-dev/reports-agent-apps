package com.infinitestack.reports.catalog;

import java.util.List;

/** Whether a Block's data is in the catalog, requirement by requirement. */
public record BlockAvailability(String blockId, AvailabilityStatus status, String catalogOrigin,
                                String catalogError, List<RequirementCheck> checks) {

    public List<String> problems() {
        List<String> all = new java.util.ArrayList<>();
        if (catalogError != null && status != AvailabilityStatus.AVAILABLE) all.add(catalogError);
        checks.forEach(c -> all.addAll(c.problems()));
        return all;
    }
}
