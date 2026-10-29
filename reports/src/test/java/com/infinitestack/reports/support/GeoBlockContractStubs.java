package com.infinitestack.reports.support;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.imageio.ImageIO;

import com.infinitestack.reports.blocks.brgeo.BrGeoBiomesBlock;
import com.infinitestack.reports.blocks.brgeo.BrGeoCensusEvidenceBlock;
import com.infinitestack.reports.blocks.brgeo.BrGeoCropProductionHistoryBlock;
import com.infinitestack.reports.blocks.brgeo.BrGeoSoilProfilesBlock;
import com.infinitestack.reports.blocks.brgeo.BrGeoSoilUnitsBlock;
import com.infinitestack.reports.blocks.brgeo.BrGeoZarcCultivarsBlock;
import com.infinitestack.reports.blocks.brgeo.YearBarChart;
import com.infinitestack.reports.blocks.brweather.BrWeatherTemperatureAnomalyBlock;
import com.infinitestack.reports.blocks.brweather.BrWeatherWaterBalanceBlock;
import com.infinitestack.reports.blocks.sathub.SatelliteHubIndexAnomalyBlock;
import com.infinitestack.reports.blocks.sathub.SatelliteHubIndexCompareBlock;
import com.infinitestack.reports.blocks.brgeo.BrGeoCropProgressBlock;
import com.infinitestack.reports.blocks.brgeo.BrGeoZarcRiskBlock;
import com.infinitestack.reports.blocks.brweather.BrWeatherDailyBlock;
import com.infinitestack.reports.blocks.brweather.BrWeatherRainfallBlockAccess;
import com.infinitestack.reports.blocks.brweather.DailyBarChart;
import com.infinitestack.reports.blocks.brgeo.BrGeoCropProductionBlock;
import com.infinitestack.reports.blocks.brgeo.BrGeoDeterAlertsBlock;
import com.infinitestack.reports.blocks.brgeo.BrGeoEmbargoesBlock;
import com.infinitestack.reports.blocks.brgeo.BrGeoMunicipalitiesBlock;
import com.infinitestack.reports.blocks.brgeo.BrGeoProdesBlock;
import com.infinitestack.reports.blocks.isgeosam.IsGeoSamFieldCandidatesBlock;
import com.infinitestack.reports.blocks.sathub.IndexSeriesChart;
import com.infinitestack.reports.blocks.sathub.SatelliteHubIndexSeriesBlock;
import com.infinitestack.reports.blocks.sathub.SatelliteHubLatestImageBlock;
import com.infinitestack.reports.engine.block.BlockDefinition;
import com.infinitestack.reports.engine.block.ExecutionMetadata;
import com.infinitestack.reports.engine.block.ReportBlock;

/**
 * TEST DOUBLES of the BR Geo, BR Weather and Satellite Hub Blocks, like {@link CarBlockContractStubs}: the real
 * definitions with canned outputs (docs/BLOCKS.md sections 3 and 4). "Bare" is an area with nothing
 * found and, for DETER and PRODES, not monitored, so the other branch of every section renders.
 */
public final class GeoBlockContractStubs {

    private GeoBlockContractStubs() {}

    public static List<ReportBlock> all(boolean bare) {
        return List.of(
                stub("brgeo-municipalities", in -> bare
                        ? map("municipalities", List.of(), "count", 0, "main", null, "mainIbgeCode", null, "mainUf", null, "areaHa", 0.0, "edition", 2025)
                        : map("municipalities", List.of(
                                        map("ibgeCode", "5107065", "name", "Querência", "uf", "MT", "intersectionHa", 1240.1, "percentOfArea", 99.29),
                                        map("ibgeCode", "5106455", "name", "Nova Ubiratã", "uf", "MT", "intersectionHa", 8.87, "percentOfArea", 0.71)),
                                "count", 2, "main", map("ibgeCode", "5107065", "name", "Querência", "uf", "MT", "percentOfArea", 99.29),
                                "mainIbgeCode", 5107065, "mainUf", "MT", "areaHa", 1248.97, "edition", 2025)),
                stub("brgeo-biomes", in -> bare
                        ? map("biomes", List.of(), "count", 0, "mainBiome", null, "areaHa", 0.0, "edition", 2025)
                        : map("biomes", List.of(map("code", 1, "name", "Amazônia", "intersectionHa", 1248.97, "percentOfArea", 100.0)),
                                "count", 1, "mainBiome", "Amazônia", "areaHa", 1248.97, "edition", 2025)),
                stub("brgeo-embargoes", in -> bare
                        ? map("embargoes", List.of(), "count", 0, "activeCount", 0, "hasActive", false, "activeOverlapHa", 0.0,
                                "lastEmbargoAt", null, "truncated", false)
                        : map("embargoes", List.of(
                                        embargo("NV3I40HN", "active", "2026-07-25", null, 4.298, 4.298, 0),
                                        embargo("W8O1VXVC", "lifted", "2024-08-24", "2025-02-10", 162.18, null, 1)),
                                "count", 2, "activeCount", 1, "hasActive", true, "activeOverlapHa", 4.298,
                                "lastEmbargoAt", "2026-07-25", "truncated", false)),
                stub("brgeo-deter-alerts", in -> bare
                        ? map("alerts", List.of(), "byClass", List.of(), "count", 0, "totalOverlapHa", 0.0, "lastAlertDate", null,
                                "monitored", false, "coverage", List.of(coverage("deter_amz", "DETER Amazonia", "Amazônia", false, true, null)),
                                "truncated", false)
                        : map("alerts", List.of(map("viewDate", "2026-09-15", "className", "DESMATAMENTO_CR", "product", "deter_amz",
                                        "productName", "DETER Amazonia", "overlapHa", 6.38, "satellite", "AMAZONIA-1", "sensor", "WFI")),
                                "byClass", List.of(
                                        map("className", "DESMATAMENTO_CR", "count", 3, "overlapHa", 21.4, "firstDate", "2019-08-01", "lastDate", "2026-09-15"),
                                        map("className", "DEGRADACAO", "count", 1, "overlapHa", 9.93, "firstDate", "2026-07-27", "lastDate", "2026-07-27")),
                                "count", 4, "totalOverlapHa", 31.33, "lastAlertDate", "2026-09-15", "monitored", true,
                                "coverage", List.of(coverage("deter_amz", "DETER Amazonia", "Amazônia", true, true, null)), "truncated", false)),
                stub("brgeo-prodes", in -> bare
                        ? map("byYear", List.of(), "byProduct", List.of(), "count", 0, "maxDeforestedHa", 0.0, "lastYear", null,
                                "monitored", false, "coverage", List.of(coverage("prodes_amazon", "PRODES Amazonia", "Amazônia", false, true, 2025)))
                        : map("byYear", List.of(
                                        map("product", "prodes_amazon", "productName", "PRODES Amazonia", "year", 2008, "polygons", 1, "deforestedHa", 6.64),
                                        map("product", "prodes_amazon", "productName", "PRODES Amazonia", "year", 2013, "polygons", 5, "deforestedHa", 37.77)),
                                "byProduct", List.of(map("product", "prodes_amazon", "productName", "PRODES Amazonia", "firstYear", 2008,
                                        "lastYear", 2013, "deforestedHa", 44.41, "sinceCutoffHa", 37.77)),
                                "count", 2, "maxDeforestedHa", 44.41, "lastYear", 2013, "monitored", true,
                                "coverage", List.of(coverage("prodes_amazon", "PRODES Amazonia", "Amazônia", true, true, 2025)))),
                stub("brgeo-crop-production", in -> bare
                        ? map("year", null, "crops", List.of(), "count", 0, "totalHarvestedHa", 0.0, "totalValueThousandBrl", 0.0, "truncated", false, "uf", "MT")
                        : map("year", 2025, "crops", List.of(
                                        map("product", "Soja (em grão)", "plantedHa", 310000.0, "harvestedHa", 310000.0, "quantity", 1104000.0,
                                                "quantityUnit", "Toneladas", "yieldKgHa", 3561.0, "valueThousandBrl", 2318400.0, "shareOfHarvestedPercent", 71.3),
                                        map("product", "Milho (em grão)", "plantedHa", 120000.0, "harvestedHa", 120000.0, "quantity", 744000.0,
                                                "quantityUnit", "Toneladas", "yieldKgHa", 6200.0, "valueThousandBrl", 669600.0, "shareOfHarvestedPercent", 27.6)),
                                "count", 2, "totalHarvestedHa", 434800.0, "totalValueThousandBrl", 2998000.0, "truncated", false, "uf", "MT")),
                stub("sathub-index-series", in -> indexSeries(bare)),
                stub("sathub-latest-image", in -> latestImage(bare)),
                stub("isgeosam-field-candidates", in -> fieldCandidates(bare)),
                stub("brgeo-zarc-risk", in -> zarc(bare)),
                stub("brgeo-crop-progress", in -> cropProgress(bare)),
                stub("brweather-daily-weather", in -> dailyWeather(bare)),
                stub("brweather-rainfall", in -> rainfall(bare)),
                stub("brweather-water-balance", in -> waterBalance(bare)),
                stub("brweather-temperature-anomaly", in -> temperatureAnomaly(bare)),
                stub("brgeo-soil-units", in -> soilUnits(bare)),
                stub("brgeo-soil-profiles", in -> soilProfiles(bare)),
                stub("brgeo-census-evidence", in -> censusEvidence(bare)),
                stub("brgeo-crop-production-history", in -> cropProductionHistory(bare, in)),
                stub("brgeo-zarc-cultivars", in -> zarcCultivars(bare)),
                stub("sathub-index-compare", in -> indexCompare(bare)),
                stub("sathub-index-anomaly", in -> indexAnomaly(bare)));
    }

    /** Full: soy in Querência with two rows. Bare: the crop is not zoned there. */
    private static Map<String, Object> zarc(boolean bare) {
        List<Map<String, Object>> periods = new ArrayList<>();
        for (int p = 1; p <= 36; p++) {
            String from = String.format("%02d/%02d", new int[] {1, 11, 21}[(p - 1) % 3], (p - 1) / 3 + 1);
            periods.add(map("period", p, "from", from, "to", from, "label", from));
        }
        List<String> crops = List.of("Algodão", "Milho 2ª safra", "Soja");
        if (bare) {
            Map<String, Object> out = map("found", false, "message", "the ZARC has no 'Trigo' for municipality 5107065",
                    "crop", null, "season", null, "uf", "MT", "municipality", null, "ordinance", null, "ordinanceDate", null);
            out.putAll(map("rows", List.of(), "count", 0, "truncated", false, "periods", periods, "availableCrops", crops, "matchedBy", null));
            return out;
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String[] r : new String[][] {{"Grupo I", "AD1"}, {"Grupo II", "AD3"}}) {
            List<Integer> risk = new ArrayList<>();
            List<Map<String, Object>> cells = new ArrayList<>();
            for (int p = 1; p <= 36; p++) {
                int value = p >= 28 && p <= 31 ? 20 : p == 32 ? 30 : p == 33 ? 40 : 0;
                risk.add(value);
                cells.add(map("period", p, "risk", value, "label", value > 0 ? value + "%" : "-"));
            }
            rows.add(map("croppingSystem", "Sequeiro", "climateType", null, "cultivarGroup", r[0], "soil", r[1], "risk", risk,
                    "cells", cells, "indicatedPeriods", 6, "lowestRisk", 20,
                    "windows", List.of(map("risk", 20, "fromPeriod", 28, "toPeriod", 31, "from", "21/09", "to", "31/10", "label", "21/09 a 31/10 (20%)"),
                            map("risk", 30, "fromPeriod", 32, "toPeriod", 32, "from", "01/11", "to", "10/11", "label", "01/11 a 10/11 (30%)"),
                            map("risk", 40, "fromPeriod", 33, "toPeriod", 33, "from", "11/11", "to", "20/11", "label", "11/11 a 20/11 (40%)"))));
        }
        Map<String, Object> out = map("found", true, "message", null, "crop", "Soja", "season", "2026/2027", "uf", "MT",
                "municipality", "Querência", "ordinance", "Portaria SPA/MAPA nº 312", "ordinanceDate", "2026-06-20");
        out.putAll(map("rows", rows, "count", 2, "truncated", false, "periods", periods, "availableCrops", crops, "matchedBy", "code"));
        return out;
    }

    /** Full: soy sowing in Mato Grosso. Bare: no recent survey for the state. */
    private static Map<String, Object> cropProgress(boolean bare) {
        List<String> states = List.of("Goiás", "Mato Grosso", "Paraná");
        if (bare) {
            return map("found", false, "message", "CONAB has no recent progress for Acre", "state", null,
                    "latestWeekEnd", "2026-10-25", "items", List.of(), "count", 0, "availableStates", states);
        }
        Map<String, Object> item = map("crop", "Soja", "season", "2026/27", "operation", "sowing", "operationLabel", "Semeadura",
                "weekEnd", "2026-10-25", "pctDone", 47.3, "previousWeekPct", 31.2, "weekChangePoints", 16.1, "lastYearPct", 52.0,
                "avg5yPct", 44.8, "vsAvg5yPoints", 2.5);
        item.put("series", List.of(map("weekEnd", "2026-10-18", "pctDone", 31.2), map("weekEnd", "2026-10-25", "pctDone", 47.3)));
        return map("found", true, "message", null, "state", "Mato Grosso", "latestWeekEnd", "2026-10-25",
                "items", List.of(item), "count", 1, "availableStates", states);
    }

    /** Full: 30 days of NASA POWER. Bare: the area has no weather loaded. */
    private static Map<String, Object> dailyWeather(boolean bare) {
        List<Map<String, Object>> days = new ArrayList<>();
        LocalDate day = LocalDate.parse("2026-09-27");
        if (!bare) {
            for (int i = 0; i < 30; i++, day = day.plusDays(1)) {
                days.add(map("date", day.toString(), "t2m", 27.4, "t2mMax", 35.1, "t2mMin", 19.8, "rh2m", 58.2, "ws2m", 1.9,
                        "radiation", 21.3, "precipitation", i % 4 == 0 ? 12.0 : 0.0, "et0Mm", i >= 28 ? null : 5.12));
            }
        }
        Map<String, Object> out = map("found", !bare, "message", bare ? "BR Weather has no NASA POWER data for this area" : null,
                "from", bare ? null : "2026-09-27", "to", bare ? null : "2026-10-26", "latestDay", bare ? null : "2026-10-26",
                "cells", bare ? List.of() : List.of(map("lat", -12.75, "lon", -52.25, "elevationM", 352.0, "weightPercent", 100.0)),
                "nearestCell", false, "days", days, "daysWithData", days.size());
        out.putAll(map("meanT2m", bare ? null : 27.4, "maxT2m", bare ? null : 38.2, "maxT2mDate", bare ? null : "2026-10-08",
                "minT2m", bare ? null : 16.1, "minT2mDate", bare ? null : "2026-09-30", "meanRh2m", bare ? null : 58.2,
                "meanWs2m", bare ? null : 1.9, "meanRadiation", bare ? null : 21.3, "powerPrecipitationMm", bare ? null : 96.0,
                "degreeDays", bare ? null : 522.0, "hotDays", bare ? 0 : 9, "coldNights", 0, "totalEt0Mm", bare ? null : 143.4,
                "meanEt0Mm", bare ? null : 5.12, "et0Days", bare ? 0 : 28, "chart", tinyPng(0xc62828)));
        return out;
    }

    /** Full: 30 days of CHIRPS with a dry spell. Bare: no clip loaded for the period. */
    private static Map<String, Object> rainfall(boolean bare) {
        List<Map<String, Object>> days = new ArrayList<>();
        List<DailyBarChart.Bar> bars = new ArrayList<>();
        LocalDate day = LocalDate.parse("2026-09-27");
        if (!bare) {
            for (int i = 0; i < 30; i++, day = day.plusDays(1)) {
                double mm = i < 12 ? 0.0 : (i % 3 == 0 ? 18.4 : 2.1);
                days.add(map("date", day.toString(), "rainMm", mm, "preliminary", i > 25));
                bars.add(new DailyBarChart.Bar(day, mm));
            }
        }
        String chart = "data:image/png;base64," + Base64.getEncoder().encodeToString(new DailyBarChart().render(bars, 900, 260));
        Map<String, Object> out = map("found", !bare, "message", bare ? "no CHIRPS clip for the area between 2026-09-27 and 2026-10-26" : null,
                "from", "2026-09-27", "to", "2026-10-26", "latestDay", bare ? null : "2026-10-26", "pixels", bare ? 0 : 4,
                "wholePixels", true, "days", days, "daysWithData", days.size(), "missingDays", bare ? 30 : 0);
        out.putAll(map("totalMm", bare ? null : 148.2, "meanDailyMm", bare ? null : 4.94, "maxDayMm", bare ? null : 18.4,
                "maxDayDate", bare ? null : "2026-10-09", "rainyDays", bare ? 0 : 18, "longestDrySpell", bare ? 0 : 12,
                "preliminaryDays", bare ? 0 : 4, "heavyRainDays", 0, "rx1dayMm", bare ? null : 18.4, "rx5dayMm", bare ? null : 43.0,
                "drySpells10", bare ? 0 : 1, "drySpells15", 0, "chart", chart));
        return out;
    }

    /** Full: a scene found, with tiny PNGs built here (not with ImageryRenderer). Bare: every scene too cloudy. */
    private static Map<String, Object> latestImage(boolean bare) {
        List<Map<String, Object>> skipped = List.of(
                map("id", "S2B_19LFJ_20261021_0_L2A", "date", "2026-10-21", "areaCloudPercent", 64.2),
                map("id", "S2A_19LFJ_20261016_0_L2A", "date", "2026-10-16", "areaCloudPercent", 41.0));
        if (bare) {
            Map<String, Object> out = map("found", false,
                    "message", "the 4 most recent scenes had more than 30% clouds over the area",
                    "scene", null, "acquiredDate", null, "daysAgo", null, "coveragePercent", null, "areaCloudPercent", null,
                    "pixelSizeM", null, "widthPx", null, "heightPx", null, "trueColor", null,
                    "imageCrs", null, "imageTransform", null, "imageBounds", null);
            out.put("indices", List.of());
            out.put("candidatesTried", 4);
            out.put("skipped", skipped);
            return out;
        }
        List<Map<String, Object>> legend = List.of(
                map("color", "#a50026", "value", 0.0, "label", "0.00"), map("color", "#f46d43", "value", 0.25, "label", "0.25"),
                map("color", "#ffffbf", "value", 0.5, "label", "0.50"), map("color", "#a6d96a", "value", 0.75, "label", "0.75"),
                map("color", "#006837", "value", 1.0, "label", "1.00"));
        List<Map<String, Object>> indices = new ArrayList<>();
        String[][] defs = {{"ndvi", "NDVI", "Normalized Difference Vegetation Index"}, {"evi", "EVI", "Enhanced Vegetation Index"},
                {"ndmi", "NDMI", "Normalized Difference Moisture Index"}, {"ndre", "NDRE", "Normalized Difference Red Edge"}};
        for (int i = 0; i < defs.length; i++) {
            double mean = Math.round((0.62 - 0.1 * i) * 1e4) / 1e4;
            Map<String, Object> item = map("slug", defs[i][0], "name", defs[i][1], "description", defs[i][2],
                    "image", tinyPng(0x2e7d32 + 0x101000 * i), "mean", mean, "min", -0.05, "max", 0.91, "stddev", 0.12,
                    "p10", Math.round((mean - 0.18) * 1e4) / 1e4, "p50", Math.round((mean + 0.02) * 1e4) / 1e4,
                    "p90", Math.round((mean + 0.14) * 1e4) / 1e4, "validPixels", 38211, "validPercent", 97.4,
                    "rangeMin", "ndmi".equals(defs[i][0]) ? -1.0 : 0.0, "rangeMax", 1.0);
            item.put("legend", legend);
            indices.add(item);
        }
        Map<String, Object> out = map("found", true, "message", null,
                "scene", map("id", "S2A_19LFJ_20261011_0_L2A", "acquiredAt", "2026-10-11T14:52:31.024Z", "date", "2026-10-11",
                        "platform", "sentinel-2a", "mgrsTile", "19LFJ", "cloudCoverPercent", 12.3,
                        "stacUrl", "https://earth-search.aws.element84.com/v1/collections/sentinel-2-l2a/items/S2A_19LFJ_20261011_0_L2A"),
                "acquiredDate", "2026-10-11", "daysAgo", 15, "coveragePercent", 100.0, "areaCloudPercent", 2.6,
                "pixelSizeM", 30.0, "widthPx", 800, "heightPx", 612, "trueColor", tinyPng(0x6b7f4a),
                "imageCrs", "EPSG:32719", "imageTransform", List.of(30.0, 0.0, 654770.0, 0.0, -30.0, 9116060.0),
                "imageBounds", List.of(-67.5791, -8.1622, -67.3584, -7.9958));
        out.put("indices", indices);
        out.put("candidatesTried", 3);
        out.put("skipped", skipped);
        return out;
    }

    /** Full: a finished segmentation with two candidates. Bare: still running. */
    private static Map<String, Object> fieldCandidates(boolean bare) {
        Map<String, Object> out = map("status", bare ? "PROCESSING" : "COMPLETED_WITH_REVIEW_REQUIRED", "ready", !bare,
                "processing", bare, "hasCandidates", !bare,
                "message", bare ? "the segmentation did not finish within the wait (run t-9 goes on); generate the report again in a few minutes" : null);
        out.putAll(map("candidates", bare ? List.of() : List.of(
                        map("number", 1, "objectId", 7, "areaHa", 182.4, "modelScore", 0.962, "stabilityScore", 0.981,
                                "status", "PENDING_REVIEW", "reviewFlags", "", "hasFlags", false),
                        map("number", 2, "objectId", 3, "areaHa", 64.15, "modelScore", 0.911, "stabilityScore", 0.944,
                                "status", "PENDING_REVIEW", "reviewFlags", "touches_image_edge, clipped_by_aoi", "hasFlags", true)),
                "candidateCount", bare ? 0 : 2, "totalAreaHa", bare ? 0.0 : 246.55, "truncated", false,
                "features", null, "map", bare ? null : tinyPng(0x556b2f)));
        out.putAll(map("reused", !bare, "runId", bare ? null : "5b1f0c1e-0000-4000-8000-000000000001", "triggerId", "t-9",
                "model", bare ? null : "sam2.1_hiera_small", "segmentedAt", bare ? null : "2026-10-28T15:02:11Z"));
        return out;
    }

    /** Full: 60 days of balance ending dry. Bare: no rain or weather loaded for the area. */
    private static Map<String, Object> waterBalance(boolean bare) {
        List<Map<String, Object>> days = new ArrayList<>();
        LocalDate day = LocalDate.parse("2026-08-28");
        if (!bare) {
            double storage = 50.0;
            for (int i = 0; i < 60; i++, day = day.plusDays(1)) {
                double rain = i % 9 == 0 ? 14.0 : 0.0;
                double eta = Math.min(5.0, storage + rain);
                storage = Math.max(0.0, Math.min(50.0, storage + rain - eta * 0.8));
                days.add(map("date", day.toString(), "rainMm", rain, "et0Mm", 5.0, "etcMm", 5.0, "etaMm", eta,
                        "storageMm", Math.round(storage * 10) / 10.0, "deficitMm", 5.0 - eta, "excessMm", 0.0, "rainMissing", false,
                        "et0Estimated", i >= 58));
            }
        }
        Map<String, Object> out = map("found", !bare, "message", bare ? "BR Weather has no rain and weather for this area" : null,
                "from", bare ? null : "2026-08-28", "to", bare ? null : "2026-10-26", "latestRainDay", bare ? null : "2026-10-26",
                "latestWeatherDay", bare ? null : "2026-10-26", "cadMm", 50.0, "kc", 1.0, "days", days);
        out.putAll(map("totalRainMm", bare ? null : 98.0, "totalEt0Mm", bare ? null : 301.4, "totalEtcMm", bare ? null : 301.4,
                "totalEtaMm", bare ? null : 146.0, "totalDeficitMm", bare ? null : 155.4, "totalExcessMm", bare ? null : 0.0,
                "finalStorageMm", bare ? null : 9.1, "finalStoragePercent", bare ? null : 18.2, "deficitDays", bare ? 0 : 38,
                "maxConsecutiveDeficitDays", bare ? 0 : 14, "missingRainDays", 0, "estimatedEt0Days", bare ? 0 : 2,
                "cells", bare ? List.of() : List.of(map("lat", -12.75, "lon", -52.25, "elevationM", 352.0, "weightPercent", 100.0)),
                "pixels", bare ? 0 : 4, "chart", tinyPng(0x1565c0)));
        return out;
    }

    /** Full: a warm period against 2000-2025. Bare: no climatology for the area. */
    private static Map<String, Object> temperatureAnomaly(boolean bare) {
        List<Map<String, Object>> perYear = new ArrayList<>();
        if (!bare) {
            for (int y = 2021; y <= 2025; y++) {
                perYear.add(map("year", y, "meanT2m", Math.round((26.1 + (y - 2021) * 0.2) * 10) / 10.0,
                        "meanT2mMax", Math.round((33.0 + (y - 2021) * 0.3) * 10) / 10.0, "meanT2mMin", 19.4, "current", false));
            }
        }
        Map<String, Object> out = map("found", !bare, "message", bare ? "no NASA POWER climatology for this area" : null,
                "from", bare ? null : "2026-09-27", "to", bare ? null : "2026-10-26", "latestDay", bare ? null : "2026-10-26",
                "cells", bare ? List.of() : List.of(map("lat", -12.75, "lon", -52.25, "elevationM", 352.0, "weightPercent", 100.0)),
                "nearestCell", false, "daysWithData", bare ? 0 : 30);
        out.putAll(map("meanT2m", bare ? null : 27.4, "climatologyT2m", bare ? null : 26.3, "anomalyT2m", bare ? null : 1.1,
                "meanT2mMax", bare ? null : 35.1, "climatologyT2mMax", bare ? null : 33.4, "anomalyT2mMax", bare ? null : 1.7,
                "meanT2mMin", bare ? null : 19.8, "climatologyT2mMin", bare ? null : 19.5, "anomalyT2mMin", bare ? null : 0.3));
        out.putAll(map("hotDays", bare ? 0 : 8, "hotDayThresholdC", bare ? null : 36.2, "coldNights", bare ? 0 : 1,
                "coldNightThresholdC", bare ? null : 16.9, "yearsUsed", bare ? 0 : 26, "firstYear", bare ? null : 2000,
                "lastYear", bare ? null : 2025, "perYear", perYear));
        return out;
    }

    /** Full: a Latossolo with a steep, stony Neossolo strip. Bare: outside the pedology map. */
    private static Map<String, Object> soilUnits(boolean bare) {
        if (bare) {
            return map("hasUnits", false, "units", List.of(), "count", 0, "truncated", false, "byOrder", List.of(), "mainOrder", null,
                    "flags", map("steepRelief", false, "stony", false, "rocky", false, "organosol", false, "vertisol", false),
                    "restrictions", List.of(), "hasRestrictions", false, "areaHa", 1248.97, "edition", 2023);
        }
        Map<String, Object> lv = map("symbol", "LVAd", "legend", "LATOSSOLO VERMELHO-AMARELO Distrófico típico, A moderado, textura argilosa",
                "order", "LATOSSOLOS", "suborder", "LATOSSOLOS VERMELHO-AMARELOS", "greatGroup", "Distróficos", "texture", "argilosa",
                "relief", "plano e suave ondulado", "stoniness", null, "rockiness", null, "erosion", null,
                "components", List.of("LVAd - LATOSSOLO VERMELHO-AMARELO Distrófico típico"), "componentsCut", false,
                "restrictions", List.of(), "overlapHa", 1064.2, "percent", 85.21);
        Map<String, Object> rl = map("symbol", "RLd", "legend", "NEOSSOLO LITÓLICO Distrófico típico, fase pedregosa, relevo forte ondulado",
                "order", "NEOSSOLOS", "suborder", "NEOSSOLOS LITÓLICOS", "greatGroup", "Distróficos", "texture", "média",
                "relief", "forte ondulado", "stoniness", "pedregosa", "rockiness", null, "erosion", null,
                "components", List.of("RLd - NEOSSOLO LITÓLICO Distrófico típico"), "componentsCut", false,
                "restrictions", List.of("STEEP_RELIEF", "STONY"), "overlapHa", 184.77, "percent", 14.79);
        return map("hasUnits", true, "units", List.of(lv, rl), "count", 2, "truncated", false,
                "byOrder", List.of(map("order", "LATOSSOLOS", "overlapHa", 1064.2, "percent", 85.21),
                        map("order", "NEOSSOLOS", "overlapHa", 184.77, "percent", 14.79)),
                "mainOrder", "LATOSSOLOS",
                "flags", map("steepRelief", true, "stony", true, "rocky", false, "organosol", false, "vertisol", false),
                "restrictions", List.of(
                        map("code", "STEEP_RELIEF", "label", "steep relief (strongly undulating, mountainous or escarpment)", "overlapHa", 184.77, "percent", 14.79),
                        map("code", "STONY", "label", "stony phase", "overlapHa", 184.77, "percent", 14.79)),
                "hasRestrictions", true, "areaHa", 1248.97, "edition", 2023);
    }

    /** Full: two profiles, one with lab data. Bare: none within the radius. */
    private static Map<String, Object> soilProfiles(boolean bare) {
        if (bare) return map("found", false, "profiles", List.of(), "count", 0, "radiusKm", 20.0, "edition", 2023);
        Map<String, Object> p1 = map("code", "RADAM-SC22-041", "pointCode", "P-041", "type", "Perfil completo", "distanceKm", 3.4,
                "inside", false, "lat", -12.66, "lon", -52.15, "order", "LATOSSOLOS", "suborder", "LATOSSOLOS VERMELHO-AMARELOS",
                "greatGroup", "Distróficos", "texture", "argilosa", "drainage", "acentuadamente drenado", "relief", "plano",
                "sand0_40Pct", 38.2, "silt0_40Pct", 11.6, "clay0_40Pct", 50.2, "covered0_40Cm", 40.0, "hasLabData", true);
        p1.put("horizons", List.of(
                map("name", "A", "top", "0", "bottom", "18", "topCm", 0, "bottomCm", 18, "textureClass", "argilosa", "sandPct", 40.1, "siltPct", 12.0, "clayPct", 47.9),
                map("name", "Bw1", "top", "18", "bottom", "70", "topCm", 18, "bottomCm", 70, "textureClass", "muito argilosa", "sandPct", 36.6, "siltPct", 11.3, "clayPct", 52.1)));
        Map<String, Object> p2 = map("code", "RADAM-SC22-057", "pointCode", "P-057", "type", "Amostra extra", "distanceKm", 11.8,
                "inside", false, "lat", -12.72, "lon", -52.08, "order", "NEOSSOLOS", "suborder", "NEOSSOLOS LITÓLICOS",
                "greatGroup", "Distróficos", "texture", "média", "drainage", "bem drenado", "relief", "forte ondulado",
                "sand0_40Pct", null, "silt0_40Pct", null, "clay0_40Pct", null, "covered0_40Cm", 0.0, "hasLabData", false);
        p2.put("horizons", List.of());
        return map("found", true, "profiles", List.of(p1, p2), "count", 2, "radiusKm", 20.0, "edition", 2023);
    }

    /** Full: a route inside the area and two CNEFE addresses. Bare: no census data for the municipality. */
    private static Map<String, Object> censusEvidence(boolean bare) {
        Map<String, Object> out = map("status", bare ? "dados_indisponiveis" : "trajeto_interno", "routeInside", !bare,
                "routeNear", !bare, "dataAvailable", !bare, "insideKm", bare ? 0.0 : 6.214, "insideMPerHa", bare ? 0.0 : 4.98,
                "minDistanceM", bare ? null : 0.0, "routesCrossing", bare ? 0 : 3, "routesNear", bare ? 0 : 5,
                "cnefeInside", bare ? 0 : 1, "cnefeNearCount", bare ? 0 : 2);
        out.putAll(map("cnefeNear", bare ? List.of() : List.of(
                        map("distanceM", 0.0, "inside", true, "locality", "Gleba Santa Rita", "address", "Estrada Vicinal MT-322", "number", "SN",
                                "situation", "Rural", "municipalityIbge", 5107065, "lat", -12.62, "lon", -52.19),
                        map("distanceM", 342.5, "inside", false, "locality", "Gleba Santa Rita", "address", "Estrada Vicinal MT-322", "number", "KM 14",
                                "situation", "Rural", "municipalityIbge", 5107065, "lat", -12.645, "lon", -52.2)),
                "truncated", false, "bufferM", 500, "edgeToleranceM", 30, "year", 2017));
        return out;
    }

    /** Full: ten years of the crop in the municipality. Bare: the PAM does not have it. */
    private static Map<String, Object> cropProductionHistory(boolean bare, Map<String, Object> in) {
        List<String> products = List.of("Algodão herbáceo (em caroço)", "Milho (em grão)", "Soja (em grão)");
        if (bare) {
            return map("found", false, "message", "the PAM has no '" + in.get("product") + "' for municipality " + in.get("municipalityIbge"),
                    "product", null, "series", List.of(), "count", 0, "fromYear", null, "toYear", null, "unit", null, "chart", null,
                    "chartVariable", "harvestedHa", "availableProducts", products, "uf", "MT");
        }
        List<Map<String, Object>> series = new ArrayList<>();
        List<YearBarChart.Bar> bars = new ArrayList<>();
        for (int y = 2016; y <= 2025; y++) {
            double ha = 220000.0 + (y - 2016) * 10000.0;
            series.add(map("year", y, "plantedHa", ha, "harvestedHa", ha, "quantity", ha * 3.4, "unit", "Toneladas",
                    "yieldKgHa", 3400.0 + (y - 2016) * 20, "valueThousandBrl", ha * 4.1));
            bars.add(new YearBarChart.Bar(y, ha));
        }
        String chart = "data:image/png;base64," + Base64.getEncoder().encodeToString(new YearBarChart().render(bars, 900, 260));
        return map("found", true, "message", null, "product", "Soja (em grão)", "series", series, "count", series.size(),
                "fromYear", 2016, "toYear", 2025, "unit", "Toneladas", "chart", chart, "chartVariable", "harvestedHa",
                "availableProducts", products, "uf", "MT");
    }

    /** Full: three soy cultivars in MT. Bare: no cultivar of the crop in the state. */
    private static Map<String, Object> zarcCultivars(boolean bare) {
        List<String> crops = List.of("Algodão", "Milho", "Soja");
        if (bare) {
            return map("found", false, "message", "the ZARC has no cultivars of 'Trigo' in MT", "crop", null, "season", null, "uf", "MT",
                    "cultivars", List.of(), "count", 0, "distinctCultivars", 0, "adaptationRegions", List.of(), "truncated", false,
                    "availableCrops", crops);
        }
        List<Map<String, Object>> cultivars = List.of(
                map("rnc", "41290", "cultivar", "BRS 7380 RR", "adaptationRegion", "401", "group", "7.3", "cycle", "médio",
                        "attributes", map("Grupo de maturação", "7,3", "Ciclo", "médio")),
                map("rnc", "38710", "cultivar", "M 8372 IPRO", "adaptationRegion", "401", "group", "8.3", "cycle", "tardio",
                        "attributes", map("Grupo de maturação", "8,3", "Ciclo", "tardio")),
                map("rnc", "40121", "cultivar", "TMG 2381 IPRO", "adaptationRegion", "402", "group", "8.1", "cycle", "médio",
                        "attributes", map("Grupo de maturação", "8,1", "Ciclo", "médio")));
        return map("found", true, "message", null, "crop", "Soja", "season", "2026/2027", "uf", "MT", "cultivars", cultivars,
                "count", 3, "distinctCultivars", 3, "adaptationRegions", List.of("401", "402"), "truncated", false, "availableCrops", crops);
    }

    /** Full: Sentinel-2 and Landsat NDVI with a pair. Bare: only Sentinel-2 (no pair) and Sentinel-1 without data. */
    private static Map<String, Object> indexCompare(boolean bare) {
        List<Map<String, Object>> series = new ArrayList<>();
        series.add(map("index", map("slug", "ndvi", "name", "NDVI", "description", "Normalized Difference Vegetation Index",
                        "collection", "sentinel-2-l2a", "collectionName", "Sentinel-2 L2A", "enabled", true),
                "points", List.of(map("date", "2026-10-11", "mean", 0.62, "p10", 0.48, "p90", 0.74)), "count", 14,
                "latest", map("date", "2026-10-11", "mean", 0.62), "periodMean", 0.55, "rightAxis", false));
        if (!bare) {
            series.add(map("index", map("slug", "ndvi_landsat", "name", "NDVI (Landsat)", "description", "NDVI from Landsat 8/9",
                            "collection", "landsat-c2-l2", "collectionName", "Landsat 8/9 C2 L2", "enabled", true),
                    "points", List.of(map("date", "2026-10-09", "mean", 0.58, "p10", 0.44, "p90", 0.7)), "count", 7,
                    "latest", map("date", "2026-10-09", "mean", 0.58), "periodMean", 0.52, "rightAxis", false));
        }
        return map("series", series, "seriesCount", series.size(), "hasSeries", true,
                "withoutData", bare ? List.of("rvi_s1") : List.of(),
                "pairs", bare ? List.of() : List.of(map("a", "ndvi", "b", "ndvi_landsat", "aName", "NDVI", "bName", "NDVI (Landsat)",
                        "matchedDates", 6, "meanDiff", 0.031, "correlation", 0.94)),
                "areas", List.of(map("id", 16991L, "farmName", "Fazenda Exemplo", "blockName", "Bloco 2", "parcelName", "Talhão 7",
                        "areaHa", 26.79, "insideHa", 26.79)),
                "areaCount", 1, "chart", tinyPng(0x2e7d32), "truncated", false);
    }

    /** Full: one drop, the latest image back to normal, with a seasonal baseline. Bare: no image in the period. */
    private static Map<String, Object> indexAnomaly(boolean bare) {
        Map<String, Object> index = map("slug", "ndvi", "name", "NDVI", "description", "Normalized Difference Vegetation Index",
                "collection", "sentinel-2-l2a", "collectionName", "Sentinel-2 L2A", "enabled", true);
        if (bare) {
            Map<String, Object> out = map("index", index, "points", List.of(), "count", 0, "events", List.of(), "eventCount", 0,
                    "hasDrops", false, "lastDropDate", null, "latestStatus", "no-data", "latestDate", null, "latestValue", null,
                    "latestDelta", null);
            out.putAll(map("seasonalBaseline", null, "hasSeasonalBaseline", false, "areas", List.of(), "areaCount", 0,
                    "chart", tinyPng(0x9e9e9e), "truncated", false));
            return out;
        }
        List<Map<String, Object>> points = List.of(
                map("date", "2026-09-21", "value", 0.64, "baseline", 0.62, "delta", 0.02, "isDrop", false),
                map("date", "2026-10-01", "value", 0.41, "baseline", 0.63, "delta", -0.22, "isDrop", true),
                map("date", "2026-10-11", "value", 0.6, "baseline", 0.63, "delta", -0.03, "isDrop", false));
        Map<String, Object> out = map("index", index, "points", points, "count", 3,
                "events", List.of(map("date", "2026-10-01", "value", 0.41, "baseline", 0.63, "delta", -0.22)), "eventCount", 1,
                "hasDrops", true, "lastDropDate", "2026-10-01", "latestStatus", "normal", "latestDate", "2026-10-11",
                "latestValue", 0.6, "latestDelta", -0.03);
        out.putAll(map("seasonalBaseline", map("month", 10, "years", List.of(map("year", 2024, "mean", 0.57), map("year", 2025, "mean", 0.61)),
                        "mean", 0.59, "observations", 6, "latestValue", 0.6, "anomaly", 0.01),
                "hasSeasonalBaseline", true,
                "areas", List.of(map("id", 16991L, "farmName", "Fazenda Exemplo", "blockName", "Bloco 2", "parcelName", "Talhão 7",
                        "areaHa", 26.79, "insideHa", 26.79)),
                "areaCount", 1, "chart", tinyPng(0x2e7d32), "truncated", false));
        return out;
    }

    private static String tinyPng(int rgb) {
        BufferedImage image = new BufferedImage(8, 6, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 6; y++) for (int x = 0; x < 8; x++) image.setRGB(x, y, x < 4 ? rgb : 0x8a7354);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
    }

    private static Map<String, Object> indexSeries(boolean bare) {
        List<Map<String, Object>> series = new ArrayList<>();
        List<IndexSeriesChart.Point> points = new ArrayList<>();
        if (!bare) {
            LocalDate day = LocalDate.parse("2026-04-20");
            for (int i = 0; i < 12; i++, day = day.plusDays(15)) {
                double mean = Math.round((0.35 + 0.4 * Math.sin(i / 3.0)) * 1e4) / 1e4;
                series.add(map("date", day.toString(), "mean", mean, "min", mean - 0.3, "max", mean + 0.2, "p10", mean - 0.1,
                        "p90", mean + 0.08, "validAreaPercent", 92.5, "areas", 1));
                points.add(new IndexSeriesChart.Point(day, mean, mean - 0.1, mean + 0.08));
            }
        }
        String chart = "data:image/png;base64," + Base64.getEncoder().encodeToString(new IndexSeriesChart().render(points, 900, 300));
        Map<String, Object> last = series.isEmpty() ? null : series.get(series.size() - 1);
        return map("index", map("slug", "ndvi", "name", "NDVI", "description", "Normalized Difference Vegetation Index",
                        "collection", "sentinel-2-l2a", "collectionName", "Sentinel-2 L2A", "enabled", true),
                "indexEnabled", true,
                "areas", bare ? List.of() : List.of(map("id", 16991L, "farmName", "Fazenda Exemplo", "blockName", "Bloco 2",
                        "parcelName", "Talhão 7", "areaHa", 26.79, "insideHa", 26.79)),
                "areaCount", bare ? 0 : 1, "series", series, "count", series.size(),
                "latestDate", last == null ? null : last.get("date"), "latestMean", last == null ? null : last.get("mean"),
                "periodMean", bare ? null : 0.41, "periodMin", bare ? null : 0.05, "periodMax", bare ? null : 0.75,
                "chart", chart, "truncated", false);
    }

    private static Map<String, Object> embargo(String term, String status, String at, String liftedAt, double area, Double overlap, int decisions) {
        return map("term", term, "series", null, "seqTad", 1889222, "status", status, "active", "active".equals(status),
                "lifted", "lifted".equals(status), "cancelled", "cancelled".equals(status), "formStatus", "Lavrado",
                "embargoedAt", at, "liftedAt", liftedAt, "liftType", liftedAt == null ? null : "Administrativo", "changedAt", at,
                "areaType", "Desmatamento", "deterProdes", null, "embargoedAreaHa", area, "overlapHa", overlap,
                "matchedBy", overlap == null ? "point" : "polygon", "uf", "MT", "municipalityIbge", "5107065",
                "processNumber", "02013.000123/2026-11", "infractionNotice", "L9DB4DOP", "judicialDecisions", decisions);
    }

    private static Map<String, Object> coverage(String product, String name, String biome, boolean intersects, boolean loaded, Integer lastYear) {
        Map<String, Object> m = map("product", product, "productName", name, "biome", biome, "intersectsArea", intersects, "loaded", loaded);
        if (product.startsWith("prodes")) m.put("lastYear", lastYear);
        return m;
    }

    private static Map<String, Object> map(Object... keyValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) m.put((String) keyValues[i], keyValues[i + 1]);
        return m;
    }

    /** The real definitions, so a stub can never drift from the Block it stands for. */
    public static final Map<String, BlockDefinition> REAL = Stream.of(
                    new BrGeoMunicipalitiesBlock(null), new BrGeoBiomesBlock(null), new BrGeoEmbargoesBlock(null),
                    new BrGeoDeterAlertsBlock(null), new BrGeoProdesBlock(null), new BrGeoCropProductionBlock(null),
                    new SatelliteHubIndexSeriesBlock(null), new SatelliteHubLatestImageBlock(null, null, null),
                    new BrGeoZarcRiskBlock(null), new BrGeoCropProgressBlock(null), new BrWeatherDailyBlock(null),
                    BrWeatherRainfallBlockAccess.create(null, Path.of("/nonexistent")),
                    new IsGeoSamFieldCandidatesBlock(null, null, "/nonexistent", "", "is-geo-sam"),
                    new BrWeatherWaterBalanceBlock(null, "/nonexistent"), new BrWeatherTemperatureAnomalyBlock(null),
                    new BrGeoSoilUnitsBlock(null), new BrGeoSoilProfilesBlock(null), new BrGeoCensusEvidenceBlock(null),
                    new BrGeoCropProductionHistoryBlock(null), new BrGeoZarcCultivarsBlock(null),
                    new SatelliteHubIndexCompareBlock(null), new SatelliteHubIndexAnomalyBlock(null))
            .map(ReportBlock::definition)
            .collect(Collectors.toMap(BlockDefinition::id, d -> d));

    private static ReportBlock stub(String id, Function<Map<String, Object>, Map<String, Object>> outputs) {
        BlockDefinition definition = REAL.get(id);
        return new ReportBlock() {
            @Override public BlockDefinition definition() { return definition; }
            @Override public Map<String, Object> execute(Map<String, Object> inputs, ExecutionMetadata metadata) { return outputs.apply(inputs); }
        };
    }
}
