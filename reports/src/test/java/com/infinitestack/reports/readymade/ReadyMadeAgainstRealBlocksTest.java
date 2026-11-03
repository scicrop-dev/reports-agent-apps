package com.infinitestack.reports.readymade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.infinitestack.reports.blocks.car.CarEnvironmentalLayersBlock;
import com.infinitestack.reports.blocks.car.CarHistoryBlock;
import com.infinitestack.reports.blocks.car.CarOverlapsBlock;
import com.infinitestack.reports.blocks.car.CarPropertyBlock;
import com.infinitestack.reports.blocks.car.CarPropertyGeometryBlock;
import com.infinitestack.reports.blocks.car.CarPropertyMapBlock;
import com.infinitestack.reports.blocks.car.ReportAreaBlock;
import com.infinitestack.reports.config.ReportsLimits;
import com.infinitestack.reports.engine.block.BlockDefinition;
import com.infinitestack.reports.engine.block.BlockPort;
import com.infinitestack.reports.engine.block.BlockRegistry;
import com.infinitestack.reports.engine.block.ReportBlock;
import com.infinitestack.reports.engine.flow.FlowValidator;
import com.infinitestack.reports.support.CarBlockContractStubs;
import com.infinitestack.reports.support.GeoBlockContractStubs;
import com.infinitestack.reports.blocks.brgeo.BrGeoBiomesBlock;
import com.infinitestack.reports.blocks.brgeo.BrGeoCropProductionBlock;
import com.infinitestack.reports.blocks.brgeo.BrGeoCropProgressBlock;
import com.infinitestack.reports.blocks.brgeo.BrGeoZarcRiskBlock;
import com.infinitestack.reports.blocks.brweather.BrWeatherDailyBlock;
import com.infinitestack.reports.blocks.brweather.BrWeatherRainfallBlockAccess;
import com.infinitestack.reports.blocks.brgeo.BrGeoDeterAlertsBlock;
import com.infinitestack.reports.blocks.brgeo.BrGeoEmbargoesBlock;
import com.infinitestack.reports.blocks.brgeo.BrGeoMunicipalitiesBlock;
import com.infinitestack.reports.blocks.brgeo.BrGeoProdesBlock;
import com.infinitestack.reports.blocks.sathub.SatelliteHubIndexSeriesBlock;
import com.infinitestack.reports.blocks.isgeosam.IsGeoSamFieldCandidatesBlock;
import com.infinitestack.reports.blocks.isgeosam.IsGeoSamSettings;
import com.infinitestack.reports.blocks.sathub.SatelliteHubLatestImageBlock;
import com.infinitestack.reports.template.HtmlSanitizer;
import com.infinitestack.reports.template.TemplateRenderer;
import com.infinitestack.reports.template.TemplateScope;

/**
 * Ties the ready-made reports to the real Blocks. {@link ReadyMadeReportsValidityTest} renders them
 * with {@link CarBlockContractStubs}; this test makes sure those stubs still have exactly the ports
 * of the real Blocks, and that every ready-made validates against the real definitions. A Block
 * whose contract changes without the ready-made being updated fails here, not in a customer's report.
 * Only definitions are used: no database.
 */
class ReadyMadeAgainstRealBlocksTest {

    private static List<ReportBlock> realBlocks() {
        return List.of(new CarPropertyBlock(null), new CarPropertyGeometryBlock(null), new CarEnvironmentalLayersBlock(null),
                new CarOverlapsBlock(null), new CarHistoryBlock(null), new CarPropertyMapBlock(null), new ReportAreaBlock(null),
                new BrGeoMunicipalitiesBlock(null), new BrGeoBiomesBlock(null), new BrGeoEmbargoesBlock(null),
                new BrGeoDeterAlertsBlock(null), new BrGeoProdesBlock(null), new BrGeoCropProductionBlock(null),
                new SatelliteHubIndexSeriesBlock(null), new SatelliteHubLatestImageBlock(null, null, null),
                new BrGeoZarcRiskBlock(null), new BrGeoCropProgressBlock(null), new BrWeatherDailyBlock(null),
                BrWeatherRainfallBlockAccess.create(null, Path.of("/nonexistent")),
                new IsGeoSamFieldCandidatesBlock(null, null, new IsGeoSamSettings("reports", "is-geo-sam", "", "/nonexistent", "/nonexistent")));
    }

    @Test
    void stubsHaveExactlyThePortsOfTheRealBlocks() {
        Map<String, BlockDefinition> real = realBlocks().stream().map(ReportBlock::definition)
                .collect(Collectors.toMap(BlockDefinition::id, Function.identity()));
        List<ReportBlock> stubs = new java.util.ArrayList<>(CarBlockContractStubs.all(false));
        stubs.addAll(GeoBlockContractStubs.all(false));
        for (ReportBlock stub : stubs) {
            BlockDefinition s = stub.definition();
            BlockDefinition r = real.get(s.id());
            assertTrue(r != null, "stub " + s.id() + " has no real block");
            assertEquals(shape(r.inputs()), shape(s.inputs()), s.id() + " inputs");
            assertEquals(shape(r.outputs()), shape(s.outputs()), s.id() + " outputs");
        }
    }

    /** What a Flow and a template depend on: name, type and whether it is required (not the description). */
    private static List<String> shape(List<BlockPort> ports) {
        return ports.stream().map(p -> p.name() + ":" + p.type() + (p.required() ? "!" : "?")).toList();
    }

    @Test
    void everyReadyMadeValidatesAgainstTheRealBlocks() throws Exception {
        BlockRegistry registry = new BlockRegistry(realBlocks());
        ReportsLimits limits = ReportsLimits.defaults();
        TemplateRenderer templates = new TemplateRenderer(new HtmlSanitizer(), limits);
        for (ReadyMadeReport report : new ReadyMadeCatalog().all()) {
            new FlowValidator(registry, limits).validate(report.flow(), report.inputs());
            assertEquals(List.of(), templates.validate(report.templateHtml(), report.templateCss(),
                    TemplateScope.of(report.inputs(), report.flow(), registry)), report.id());
        }
    }
}
