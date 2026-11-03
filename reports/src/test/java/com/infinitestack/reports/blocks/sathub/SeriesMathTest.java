package com.infinitestack.reports.blocks.sathub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * The pure parts of sathub-index-compare and sathub-index-anomaly: date matching, correlation,
 * rolling median, same-month mean, the anomaly analysis over a series, and the multi-line chart.
 */
class SeriesMathTest {

    static SeriesMath.Value v(String date, double value) {
        return new SeriesMath.Value(LocalDate.parse(date), value);
    }

    @Test
    void matchesEachDateOnceWithinTheGap() {
        List<SeriesMath.Value> a = List.of(v("2026-01-01", 0.5), v("2026-01-06", 0.6), v("2026-01-20", 0.7));
        List<SeriesMath.Value> b = List.of(v("2026-01-02", 0.4), v("2026-01-03", 0.45), v("2026-01-30", 0.9));
        List<SeriesMath.Match> matches = SeriesMath.matchDates(a, b, 3);
        // 01-01 takes 01-02 (nearest); 01-06 takes 01-03 (3 days, the nearest unused); 01-20 has none within 3 days
        assertEquals(2, matches.size());
        assertEquals(LocalDate.parse("2026-01-02"), matches.get(0).b().date());
        assertEquals(LocalDate.parse("2026-01-03"), matches.get(1).b().date());
        assertEquals(0.125, SeriesMath.meanDifference(matches), 1e-9);
        assertTrue(SeriesMath.matchDates(a, b, 0).isEmpty());
        assertNull(SeriesMath.meanDifference(List.of()));
    }

    @Test
    void correlationIsPearson() {
        List<SeriesMath.Match> perfect = new ArrayList<>();
        List<SeriesMath.Match> inverse = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            perfect.add(new SeriesMath.Match(v("2026-01-0" + (i + 1), i), v("2026-01-0" + (i + 1), 2 * i + 1)));
            inverse.add(new SeriesMath.Match(v("2026-01-0" + (i + 1), i), v("2026-01-0" + (i + 1), -i)));
        }
        assertEquals(1.0, SeriesMath.correlation(perfect), 1e-12);
        assertEquals(-1.0, SeriesMath.correlation(inverse), 1e-12);
        // x = 1, 2, 3, 4; y = 2, 1, 4, 3: r = 0.6
        List<SeriesMath.Match> some = List.of(
                new SeriesMath.Match(v("2026-01-01", 1), v("2026-01-01", 2)), new SeriesMath.Match(v("2026-01-02", 2), v("2026-01-02", 1)),
                new SeriesMath.Match(v("2026-01-03", 3), v("2026-01-03", 4)), new SeriesMath.Match(v("2026-01-04", 4), v("2026-01-04", 3)));
        assertEquals(0.6, SeriesMath.correlation(some), 1e-12);
        assertNull(SeriesMath.correlation(perfect.subList(0, 2)), "fewer than 3 matches");
        List<SeriesMath.Match> constant = List.of(new SeriesMath.Match(v("2026-01-01", 1), v("2026-01-01", 5)),
                new SeriesMath.Match(v("2026-01-02", 2), v("2026-01-02", 5)), new SeriesMath.Match(v("2026-01-03", 3), v("2026-01-03", 5)));
        assertNull(SeriesMath.correlation(constant), "one side constant");
    }

    @Test
    void rollingMedianUsesOnlyEarlierValues() {
        List<Double> values = List.of(0.8, 0.7, 0.9, 0.85, 0.3, 0.75);
        List<Double> baseline = SeriesMath.rollingMedian(values, 3, 2);
        assertEquals(Arrays.asList(null, null, 0.75, 0.8, 0.85, 0.85), baseline);
        assertEquals(Arrays.asList(null, 0.8, 0.7, 0.9, 0.85, 0.3), SeriesMath.rollingMedian(values, 1, 1));
        assertEquals(0.75, SeriesMath.median(List.of(0.9, 0.6, 0.8, 0.7)), 1e-12);
    }

    @Test
    void sameMonthOfEarlierYearsNeedsTwoYearsOfHistory() {
        List<SeriesMath.Value> history = List.of(v("2024-03-05", 0.6), v("2024-03-25", 0.8), v("2024-09-10", 0.3),
                v("2025-03-15", 0.5), v("2026-03-10", 0.4));
        SeriesMath.SeasonalMean mean = SeriesMath.sameMonthEarlierYears(history, LocalDate.parse("2026-03-10"), 2);
        assertEquals(3, mean.month());
        assertEquals(Map.of(2024, 0.7, 2025, 0.5).keySet(), mean.byYear().keySet());
        assertEquals(0.7, mean.byYear().get(2024), 1e-9);
        assertEquals((0.6 + 0.8 + 0.5) / 3, mean.mean(), 1e-9);
        assertEquals(3, mean.observations());
        // Less than 2 years from the first observation to the latest
        assertNull(SeriesMath.sameMonthEarlierYears(history.subList(3, 5), LocalDate.parse("2026-03-10"), 2));
        // Two years of history, but the month never appeared before
        assertNull(SeriesMath.sameMonthEarlierYears(List.of(v("2024-01-01", 0.5), v("2026-03-10", 0.4)), LocalDate.parse("2026-03-10"), 2));
    }

    static SatelliteHubSeriesData.Point point(String date, double mean) {
        return new SatelliteHubSeriesData.Point(LocalDate.parse(date), mean, mean, mean, mean, mean, 100.0, 1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void anomalyFlagsDropsAgainstTheRollingMedian() {
        List<SatelliteHubSeriesData.Point> history = List.of(point("2026-01-01", 0.80), point("2026-01-11", 0.82),
                point("2026-01-21", 0.78), point("2026-01-31", 0.81), point("2026-02-10", 0.55), point("2026-02-20", 0.79),
                point("2026-03-02", 0.60));
        SatelliteHubSeriesData.Window all = new SatelliteHubSeriesData.Window(null, 1L, null, null, 365, 50);
        Map<String, Object> out = SatelliteHubIndexAnomalyBlock.analyse(history, all, 0.15, 5);
        List<Map<String, Object>> points = (List<Map<String, Object>>) out.get("points");
        assertEquals(7, points.size());
        assertNull(points.get(2).get("baseline"), "fewer than 3 earlier images");
        assertEquals(0.80, (Double) points.get(3).get("baseline"), 1e-9);
        assertEquals(0.805, (Double) points.get(4).get("baseline"), 1e-9);
        assertEquals(true, points.get(4).get("isDrop"));
        assertEquals(false, points.get(5).get("isDrop"));
        // the last image: median of 0.82, 0.78, 0.81, 0.55, 0.79 = 0.79; 0.60 - 0.79 = -0.19
        assertEquals(-0.19, (Double) points.get(6).get("delta"), 1e-9);
        assertEquals("drop", out.get("latestStatus"));
        assertEquals(2, out.get("eventCount"));
        assertEquals("2026-03-02", out.get("lastDropDate"));
        assertEquals(false, out.get("hasSeasonalBaseline"));

        // A window shows only its images, but the baseline still sees the earlier ones
        SatelliteHubSeriesData.Window feb = new SatelliteHubSeriesData.Window(null, 1L, LocalDate.parse("2026-02-01"),
                LocalDate.parse("2026-02-28"), 365, 50);
        Map<String, Object> window = SatelliteHubIndexAnomalyBlock.analyse(history, feb, 0.15, 5);
        assertEquals(2, window.get("count"));
        assertEquals("normal", window.get("latestStatus"));
        assertEquals(true, window.get("hasDrops"));

        Map<String, Object> empty = SatelliteHubIndexAnomalyBlock.analyse(List.of(), all, 0.15, 5);
        assertEquals("no-data", empty.get("latestStatus"));
        assertFalse((Boolean) empty.get("hasDrops"));
        assertNull(empty.get("lastDropDate"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void anomalyComparesWithTheSameMonthOfEarlierYears() {
        List<SatelliteHubSeriesData.Point> history = new ArrayList<>();
        for (int year = 2024; year <= 2026; year++) {
            for (int month = 1; month <= (year == 2026 ? 5 : 12); month++) {
                history.add(point(String.format("%d-%02d-10", year, month), month == 5 ? 0.7 : 0.5));
            }
        }
        history.set(history.size() - 1, point("2026-05-10", 0.4));
        Map<String, Object> out = SatelliteHubIndexAnomalyBlock.analyse(history,
                new SatelliteHubSeriesData.Window(null, 1L, null, null, 120, 50), 0.15, 5);
        assertEquals(true, out.get("hasSeasonalBaseline"));
        Map<String, Object> seasonal = (Map<String, Object>) out.get("seasonalBaseline");
        assertEquals(5, seasonal.get("month"));
        assertEquals(0.7, (Double) seasonal.get("mean"), 1e-9);
        assertEquals(-0.3, (Double) seasonal.get("anomaly"), 1e-9);
        assertEquals(2, ((List<?>) seasonal.get("years")).size());
        // 120 days back from 2026-05-10: from 2026-01-11 on
        assertEquals(4, out.get("count"));
    }

    @Test
    void multiLineChartRendersWithAndWithoutARightAxis() {
        IndexSeriesChart chart = new IndexSeriesChart(false);
        List<IndexSeriesChart.Point> ndvi = new ArrayList<>();
        List<IndexSeriesChart.Point> rvi = new ArrayList<>();
        LocalDate day = LocalDate.parse("2026-01-01");
        for (int i = 0; i < 20; i++, day = day.plusDays(12)) {
            double n = 0.4 + 0.3 * Math.sin(i / 4.0);
            ndvi.add(new IndexSeriesChart.Point(day, n, n - 0.1, n + 0.1));
            rvi.add(new IndexSeriesChart.Point(day.plusDays(2), 2 * n + 0.5, 2 * n + 0.5, 2 * n + 0.5));
        }
        byte[] both = chart.renderLines(List.of(
                new IndexSeriesChart.Line("NDVI", IndexSeriesChart.PALETTE.get(0), ndvi, false, true, false),
                new IndexSeriesChart.Line("RVI", IndexSeriesChart.PALETTE.get(1), rvi, false, false, true)),
                List.of(new IndexSeriesChart.Marker(ndvi.get(3).date(), ndvi.get(3).mean(), Color.RED)), 900, 320);
        assertPng(both);
        // Only right-axis lines: drawn on the left axis
        assertPng(chart.renderLines(List.of(new IndexSeriesChart.Line("RVI", Color.BLUE, rvi, true, false, true)), List.of(), 900, 320));
        assertPng(chart.renderLines(List.of(new IndexSeriesChart.Line("none", Color.BLUE, List.of(), false, false, false)), List.of(), 900, 320));
        assertPng(new IndexSeriesChart().renderLines(List.of(
                new IndexSeriesChart.Line("NDVI", Color.GREEN, ndvi.subList(0, 1), false, false, false)), null, 400, 200));
    }

    private static void assertPng(byte[] png) {
        assertTrue(png.length > 100);
        assertEquals((byte) 0x89, png[0]);
        assertEquals((byte) 'P', png[1]);
    }
}
