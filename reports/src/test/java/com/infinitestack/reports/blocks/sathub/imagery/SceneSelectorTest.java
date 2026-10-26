package com.infinitestack.reports.blocks.sathub.imagery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/** Scene choice with synthetic footprints around a unit-square area at lon 0..1, lat 0..1. */
class SceneSelectorTest {

    static final Map<String, Object> AREA = square(0, 0, 1, 1);

    static Map<String, Object> square(double minX, double minY, double maxX, double maxY) {
        return Map.of("type", "Polygon", "coordinates", List.of(ring(minX, minY, maxX, maxY)));
    }

    static List<List<Double>> ring(double minX, double minY, double maxX, double maxY) {
        return List.of(List.of(minX, minY), List.of(maxX, minY), List.of(maxX, maxY), List.of(minX, maxY),
                List.of(minX, minY));
    }

    static SceneItem scene(String id, String date, Map<String, Object> footprint) {
        return new SceneItem(id, Instant.parse(date + "T15:00:00Z"), 5, 32719, "sentinel-2c", id, footprint,
                Map.of(), null);
    }

    @Test
    void newestFullCoverageWins() {
        List<SceneItem> candidates = List.of(
                scene("half-new", "2026-10-25", square(0.5, -1, 2, 2)),
                scene("full", "2026-10-20", square(-1, -1, 2, 2)),
                scene("full-old", "2026-10-15", square(-1, -1, 2, 2)));
        SceneSelector.Choice choice = SceneSelector.choose(candidates, AREA).orElseThrow();
        assertEquals("full", choice.scene().id());
        assertEquals(100, choice.coveragePercent(), 1e-9);
    }

    @Test
    void sameDateTwoTilesPicksTheOneThatCovers() {
        List<SceneItem> candidates = List.of(
                scene("19MCM", "2026-10-25", square(-1, 0.9, 2, 3)),
                scene("19LCL", "2026-10-25", square(-1, -2, 2, 1.0001)));
        assertEquals("19LCL", SceneSelector.choose(candidates, AREA).orElseThrow().scene().id());
    }

    @Test
    void withoutFullCoverageTakesTheLargestShareNewestOnTies() {
        List<SceneItem> candidates = List.of(
                scene("quarter", "2026-10-25", square(0.75, -1, 2, 2)),
                scene("sixty-a", "2026-10-20", square(0.4, -1, 2, 2)),
                scene("sixty-b", "2026-10-15", square(0.4, -1, 2, 2)));
        SceneSelector.Choice choice = SceneSelector.choose(candidates, AREA).orElseThrow();
        assertEquals("sixty-a", choice.scene().id());
        assertEquals(60, choice.coveragePercent(), 1.5);
    }

    @Test
    void noOverlapOrNoCandidatesIsEmpty() {
        assertEquals(Optional.empty(), SceneSelector.choose(List.of(), AREA));
        assertEquals(Optional.empty(), SceneSelector.choose(
                List.of(scene("far", "2026-10-25", square(5, 5, 6, 6)), scene("none", "2026-10-20", null)), AREA));
    }

    @Test
    void holesInTheAreaAreNotCounted() {
        // Area = square with a hole on its left half; a footprint over the right half covers all of it.
        Map<String, Object> areaWithHole = Map.of("type", "Polygon", "coordinates",
                List.of(ring(0, 0, 1, 1), ring(0.05, 0.05, 0.5, 0.95)));
        Map<String, Object> rightFrame = Map.of("type", "MultiPolygon", "coordinates", List.of(
                List.of(ring(-1, -1, 2, 0.05)), List.of(ring(-1, 0.95, 2, 2)),
                List.of(ring(-1, -1, 0.05, 2)), List.of(ring(0.5, -1, 2, 2))));
        SceneSelector.Choice choice = SceneSelector.choose(
                List.of(scene("frame", "2026-10-25", rightFrame)), areaWithHole).orElseThrow();
        assertTrue(choice.coveragePercent() >= SceneSelector.FULL_COVERAGE_PERCENT, "" + choice.coveragePercent());
    }

    @Test
    void multiPolygonAreaAndThinShapes() {
        Map<String, Object> twoParts = Map.of("type", "MultiPolygon", "coordinates", List.of(
                List.of(ring(0, 0, 1, 1)), List.of(ring(10, 0, 11, 1))));
        assertEquals(50, SceneSelector.coveragePercent(twoParts, square(-1, -1, 2, 2)), 1e-9);
        // A thin diagonal strip leaves few grid points inside its bbox; the grid densifies.
        Map<String, Object> strip = Map.of("type", "Polygon", "coordinates", List.of(List.of(
                List.of(0.0, 0.0), List.of(0.01, 0.0), List.of(1.0, 0.99), List.of(1.0, 1.0), List.of(0.99, 1.0),
                List.of(0.0, 0.01), List.of(0.0, 0.0))));
        assertTrue(SceneSelector.samplePoints(SceneSelector.polygons(strip)).size() >= 100);
        assertEquals(50, SceneSelector.coveragePercent(strip, square(-1, -1, 0.5, 2)), 3);
    }
}
