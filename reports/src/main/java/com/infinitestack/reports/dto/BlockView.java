package com.infinitestack.reports.dto;

import com.infinitestack.reports.catalog.BlockAvailability;
import com.infinitestack.reports.catalog.BlockRelease;
import com.infinitestack.reports.engine.block.BlockDefinition;

/**
 * A Block as the API shows it: its contract, whether the catalog has its data, whether it was
 * released, and the conclusion — {@code usable} — that the UI and the engine act on.
 */
public record BlockView(BlockDefinition definition, BlockAvailability availability, BlockRelease release, boolean usable) {
}
