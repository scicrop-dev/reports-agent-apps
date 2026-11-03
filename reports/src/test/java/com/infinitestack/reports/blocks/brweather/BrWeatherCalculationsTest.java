package com.infinitestack.reports.blocks.brweather;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

/** The calculations of the BR Weather Blocks, without database. */
class BrWeatherCalculationsTest {

    // ---------------------------------------------------------------- ET0

    /**
     * FAO-56, example 18 (Brussels, 6 July, 50 48' N, 100 m): Tmax 21.5 C, Tmin 12.3 C, ea 1.409 kPa
     * (from RHmax 84% and RHmin 63%), u2 2.078 m/s, Rs 22.07 MJ/m2/day. ET0 = 3.9 mm/day; the
     * intermediate values of the example on the way.
     */
    @Test
    void penmanMonteithMatchesFao56Example18() {
        assertEquals(1.997, (Et0.saturationVapourPressure(21.5) + Et0.saturationVapourPressure(12.3)) / 2, 0.001);
        assertEquals(41.09, Et0.extraterrestrialRadiation(50.8, 187), 0.05);
        double et0 = Et0.penmanMonteith(21.5, 12.3, 1.409, 2.078, 22.07, 100, 50.8, 187);
        assertEquals(3.9, et0, 0.05);
        // with the mean humidity (what POWER has): ea = 0.735 x 1.997 = 1.468, a little less ET0
        double rhMean = Et0.penmanMonteithRhMean(21.5, 12.3, 73.5, 2.078, 22.07, 100, 50.8, 187);
        assertTrue(rhMean < et0 && rhMean > 3.7, String.valueOf(rhMean));
    }

    /** FAO-56, example 8: Ra on 3 September at 20 S = 32.2 MJ/m2/day. */
    @Test
    void extraterrestrialRadiationMatchesFao56Example8() {
        assertEquals(32.2, Et0.extraterrestrialRadiation(-20, 246), 0.05);
    }

    @Test
    void hargreavesIsInTheRangeOfPenmanMonteith() {
        double hs = Et0.hargreaves(21.5, 12.3, 50.8, 187);
        assertTrue(hs > 3 && hs < 5, String.valueOf(hs));
        assertEquals(0.0, Et0.hargreaves(10, 12, -15, 100), "no negative range");
    }

    // ---------------------------------------------------------------- water balance

    @Test
    void waterBalanceDriesExponentiallyAndKeepsTheMass() {
        LocalDate day = LocalDate.parse("2026-09-01");
        List<BrWeatherWaterBalanceBlock.Input> series = new ArrayList<>();
        for (int i = 0; i < 10; i++) series.add(new BrWeatherWaterBalanceBlock.Input(day.plusDays(i), 0.0, 5.0));
        series.add(new BrWeatherWaterBalanceBlock.Input(day.plusDays(10), 100.0, 5.0));
        series.add(new BrWeatherWaterBalanceBlock.Input(day.plusDays(11), null, 4.0));

        List<BrWeatherWaterBalanceBlock.Day> out = BrWeatherWaterBalanceBlock.balance(series, 50, 1.0);
        // day 1: loss -5, storage 50 e^-0.1 = 45.24, ETa = 4.76, deficit 0.24
        assertEquals(45.24, out.get(0).storageMm(), 0.01);
        assertEquals(4.76, out.get(0).etaMm(), 0.01);
        assertEquals(0.24, out.get(0).deficitMm(), 0.01);
        // day 10: loss -50, storage 50 e^-1 = 18.39
        assertEquals(18.39, out.get(9).storageMm(), 0.01);
        // day 11: 95 mm over the soil, back to full, the rest is excess
        assertEquals(50.0, out.get(10).storageMm(), 1e-9);
        assertEquals(95 - (50 - 18.39), out.get(10).excessMm(), 0.01);
        assertEquals(5.0, out.get(10).etaMm(), 1e-9);
        // day 12: no rain value, counted as no rain
        assertTrue(out.get(11).storageMm() < 50);

        double rain = 100;
        double eta = out.stream().mapToDouble(BrWeatherWaterBalanceBlock.Day::etaMm).sum();
        double excess = out.stream().mapToDouble(BrWeatherWaterBalanceBlock.Day::excessMm).sum();
        assertEquals(out.get(11).storageMm() - 50, rain - eta - excess, 1e-9, "P - ETa - excess = change of storage");

        List<Boolean> estimated = new ArrayList<>();
        for (int i = 0; i < series.size(); i++) estimated.add(i == 11);
        Map<String, Object> summary = BrWeatherWaterBalanceBlock.summary(series, estimated, out, 50);
        assertEquals(1, summary.get("missingRainDays"));
        assertEquals(1, summary.get("estimatedEt0Days"));
        assertEquals(100.0, summary.get("totalRainMm"));
        // deficit of 1 mm or more: from day 3 (storage 40.9 -> 37.0 gives 3.9 of 5) to day 10
        int expected = (int) out.subList(0, 10).stream().filter(d -> d.deficitMm() >= 1).count();
        assertEquals(expected, summary.get("deficitDays"));
        assertEquals(expected, summary.get("maxConsecutiveDeficitDays"));
        assertEquals(100.0 * out.get(11).storageMm() / 50, (Double) summary.get("finalStoragePercent"), 0.05);
    }

    @Test
    void waterBalanceWithKcScalesTheDemand() {
        List<BrWeatherWaterBalanceBlock.Input> series = List.of(new BrWeatherWaterBalanceBlock.Input(LocalDate.parse("2026-01-01"), 10.0, 4.0));
        BrWeatherWaterBalanceBlock.Day day = BrWeatherWaterBalanceBlock.balance(series, 50, 1.2).get(0);
        assertEquals(4.8, day.etcMm(), 1e-9);
        assertEquals(5.2, day.excessMm(), 1e-9, "the soil was full");
    }

    // ---------------------------------------------------------------- rainfall extremes

    @Test
    void rainfallExtremesCountSpellsAndFiveDayWindows() {
        List<Map<String, Object>> series = new ArrayList<>();
        LocalDate day = LocalDate.parse("2026-01-01");
        double[] rain = new double[40];
        rain[0] = 55;     // heavy
        rain[1] = 20;
        rain[2] = 10;
        // days 3 to 14: 12 dry days (one spell of 10+, not 15+)
        rain[15] = 5;
        // days 16 to 32: 17 dry days, with day 25 missing (breaks it into 9 and 7)
        rain[33] = 60;    // heavy
        rain[34] = 1;
        for (int i = 0; i < rain.length; i++) {
            if (i == 25) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", day.plusDays(i).toString());
            m.put("rainMm", rain[i]);
            m.put("preliminary", false);
            series.add(m);
        }
        Map<String, Object> out = BrWeatherRainfallBlock.summary(series);
        assertEquals(2, out.get("heavyRainDays"));
        assertEquals(60.0, out.get("rx1dayMm"));
        assertEquals(85.0, out.get("rx5dayMm"));
        assertEquals(1, out.get("drySpells10"));
        assertEquals(0, out.get("drySpells15"));
        assertEquals(12, out.get("longestDrySpell"));

        // a spell running to the end of the period counts; under 5 days there is no Rx5day
        List<Map<String, Object>> dry = new ArrayList<>();
        for (int i = 0; i < 16; i++) dry.add(Map.of("date", day.plusDays(i).toString(), "rainMm", 0.2, "preliminary", false));
        Map<String, Object> allDry = BrWeatherRainfallBlock.summary(dry);
        assertEquals(1, allDry.get("drySpells10"));
        assertEquals(1, allDry.get("drySpells15"));
        assertNull(BrWeatherRainfallBlock.summary(dry.subList(0, 4)).get("rx5dayMm"));
        assertEquals(0, BrWeatherRainfallBlock.summary(List.of()).get("heavyRainDays"));
    }

    // ---------------------------------------------------------------- temperature anomaly

    @Test
    void anomalyWindowsCoverTheClosedYears() {
        List<BrWeatherTemperatureAnomalyBlock.Window> windows = BrWeatherTemperatureAnomalyBlock.windows(
                LocalDate.parse("2026-09-21"), LocalDate.parse("2026-10-20"), LocalDate.parse("2026-10-20"));
        assertEquals(26, windows.size());
        assertEquals(new BrWeatherTemperatureAnomalyBlock.Window(2000, LocalDate.parse("2000-09-21"), LocalDate.parse("2000-10-20")), windows.get(0));
        assertEquals(2025, windows.get(25).year());

        // across the turn of the year: the window of 2025 would end in 2026, not closed yet
        List<BrWeatherTemperatureAnomalyBlock.Window> summer = BrWeatherTemperatureAnomalyBlock.windows(
                LocalDate.parse("2025-12-15"), LocalDate.parse("2026-01-10"), LocalDate.parse("2026-10-20"));
        assertEquals(25, summer.size());
        assertEquals(LocalDate.parse("2025-01-10"), summer.get(24).to());

        // a period in a closed year is in its own climatology; 29 February shifts to the 28th
        List<BrWeatherTemperatureAnomalyBlock.Window> leap = BrWeatherTemperatureAnomalyBlock.windows(
                LocalDate.parse("2024-02-29"), LocalDate.parse("2024-03-05"), LocalDate.parse("2025-12-31"));
        assertEquals(26, leap.size());
        assertEquals(LocalDate.parse("2001-02-28"), leap.get(1).from());
    }

    @Test
    void percentileInterpolatesLikePercentileCont() {
        assertEquals(9.1, BrWeatherTemperatureAnomalyBlock.percentile(List.of(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0), 90), 1e-9);
        assertEquals(5.0, BrWeatherTemperatureAnomalyBlock.percentile(List.of(5.0), 10), 1e-9);
        assertNull(BrWeatherTemperatureAnomalyBlock.percentile(List.of(), 10));
    }

    // ---------------------------------------------------------------- chart

    @Test
    void barChartDrawsLinesOverTheBars() throws Exception {
        List<DailyBarChart.Bar> bars = new ArrayList<>();
        List<DailyBarChart.Bar> storage = new ArrayList<>();
        LocalDate day = LocalDate.parse("2026-09-01");
        for (int i = 0; i < 30; i++) {
            bars.add(new DailyBarChart.Bar(day.plusDays(i), i % 5 == 0 ? 20.0 : null));
            storage.add(new DailyBarChart.Bar(day.plusDays(i), 50.0 - i));
        }
        byte[] png = new DailyBarChart(false).render(bars, List.of(new DailyBarChart.Line(BrWeatherWaterBalanceBlock.STORAGE, storage)), 300, 120);
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        boolean green = false;
        for (int x = 0; x < image.getWidth() && !green; x++) {
            for (int y = 0; y < image.getHeight() && !green; y++) green = image.getRGB(x, y) == BrWeatherWaterBalanceBlock.STORAGE.getRGB();
        }
        assertTrue(green, "the storage line is drawn");
        // the old signature still renders bars only
        assertTrue(new DailyBarChart(false).render(bars, 300, 120).length > 0);
    }
}
