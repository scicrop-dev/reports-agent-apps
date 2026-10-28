package com.infinitestack.reports.blocks.brweather;

import java.nio.file.Path;

import com.infinitestack.reports.blocks.sathub.imagery.HttpCogReader;
import com.infinitestack.reports.blocks.sathub.imagery.LocalFileRangeFetcher;
import com.infinitestack.reports.engine.block.BlockQueries;

/** Builds the rainfall Block with a test lake directory, for tests outside this package. */
public final class BrWeatherRainfallBlockAccess {

    private BrWeatherRainfallBlockAccess() {}

    public static BrWeatherRainfallBlock create(BlockQueries queries, Path lake) {
        return new BrWeatherRainfallBlock(queries, new HttpCogReader(new LocalFileRangeFetcher()), lake);
    }
}
