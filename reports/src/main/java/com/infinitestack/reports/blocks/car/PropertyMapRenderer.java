package com.infinitestack.reports.blocks.car;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Draws the map of a CAR property as a PNG with plain Java2D: neighbours (gray outline), the
 * declared feature layers (fixed colors, semi-transparent) and the property outline on top, plus
 * north arrow, scale bar and corner coordinates.
 *
 * <p>Kept apart from {@link CarPropertyMapBlock} so it can be tested without a database. It needs
 * no tiles and no network, so the report stays self-contained. Text is optional: the IS host may
 * run a JRE without fonts, where any text call can throw; then the labels are skipped and the map
 * still renders.
 */
public class PropertyMapRenderer {

    /** Alpha of the layer fills, so overlapping layers and the property outline stay visible. */
    public static final float FILL_ALPHA = 0.55f;

    /** Fixed color per layer code ({@code car_agent_app_camada.codigo}). */
    public static final Map<String, Color> LAYER_COLORS = Map.of(
            "VEGETACAO_NATIVA", new Color(0x4CAF50),
            "RESERVA_LEGAL", new Color(0x1B5E20),
            "APPS", new Color(0x1E88E5),
            "AREA_CONSOLIDADA", new Color(0xD2B48C),
            "HIDROGRAFIA", new Color(0x81D4FA),
            "USO_RESTRITO", new Color(0xFB8C00),
            "SERVIDAO_ADMINISTRATIVA", new Color(0x9E9E9E),
            "AREA_POUSIO", new Color(0xFDD835));

    /** Color for a layer code that has no fixed color (a new layer in the source). */
    public static final Color OTHER_LAYER_COLOR = new Color(0xAB47BC);

    static final Color PROPERTY_COLOR = new Color(0xB71C1C);
    static final Color NEIGHBOUR_COLOR = new Color(0xBDBDBD);
    private static final Color TEXT_COLOR = new Color(0x424242);
    private static final double METERS_PER_DEGREE = 111_320.0;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final boolean drawText;

    public PropertyMapRenderer() {
        this(true);
    }

    /** @param drawText false renders the map without any label (what happens when fonts fail) */
    public PropertyMapRenderer(boolean drawText) {
        this.drawText = drawText;
    }

    /** One feature layer: its code, display name and GeoJSON geometries, drawn in list order. */
    public record Layer(String code, String name, List<String> geometries) {}

    /** What goes on the map; geometries are GeoJSON (Polygon, MultiPolygon or collections of them). */
    public record MapData(String property, List<Layer> layers, List<String> neighbours) {}

    /** One legend line, for a layer actually drawn. */
    public record LegendEntry(String layer, String layerName, String color) {}

    /** The PNG and its legend. */
    public record Result(byte[] png, List<LegendEntry> legend) {}

    /**
     * Equirectangular projection fitted to a bounding box: x is scaled by the cosine of the center
     * latitude so shapes are not stretched. The box is centered with a padding around it.
     */
    public record Viewport(int width, int height, double centerLon, double centerLat,
                           double cosLat, double pixelsPerDegree) {

        /** Fraction of the image left free around the property on each side. */
        static final double PADDING = 0.08;

        public static Viewport fit(double minLon, double minLat, double maxLon, double maxLat, int width, int height) {
            double centerLon = (minLon + maxLon) / 2;
            double centerLat = (minLat + maxLat) / 2;
            double cosLat = Math.max(0.01, Math.cos(Math.toRadians(centerLat)));
            // A degenerate box (a point or a line) still gets a ~100 m window.
            double spanX = Math.max((maxLon - minLon) * cosLat, 0.001);
            double spanY = Math.max(maxLat - minLat, 0.001);
            double usableW = width * (1 - 2 * PADDING);
            double usableH = height * (1 - 2 * PADDING);
            double scale = Math.min(usableW / spanX, usableH / spanY);
            return new Viewport(width, height, centerLon, centerLat, cosLat, scale);
        }

        public double x(double lon) {
            return width / 2.0 + (lon - centerLon) * cosLat * pixelsPerDegree;
        }

        public double y(double lat) {
            return height / 2.0 - (lat - centerLat) * pixelsPerDegree;
        }

        public double minLon() { return centerLon - width / 2.0 / (cosLat * pixelsPerDegree); }
        public double maxLon() { return centerLon + width / 2.0 / (cosLat * pixelsPerDegree); }
        public double minLat() { return centerLat - height / 2.0 / pixelsPerDegree; }
        public double maxLat() { return centerLat + height / 2.0 / pixelsPerDegree; }

        /** Degrees (of latitude, the finer axis) per pixel: the simplification tolerance that is invisible. */
        public double degreesPerPixel() {
            return 1 / pixelsPerDegree;
        }

        public double metersPerPixel() {
            return METERS_PER_DEGREE / pixelsPerDegree;
        }
    }

    public Result render(Viewport view, MapData data) {
        BufferedImage image = new BufferedImage(view.width(), view.height(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, view.width(), view.height());

            g.setColor(NEIGHBOUR_COLOR);
            g.setStroke(new BasicStroke(1f));
            for (String neighbour : data.neighbours()) {
                Path2D path = path(view, neighbour);
                if (path != null) g.draw(path);
            }

            List<LegendEntry> legend = new ArrayList<>();
            for (Layer layer : data.layers()) {
                Color color = LAYER_COLORS.getOrDefault(layer.code(), OTHER_LAYER_COLOR);
                Color fill = new Color(color.getRed(), color.getGreen(), color.getBlue(), Math.round(FILL_ALPHA * 255));
                boolean drawn = false;
                for (String geometry : layer.geometries()) {
                    Path2D path = path(view, geometry);
                    if (path == null) continue;
                    g.setColor(fill);
                    g.fill(path);
                    g.setColor(color);
                    g.setStroke(new BasicStroke(1f));
                    g.draw(path);
                    drawn = true;
                }
                if (drawn) legend.add(new LegendEntry(layer.code(), layer.name(), hex(color)));
            }

            Path2D property = path(view, data.property());
            if (property != null) {
                g.setColor(PROPERTY_COLOR);
                g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.draw(property);
            }

            drawNorthArrow(g, view);
            drawScaleBar(g, view);
            drawCornerCoordinates(g, view);
            return new Result(png(image), List.copyOf(legend));
        } finally {
            g.dispose();
        }
    }

    // --- geometry ---------------------------------------------------------------------------

    /** All rings of a GeoJSON geometry in one even-odd path (holes stay holes); null when empty. */
    static Path2D path(Viewport view, String geoJson) {
        if (geoJson == null || geoJson.isBlank()) return null;
        JsonNode node;
        try {
            node = JSON.readTree(geoJson);
        } catch (IOException e) {
            throw new IllegalArgumentException("invalid GeoJSON: " + e.getMessage(), e);
        }
        Path2D path = new Path2D.Double(Path2D.WIND_EVEN_ODD);
        boolean any = addGeometry(view, node, path);
        return any ? path : null;
    }

    private static boolean addGeometry(Viewport view, JsonNode node, Path2D path) {
        String type = node.path("type").asText();
        JsonNode coordinates = node.path("coordinates");
        boolean any = false;
        switch (type) {
            case "Polygon" -> any = addPolygon(view, coordinates, path);
            case "MultiPolygon" -> {
                for (JsonNode polygon : coordinates) any |= addPolygon(view, polygon, path);
            }
            case "GeometryCollection" -> {
                for (JsonNode member : node.path("geometries")) any |= addGeometry(view, member, path);
            }
            default -> { /* points and lines are not drawn: CAR layers are polygons */ }
        }
        return any;
    }

    private static boolean addPolygon(Viewport view, JsonNode rings, Path2D path) {
        boolean any = false;
        for (JsonNode ring : rings) {
            if (ring.size() < 3) continue;
            for (int i = 0; i < ring.size(); i++) {
                double x = view.x(ring.get(i).get(0).asDouble());
                double y = view.y(ring.get(i).get(1).asDouble());
                if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
            }
            path.closePath();
            any = true;
        }
        return any;
    }

    // --- decorations ------------------------------------------------------------------------

    private void drawNorthArrow(Graphics2D g, Viewport view) {
        int size = Math.max(18, Math.min(view.width(), view.height()) / 22);
        int cx = view.width() - 16 - size / 2;
        int top = 16 + size / 2;
        Path2D left = new Path2D.Double();
        left.moveTo(cx, top);
        left.lineTo(cx - size / 2.0, top + size * 1.4);
        left.lineTo(cx, top + size * 1.05);
        left.closePath();
        Path2D right = new Path2D.Double();
        right.moveTo(cx, top);
        right.lineTo(cx + size / 2.0, top + size * 1.4);
        right.lineTo(cx, top + size * 1.05);
        right.closePath();
        g.setStroke(new BasicStroke(1.2f));
        g.setColor(TEXT_COLOR);
        g.fill(left);
        g.setColor(Color.WHITE);
        g.fill(right);
        g.setColor(TEXT_COLOR);
        g.draw(left);
        g.draw(right);
        drawText(g, "N", cx, top - 4, size * 0.8f, Align.CENTER);
    }

    private void drawScaleBar(Graphics2D g, Viewport view) {
        double meters = niceDistance(view.metersPerPixel() * view.width() / 5);
        int length = (int) Math.round(meters / view.metersPerPixel());
        int x = 16;
        int y = view.height() - 18;
        int thickness = 6;
        int half = length / 2;
        g.setStroke(new BasicStroke(1f));
        g.setColor(TEXT_COLOR);
        g.fillRect(x, y - thickness, half, thickness);
        g.setColor(Color.WHITE);
        g.fillRect(x + half, y - thickness, length - half, thickness);
        g.setColor(TEXT_COLOR);
        g.drawRect(x, y - thickness, length, thickness);
        drawText(g, formatDistance(meters), x + length + 6, y, 12f, Align.LEFT);
    }

    private void drawCornerCoordinates(Graphics2D g, Viewport view) {
        drawText(g, formatCoordinate(view.maxLat(), view.minLon()), 6, 14, 10f, Align.LEFT);
        drawText(g, formatCoordinate(view.minLat(), view.maxLon()), view.width() - 6, view.height() - 6, 10f, Align.RIGHT);
    }

    /** 1, 2 or 5 times a power of ten, not above {@code meters}. */
    static double niceDistance(double meters) {
        double power = Math.pow(10, Math.floor(Math.log10(Math.max(meters, 1))));
        double fraction = meters / power;
        double nice = fraction >= 5 ? 5 : fraction >= 2 ? 2 : 1;
        return nice * power;
    }

    static String formatDistance(double meters) {
        return meters >= 1000
                ? String.format(Locale.ROOT, "%s km", trim(meters / 1000))
                : String.format(Locale.ROOT, "%s m", trim(meters));
    }

    private static String trim(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    static String formatCoordinate(double lat, double lon) {
        return String.format(Locale.ROOT, "%.5f°%s  %.5f°%s",
                Math.abs(lat), lat < 0 ? "S" : "N", Math.abs(lon), lon < 0 ? "W" : "E");
    }

    private enum Align { LEFT, CENTER, RIGHT }

    /**
     * Draws a label with a white halo. Any failure (no fonts, no fontconfig on the host) skips the
     * label: text is decoration and must never fail the map.
     */
    private void drawText(Graphics2D g, String text, int x, int y, float size, Align align) {
        if (!drawText) return;
        try {
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 1).deriveFont(size));
            FontMetrics metrics = g.getFontMetrics();
            int width = metrics.stringWidth(text);
            int left = switch (align) {
                case LEFT -> x;
                case CENTER -> x - width / 2;
                case RIGHT -> x - width;
            };
            g.setColor(new Color(255, 255, 255, 200));
            g.fillRect(left - 2, y - metrics.getAscent(), width + 4, metrics.getAscent() + metrics.getDescent());
            g.setColor(TEXT_COLOR);
            g.drawString(text, left, y);
        } catch (Throwable fontFailure) { // NOSONAR: font errors are Errors (UnsatisfiedLinkError, InternalError)
            // skip the label
        }
    }

    // --- output -----------------------------------------------------------------------------

    static String hex(Color color) {
        return String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
    }

    /** PNG at maximum compression: the image travels inside the execution context JSON. */
    static byte[] png(BufferedImage image) {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("png").next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(stream);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(0f);
            }
            writer.write(null, new IIOImage(image, null, null), param);
        } catch (IOException e) {
            throw new IllegalStateException("could not encode the map PNG", e);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    /** Legend as plain JSON values (the Block output). */
    static List<Map<String, Object>> legendJson(List<LegendEntry> legend) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (LegendEntry entry : legend) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("layer", entry.layer());
            item.put("layerName", entry.layerName());
            item.put("color", entry.color());
            out.add(item);
        }
        return out;
    }
}
