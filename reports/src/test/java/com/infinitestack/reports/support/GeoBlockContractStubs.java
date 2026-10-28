package com.infinitestack.reports.support;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
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
import com.infinitestack.reports.blocks.brgeo.BrGeoCropProductionBlock;
import com.infinitestack.reports.blocks.brgeo.BrGeoDeterAlertsBlock;
import com.infinitestack.reports.blocks.brgeo.BrGeoEmbargoesBlock;
import com.infinitestack.reports.blocks.brgeo.BrGeoMunicipalitiesBlock;
import com.infinitestack.reports.blocks.brgeo.BrGeoProdesBlock;
import com.infinitestack.reports.blocks.sathub.IndexSeriesChart;
import com.infinitestack.reports.blocks.sathub.SatelliteHubIndexSeriesBlock;
import com.infinitestack.reports.blocks.sathub.SatelliteHubLatestImageBlock;
import com.infinitestack.reports.engine.block.BlockDefinition;
import com.infinitestack.reports.engine.block.ExecutionMetadata;
import com.infinitestack.reports.engine.block.ReportBlock;

/**
 * TEST DOUBLES of the BR Geo and Satellite Hub Blocks, like {@link CarBlockContractStubs}: the real
 * definitions with canned outputs (docs/BLOCKS.md sections 3 and 4). "Bare" is an area with nothing
 * found and, for DETER and PRODES, not monitored, so the other branch of every section renders.
 */
public final class GeoBlockContractStubs {

    private GeoBlockContractStubs() {}

    public static List<ReportBlock> all(boolean bare) {
        return List.of(
                stub("brgeo-municipalities", in -> bare
                        ? map("municipalities", List.of(), "count", 0, "main", null, "mainIbgeCode", null, "areaHa", 0.0, "edition", 2025)
                        : map("municipalities", List.of(
                                        map("ibgeCode", "5107065", "name", "Querência", "uf", "MT", "intersectionHa", 1240.1, "percentOfArea", 99.29),
                                        map("ibgeCode", "5106455", "name", "Nova Ubiratã", "uf", "MT", "intersectionHa", 8.87, "percentOfArea", 0.71)),
                                "count", 2, "main", map("ibgeCode", "5107065", "name", "Querência", "uf", "MT", "percentOfArea", 99.29),
                                "mainIbgeCode", 5107065, "areaHa", 1248.97, "edition", 2025)),
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
                        ? map("year", null, "crops", List.of(), "count", 0, "totalHarvestedHa", 0.0, "totalValueThousandBrl", 0.0, "truncated", false)
                        : map("year", 2025, "crops", List.of(
                                        map("product", "Soja (em grão)", "plantedHa", 310000.0, "harvestedHa", 310000.0, "quantity", 1104000.0,
                                                "quantityUnit", "Toneladas", "yieldKgHa", 3561.0, "valueThousandBrl", 2318400.0, "shareOfHarvestedPercent", 71.3),
                                        map("product", "Milho (em grão)", "plantedHa", 120000.0, "harvestedHa", 120000.0, "quantity", 744000.0,
                                                "quantityUnit", "Toneladas", "yieldKgHa", 6200.0, "valueThousandBrl", 669600.0, "shareOfHarvestedPercent", 27.6)),
                                "count", 2, "totalHarvestedHa", 434800.0, "totalValueThousandBrl", 2998000.0, "truncated", false)),
                stub("sathub-index-series", in -> indexSeries(bare)),
                stub("sathub-latest-image", in -> latestImage(bare)));
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
                        "collection", "sentinel-2-l2a", "collectionName", "Sentinel-2 L2A"),
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
                    new SatelliteHubIndexSeriesBlock(null), new SatelliteHubLatestImageBlock(null, null, null))
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
