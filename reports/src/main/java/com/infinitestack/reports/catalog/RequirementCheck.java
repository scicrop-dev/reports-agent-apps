package com.infinitestack.reports.catalog;

import java.util.List;

import com.infinitestack.reports.engine.block.DataRequirement;

/** The diagnosis of one {@link DataRequirement}: what was found and, if anything is wrong, what. */
public record RequirementCheck(DataRequirement requirement, AvailabilityStatus status, String sourceId,
                               String lastUpdate, List<String> problems) {

    public RequirementCheck {
        problems = problems == null ? List.of() : List.copyOf(problems);
    }
}
