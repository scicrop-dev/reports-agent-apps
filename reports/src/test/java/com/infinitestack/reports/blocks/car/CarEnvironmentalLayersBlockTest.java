package com.infinitestack.reports.blocks.car;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.infinitestack.reports.blocks.car.CarEnvironmentalLayersBlock.ThemeRow;

/** The layer area rule and the summary, without a database. */
class CarEnvironmentalLayersBlockTest {

    private static ThemeRow row(String layer, int order, String theme, boolean aggregate, String area) {
        return new ThemeRow(layer, layer + " name", order, theme, theme + " name", aggregate, 1,
                area == null ? null : new BigDecimal(area));
    }

    @Test
    void aggregateThemeWinsOverItsParts() {
        // Real shape (AC-1200302-0C2A...): APP_TOTAL equals the river APP; the other themes overlap it.
        List<ThemeRow> apps = List.of(
                row("APPS", 1, "APP_AREA_VN", false, "0.4233"),
                row("APPS", 1, "APP_ESCADINHA", false, "0.1592"),
                row("APPS", 1, "APP_RIO_ATE_10", false, "1.2021"),
                row("APPS", 1, "APP_TOTAL", true, "1.2021"));
        assertEquals(1.2021, CarEnvironmentalLayersBlock.layerArea(apps), 1e-9);
    }

    @Test
    void withoutAggregateTheLayerIsTheSumOfItsThemesAndNullAreasCountAsZero() {
        List<ThemeRow> rl = List.of(
                row("RESERVA_LEGAL", 8, "ARL_AVERBADA", false, "10.5"),
                row("RESERVA_LEGAL", 8, "ARL_PROPOSTA", false, "2.25"),
                row("RESERVA_LEGAL", 8, "ARL_APROVADA_NAO_AVERBADA", false, null));
        assertEquals(12.75, CarEnvironmentalLayersBlock.layerArea(rl), 1e-9);
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildsLayersInLayerOrderAndASummaryWithEveryKey() {
        List<ThemeRow> rows = List.of(
                row("RESERVA_LEGAL", 8, "ARL_PROPOSTA", false, "46.5079"),
                row("VEGETACAO_NATIVA", 2, "VEGETACAO_NATIVA", false, "51.3702"),
                row("APPS", 1, "APP_TOTAL", true, "1.2021"),
                row("APPS", 1, "APP_RIO_ATE_10", false, "1.2021"),
                row("HIDROGRAFIA", 5, "RIO_ATE_10", false, null));
        Map<String, Object> out = CarEnvironmentalLayersBlock.result(rows, 100.0);

        List<Map<String, Object>> layers = (List<Map<String, Object>>) out.get("layers");
        assertEquals(List.of("APPS", "VEGETACAO_NATIVA", "HIDROGRAFIA", "RESERVA_LEGAL"),
                layers.stream().map(l -> l.get("layer")).toList());
        Map<String, Object> app = layers.get(0);
        assertEquals(1.2021, app.get("declaredAreaHa"));
        assertEquals(1.2, app.get("percentOfProperty"));
        assertEquals(2, ((List<?>) app.get("themes")).size());
        assertEquals(0.0, layers.get(2).get("declaredAreaHa"));

        Map<String, Object> summary = (Map<String, Object>) out.get("summary");
        assertEquals(List.of("propertyAreaHa", "appHa", "legalReserveHa", "nativeVegetationHa", "consolidatedHa",
                "fallowHa", "hydrographyHa", "restrictedUseHa", "administrativeEasementHa", "appPercent",
                "legalReservePercent", "nativeVegetationPercent"), List.copyOf(summary.keySet()));
        assertEquals(100.0, summary.get("propertyAreaHa"));
        assertEquals(46.5079, summary.get("legalReserveHa"));
        assertEquals(0.0, summary.get("consolidatedHa"));
        assertEquals(46.51, summary.get("legalReservePercent"));
        assertEquals(51.37, summary.get("nativeVegetationPercent"));
        assertTrue((Boolean) out.get("hasLegalReserve"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void noFeaturesGivesZerosAndNoLegalReserve() {
        Map<String, Object> out = CarEnvironmentalLayersBlock.result(List.of(), null);
        assertTrue(((List<?>) out.get("layers")).isEmpty());
        Map<String, Object> summary = (Map<String, Object>) out.get("summary");
        assertEquals(0.0, summary.get("propertyAreaHa"));
        assertEquals(0.0, summary.get("appPercent"));
        assertFalse((Boolean) out.get("hasLegalReserve"));
    }
}
