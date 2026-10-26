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

import com.infinitestack.reports.engine.DataType;
import com.infinitestack.reports.engine.block.BlockDefinition;
import com.infinitestack.reports.engine.block.BlockPort;
import com.infinitestack.reports.engine.block.ExecutionMetadata;
import com.infinitestack.reports.engine.block.ReportBlock;

/**
 * TEST DOUBLES of the CAR Blocks: same ids, ports and types as the contracts in docs/BLOCKS.md
 * (section 2), with canned, realistic outputs and no database. They exist so the ready-made
 * reports can be validated and rendered before the real Blocks are merged. They do not declare
 * DataRequirements (the gate is not what these tests check). When a contract in BLOCKS.md
 * changes, change it here too; once the real Blocks exist, the ready-made reports must also be
 * validated against them.
 */
public final class CarBlockContractStubs {

    public static final String COD_IMOVEL = "MT-5107925-1A2B3C4D5E6F708192A3B4C5D6E7F801";

    private CarBlockContractStubs() {}

    /** @param bare true for a property with no layer, no legal reserve, no overlap and one version */
    public static List<ReportBlock> all(boolean bare) {
        return List.of(new Property(bare), new Geometry(), new EnvironmentalLayers(bare), new Overlaps(bare),
                new History(bare), new PropertyMap(bare));
    }

    private static BlockPort in(String name, DataType type) { return BlockPort.required(name, type, name); }
    private static BlockPort opt(String name, DataType type) { return BlockPort.optional(name, type, name); }
    private static BlockPort out(String name, DataType type) { return BlockPort.required(name, type, name); }

    private static Map<String, Object> map(Object... keyValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) m.put((String) keyValues[i], keyValues[i + 1]);
        return m;
    }

    private abstract static class Stub implements ReportBlock {
        private final BlockDefinition definition;

        Stub(String id, String name, List<BlockPort> inputs, List<BlockPort> outputs) {
            this.definition = new BlockDefinition(id, name, name + " (contract test double)", "0.0.0-stub",
                    inputs, outputs, List.of(), null);
        }

        @Override public BlockDefinition definition() { return definition; }
    }

    /** car-property */
    public static final class Property extends Stub {
        private final boolean bare;

        Property(boolean bare) {
            super("car-property", "CAR property", List.of(in("codImovel", DataType.STRING)), List.of(
                    out("codImovel", DataType.STRING), out("uf", DataType.STRING), out("municipality", DataType.STRING),
                    out("municipalityIbge", DataType.NUMBER), out("status", DataType.STRING), out("statusLabel", DataType.STRING),
                    out("type", DataType.STRING), out("typeLabel", DataType.STRING), out("condition", DataType.STRING),
                    out("fiscalModules", DataType.NUMBER), out("declaredAreaHa", DataType.NUMBER),
                    out("registeredAt", DataType.DATE), out("updatedAt", DataType.DATE), out("versionSince", DataType.DATE),
                    opt("removedAt", DataType.DATE)));
            this.bare = bare;
        }

        @Override public Map<String, Object> execute(Map<String, Object> inputs, ExecutionMetadata metadata) {
            Map<String, Object> out = map("codImovel", inputs.get("codImovel"), "uf", "MT", "municipality", "Querência",
                    "municipalityIbge", 5107065, "status", "AT", "statusLabel", "Ativo", "type", "IRU", "typeLabel", "Imóvel Rural",
                    "condition", "Aguardando análise", "fiscalModules", 12.5, "declaredAreaHa", 1250.4321,
                    "registeredAt", "2015-03-12", "updatedAt", "2023-08-30", "versionSince", "2023-09-02");
            out.put("removedAt", bare ? null : "2024-05-10");
            return out;
        }
    }

    /** car-property-geometry */
    public static final class Geometry extends Stub {
        Geometry() {
            super("car-property-geometry", "CAR property geometry", List.of(in("codImovel", DataType.STRING)), List.of(
                    out("geometry", DataType.GEOMETRY), out("areaHa", DataType.NUMBER), out("centroidLat", DataType.NUMBER),
                    out("centroidLon", DataType.NUMBER), out("bbox", DataType.OBJECT), out("vertices", DataType.NUMBER)));
        }

        @Override public Map<String, Object> execute(Map<String, Object> inputs, ExecutionMetadata metadata) {
            Map<String, Object> polygon = map("type", "Polygon", "coordinates", List.of(List.of(
                    List.of(-52.21, -12.61), List.of(-52.17, -12.61), List.of(-52.17, -12.64), List.of(-52.21, -12.64), List.of(-52.21, -12.61))));
            return map("geometry", polygon, "areaHa", 1248.97, "centroidLat", -12.625314, "centroidLon", -52.190221,
                    "bbox", map("minLon", -52.21, "minLat", -12.64, "maxLon", -52.17, "maxLat", -12.61), "vertices", 5);
        }
    }

    /** car-environmental-layers */
    public static final class EnvironmentalLayers extends Stub {
        private final boolean bare;

        EnvironmentalLayers(boolean bare) {
            super("car-environmental-layers", "CAR environmental layers", List.of(in("codImovel", DataType.STRING)), List.of(
                    out("layers", DataType.ARRAY), out("summary", DataType.OBJECT), out("hasLegalReserve", DataType.BOOLEAN)));
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
                    "appPercent", bare ? 0 : 7.05, "legalReservePercent", bare ? 0 : 20.09, "nativeVegetationPercent", bare ? 0 : 51.24);
            return map("layers", layers, "summary", summary, "hasLegalReserve", !bare);
        }

        private static Map<String, Object> layer(String layer, String name, double area, double percent, Object... themes) {
            return map("layer", layer, "layerName", name, "declaredAreaHa", area, "percentOfProperty", percent, "themes", List.of(themes));
        }

        private static Map<String, Object> theme(String theme, String name, int count, double area) {
            return map("theme", theme, "themeName", name, "count", count, "declaredAreaHa", area);
        }
    }

    /** car-overlaps */
    public static final class Overlaps extends Stub {
        private final boolean bare;

        Overlaps(boolean bare) {
            super("car-overlaps", "CAR overlaps", List.of(in("codImovel", DataType.STRING), opt("minOverlapHa", DataType.NUMBER)), List.of(
                    out("overlaps", DataType.ARRAY), out("count", DataType.NUMBER), out("totalOverlapHa", DataType.NUMBER),
                    out("truncated", DataType.BOOLEAN)));
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
            super("car-history", "CAR history", List.of(in("codImovel", DataType.STRING)), List.of(
                    out("versions", DataType.ARRAY), out("count", DataType.NUMBER)));
            this.bare = bare;
        }

        @Override public Map<String, Object> execute(Map<String, Object> inputs, ExecutionMetadata metadata) {
            List<Object> versions = new ArrayList<>();
            if (!bare) {
                versions.add(version("2015-03-12", "2019-06-01", "Pendente", "Aguardando análise", 1262.0, false));
                versions.add(version("2019-06-01", "2023-09-02", "Ativo", "Aguardando análise", 1250.4321, true));
            }
            versions.add(version("2023-09-02", null, "Ativo", "Aguardando análise", 1250.4321, false));
            return map("versions", versions, "count", versions.size());
        }

        private static Map<String, Object> version(String from, String to, String statusLabel, String condition,
                                                   double area, boolean geometryChanged) {
            Map<String, Object> v = new HashMap<>(map("validFrom", from, "status", "AT", "statusLabel", statusLabel,
                    "condition", condition, "declaredAreaHa", area, "geometryChanged", geometryChanged));
            v.put("validTo", to);
            return v;
        }
    }

    /** car-property-map: draws a real PNG, so the PDF test embeds an image of a realistic size. */
    public static final class PropertyMap extends Stub {
        private final boolean bare;

        PropertyMap(boolean bare) {
            super("car-property-map", "CAR property map", List.of(in("codImovel", DataType.STRING),
                    opt("width", DataType.NUMBER), opt("height", DataType.NUMBER)), List.of(
                    out("image", DataType.STRING), out("legend", DataType.ARRAY)));
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
