package com.infinitestack.reports.support;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import com.infinitestack.reports.blocks.car.CarEnvironmentalLayersBlock;
import com.infinitestack.reports.blocks.car.CarHistoryBlock;
import com.infinitestack.reports.blocks.car.CarOverlapsBlock;
import com.infinitestack.reports.blocks.car.CarPropertyBlock;
import com.infinitestack.reports.blocks.car.CarPropertyGeometryBlock;
import com.infinitestack.reports.blocks.car.CarPropertyMapBlock;
import com.infinitestack.reports.blocks.car.ReportAreaBlock;
import com.infinitestack.reports.engine.block.BlockDefinition;
import com.infinitestack.reports.engine.block.ExecutionMetadata;
import com.infinitestack.reports.engine.block.ReportBlock;

/**
 * TEST DOUBLES of the CAR Blocks: the real Blocks' definitions (ids, ports, types) with canned,
 * realistic outputs instead of database queries, so the ready-made reports can be executed and
 * rendered to HTML and PDF without a database. The canned outputs must follow docs/BLOCKS.md
 * (section 2); the engine checks their types against the real ports when they run.
 */
public final class CarBlockContractStubs {

    public static final String COD_IMOVEL = "MT-5107925-1A2B3C4D5E6F708192A3B4C5D6E7F801";

    private CarBlockContractStubs() {}

    /** @param bare true for a property with no layer, no legal reserve, no overlap and one version */
    public static List<ReportBlock> all(boolean bare) {
        return List.of(new Property(bare), new Geometry(), new EnvironmentalLayers(bare), new Overlaps(bare),
                new History(bare), new PropertyMap(bare), new ReportArea());
    }


    private static Map<String, Object> map(Object... keyValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) m.put((String) keyValues[i], keyValues[i + 1]);
        return m;
    }

    /** The real definitions, so a stub can never drift from the Block it stands for. */
    private static final Map<String, BlockDefinition> REAL = java.util.stream.Stream.of(
                    new CarPropertyBlock(null), new CarPropertyGeometryBlock(null), new CarEnvironmentalLayersBlock(null),
                    new CarOverlapsBlock(null), new CarHistoryBlock(null), new CarPropertyMapBlock(null), new ReportAreaBlock(null, null))
            .map(ReportBlock::definition)
            .collect(java.util.stream.Collectors.toMap(BlockDefinition::id, d -> d));

    private abstract static class Stub implements ReportBlock {
        private final BlockDefinition definition;

        Stub(String id) {
            this.definition = REAL.get(id);
        }

        @Override public BlockDefinition definition() { return definition; }
    }

    /** car-property */
    public static final class Property extends Stub {
        private final boolean bare;

        Property(boolean bare) {
            super("car-property");
            this.bare = bare;
        }

        @Override public Map<String, Object> execute(Map<String, Object> inputs, ExecutionMetadata metadata) {
            Map<String, Object> out = map("codImovel", inputs.get("codImovel"), "uf", "MT", "municipality", "Querência",
                    "municipalityIbge", 5107065, "status", "AT", "statusLabel", "Ativo", "type", "IRU", "typeLabel", "Imóvel Rural",
                    "condition", "Aguardando análise", "fiscalModules", 12.5, "declaredAreaHa", 1250.4321,
                    "registeredAt", "2015-03-12", "updatedAt", "2023-08-30", "versionSince", "2023-09-02");
            out.put("removedAt", bare ? null : "2024-05-10");
            out.put("removed", !bare);
            out.put("calculatedAreaHa", 1248.97);
            out.put("areaDifferencePct", 0.11);
            out.put("areaMismatch", false);
            return out;
        }
    }

    /** car-property-geometry */
    public static final class Geometry extends Stub {
        Geometry() {
            super("car-property-geometry");
        }

        @Override public Map<String, Object> execute(Map<String, Object> inputs, ExecutionMetadata metadata) {
            Map<String, Object> polygon = map("type", "Polygon", "coordinates", List.of(List.of(
                    List.of(-52.21, -12.61), List.of(-52.17, -12.61), List.of(-52.17, -12.64), List.of(-52.21, -12.64), List.of(-52.21, -12.61))));
            return map("geometry", polygon, "areaHa", 1248.97, "centroidLat", -12.625314, "centroidLon", -52.190221,
                    "bbox", map("minLon", -52.21, "minLat", -12.64, "maxLon", -52.17, "maxLat", -12.61), "vertices", 5);
        }
    }

    /** report-area: the CAR geometry for a code, the polygon itself, or a GIS dataset; fails like the Block on more than one or none. */
    public static final class ReportArea extends Stub {
        ReportArea() {
            super("report-area");
        }

        @Override public Map<String, Object> execute(Map<String, Object> inputs, ExecutionMetadata metadata) {
            Object code = inputs.get("codImovel");
            Object polygon = inputs.get("geometry");
            Object gis = inputs.get("gisDataset");
            int given = (code != null ? 1 : 0) + (polygon != null ? 1 : 0) + (gis != null ? 1 : 0);
            if (given != 1) throw new IllegalArgumentException("give a CAR code, a polygon or a GIS dataset");
            Map<String, Object> out = new Geometry().execute(inputs, metadata);
            if (polygon != null) out.put("geometry", polygon);
            out.put("source", code != null ? "car" : gis != null ? "gis" : "polygon");
            out.put("isCar", code != null);
            out.put("isGis", gis != null);
            out.put("codImovel", code);
            out.put("label", code != null ? code : gis != null ? "Talhões " + gis : inputs.getOrDefault("name", "Área informada"));
            out.put("gisDatasetId", gis != null ? 1 : null);
            out.put("gisDatasetName", gis != null ? "Talhões " + gis : null);
            out.put("gisFeatureId", inputs.get("gisFeature"));
            out.put("gisPolygons", gis != null ? 3 : null);
            return out;
        }
    }

    /** car-environmental-layers */
    public static final class EnvironmentalLayers extends Stub {
        private final boolean bare;

        EnvironmentalLayers(boolean bare) {
            super("car-environmental-layers");
            this.bare = bare;
        }

        @Override public Map<String, Object> execute(Map<String, Object> inputs, ExecutionMetadata metadata) {
            List<Object> layers = bare ? List.of() : List.of(
                    layer("RESERVA_LEGAL", "Reserva Legal", 251.2, 20.09, theme("ARL_TOTAL", "Reserva Legal total", 2, 251.2)),
                    layer("VEGETACAO_NATIVA", "Vegetação Nativa", 640.75, 51.24, theme("VEG_NATIVA", "Remanescente de vegetação nativa", 3, 640.75)),
                    layer("APPS", "Áreas de Preservação Permanente", 88.1, 7.05,
                            theme("APP_TOTAL", "APP total", 4, 88.1)),
                    layer("AREA_CONSOLIDADA", "Área Consolidada", 512.3, 40.97, theme("AREA_CONSOLIDADA", "Área consolidada", 1, 512.3)),
                    layer("HIDROGRAFIA", "Hidrografia", 12.4, 0.99,
                            theme("RIO_ATE_10", "Curso d'água natural de até 10 metros", 2, 9.1), theme("NASCENTE", "Nascente ou olho d'água perene", 1, 3.3)));
            double legalReserve = bare ? 0 : 251.2;
            Map<String, Object> summary = map("propertyAreaHa", 1250.4321, "appHa", bare ? 0 : 88.1, "legalReserveHa", legalReserve,
                    "nativeVegetationHa", bare ? 0 : 640.75, "consolidatedHa", bare ? 0 : 512.3, "fallowHa", 0,
                    "hydrographyHa", bare ? 0 : 12.4, "restrictedUseHa", 0, "administrativeEasementHa", 0,
                    "appPercent", bare ? 0 : 7.05, "legalReservePercent", bare ? 0 : 20.09, "nativeVegetationPercent", bare ? 0 : 51.24,
                    "hydrographyCalculated", !bare);
            return map("layers", layers, "summary", summary, "hasLegalReserve", !bare);
        }

        private static Map<String, Object> layer(String layer, String name, double area, double percent, Object... themes) {
            String source = "HIDROGRAFIA".equals(layer) ? "calculated" : "declared";
            return map("layer", layer, "layerName", name, "declaredAreaHa", area, "areaSource", source,
                    "percentOfProperty", percent, "themes", List.of(themes));
        }

        private static Map<String, Object> theme(String theme, String name, int count, double area) {
            String source = theme.startsWith("RIO") || theme.equals("NASCENTE") ? "calculated" : "declared";
            return map("theme", theme, "themeName", name, "count", count, "declaredAreaHa", area, "areaSource", source);
        }
    }

    /** car-overlaps */
    public static final class Overlaps extends Stub {
        private final boolean bare;

        Overlaps(boolean bare) {
            super("car-overlaps");
            this.bare = bare;
        }

        @Override public Map<String, Object> execute(Map<String, Object> inputs, ExecutionMetadata metadata) {
            List<Object> overlaps = bare ? List.of() : List.of(
                    map("codImovel", "MT-5107065-0F1E2D3C4B5A69788796A5B4C3D2E1F0", "status", "AT", "statusLabel", "Ativo",
                            "overlapHa", 35.21, "percentOfProperty", 2.82),
                    map("codImovel", "MT-5107065-99AA88BB77CC66DD55EE44FF33001122", "status", "PE", "statusLabel", "Pendente",
                            "overlapHa", 0.87, "percentOfProperty", 0.07));
            return map("overlaps", overlaps, "count", overlaps.size(), "totalOverlapHa", bare ? 0 : 36.08, "truncated", false);
        }
    }

    /** car-history */
    public static final class History extends Stub {
        private final boolean bare;

        History(boolean bare) {
            super("car-history");
            this.bare = bare;
        }

        @Override public Map<String, Object> execute(Map<String, Object> inputs, ExecutionMetadata metadata) {
            List<Object> versions = new ArrayList<>();
            if (!bare) {
                versions.add(version("2015-03-12", "2019-06-01", "Pendente", "Aguardando análise", 1262.0, 1260.12, null, null, false));
                versions.add(version("2019-06-01", "2023-09-02", "Ativo", "Aguardando análise", 1250.4321, 1248.97, -11.15, -0.88, true));
            }
            versions.add(version("2023-09-02", null, "Ativo", "Aguardando análise", 1250.4321, 1248.97, bare ? null : 0.0,
                    bare ? null : 0.0, false));
            Map<String, Object> out = map("versions", versions, "count", versions.size());
            out.put("removedAt", null);
            return out;
        }

        private static Map<String, Object> version(String from, String to, String statusLabel, String condition,
                                                   double area, double calculated, Double changeHa, Double changePct,
                                                   boolean geometryChanged) {
            Map<String, Object> v = new HashMap<>(map("validFrom", from, "status", "AT", "statusLabel", statusLabel,
                    "condition", condition, "declaredAreaHa", area, "calculatedAreaHa", calculated,
                    "geometryChanged", geometryChanged));
            v.put("validTo", to);
            v.put("areaChangeHa", changeHa);
            v.put("areaChangePct", changePct);
            return v;
        }
    }

    /** car-property-map: draws a real PNG, so the PDF test embeds an image of a realistic size. */
    public static final class PropertyMap extends Stub {
        private final boolean bare;

        PropertyMap(boolean bare) {
            super("car-property-map");
            this.bare = bare;
        }

        @Override public Map<String, Object> execute(Map<String, Object> inputs, ExecutionMetadata metadata) {
            BufferedImage image = new BufferedImage(1000, 700, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = image.createGraphics();
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, 1000, 700);
            g.setColor(new Color(160, 160, 160));
            g.drawRect(40, 60, 260, 300);
            g.setColor(new Color(27, 120, 55, 120));
            g.fillRect(320, 140, 200, 260);
            g.setColor(new Color(17, 24, 39));
            g.setStroke(new BasicStroke(3));
            g.drawRect(300, 100, 520, 480);
            g.dispose();
            List<Object> legend = bare ? List.of() : List.of(
                    map("layer", "RESERVA_LEGAL", "layerName", "Reserva Legal", "color", "#1b7837"),
                    map("layer", "APPS", "layerName", "Áreas de Preservação Permanente", "color", "#2166ac"));
            return map("image", "data:image/png;base64," + Base64.getEncoder().encodeToString(png(image)), "legend", legend);
        }

        private static byte[] png(BufferedImage image) {
            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                ImageIO.write(image, "png", out);
                return out.toByteArray();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
