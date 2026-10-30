package com.infinitestack.reports.blocks.car;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** The rules of the CAR Blocks that do not need a database (the SQL is in CarBlocksSyntheticDbTest). */
class CarBlocksLogicTest {

    @Test
    void areaMismatchAboveFivePercentEitherWay() {
        assertEquals(10.0, CarPropertyBlock.areaDifferencePct(110.0, 100.0));
        assertEquals(-4.0, CarPropertyBlock.areaDifferencePct(96.0, 100.0));
        assertTrue(CarPropertyBlock.areaMismatch(10.0));
        assertTrue(CarPropertyBlock.areaMismatch(-5.01));
        assertFalse(CarPropertyBlock.areaMismatch(5.0));
        assertFalse(CarPropertyBlock.areaMismatch(null));
        assertNull(CarPropertyBlock.areaDifferencePct(null, 100.0));
        assertNull(CarPropertyBlock.areaDifferencePct(100.0, 0.0));
    }

    @Test
    void historyAreaChange() {
        assertEquals(-11.15, CarHistoryBlock.areaChangeHa(1260.12, 1248.97));
        assertEquals(-0.88, CarHistoryBlock.areaChangePct(1260.12, 1248.97));
        assertNull(CarHistoryBlock.areaChangeHa(null, 10.0));
        assertNull(CarHistoryBlock.areaChangePct(0.0, 10.0));
    }

    @Test
    void dataReferenceStateFromUfOrCode() {
        assertEquals("MT", CarDataReferenceBlock.uf(Map.of("uf", " mt ")));
        assertEquals("BA", CarDataReferenceBlock.uf(Map.of("codImovel", "BA-2915601-735B9BEE97F6498387B672DAA9506DF1")));
        assertEquals("BA", CarDataReferenceBlock.uf(Map.of("uf", "BA", "codImovel", "BA-2915601-735B9BEE97F6498387B672DAA9506DF1")));
        // The code wins over the state: a polygon report wires both (main state of the area + CAR code if any).
        assertEquals("BA", CarDataReferenceBlock.uf(Map.of("uf", "MT", "codImovel", "BA-2915601-735B9BEE97F6498387B672DAA9506DF1")));
        assertThrows(IllegalArgumentException.class, () -> CarDataReferenceBlock.uf(Map.of()));
        assertThrows(IllegalArgumentException.class, () -> CarDataReferenceBlock.uf(Map.of("uf", "M1")));
    }

    @Test
    void dataReferenceSummary() {
        Map<String, Object> out = CarDataReferenceBlock.result("MT", List.of(
                Map.of("layer", "AREA_IMOVEL", "publishedAt", "2026-03-01"),
                Map.of("layer", "APPS", "publishedAt", "2026-01-10"),
                Map.of("layer", "HIDROGRAFIA")));
        assertEquals("2026-03-01", out.get("latestPublishedAt"));
        assertEquals("2026-01-10", out.get("oldestPublishedAt"));
        assertEquals(true, out.get("hasData"));
        assertEquals(false, out.get("complete"));
    }

    @Test
    void inAreaLimitIsClamped() {
        assertEquals(50, CarInAreaBlock.limit(null));
        assertEquals(1, CarInAreaBlock.limit(0));
        assertEquals(500, CarInAreaBlock.limit(10_000));
    }
}
