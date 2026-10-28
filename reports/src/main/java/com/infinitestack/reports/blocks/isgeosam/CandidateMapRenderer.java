package com.infinitestack.reports.blocks.isgeosam;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.List;

import javax.imageio.ImageIO;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.infinitestack.reports.blocks.sathub.imagery.Utm;

/**
 * Draws the field candidates over the true color image they were segmented from: each outline in
 * its own color, lightly filled, with its number at the center of its box. Kept apart from the Block
 * so it can be tested without a host.
 *
 * <p>The candidates come in EPSG:4326 and the image in imageCrs (a WGS 84 UTM zone for
 * sathub-latest-image); pixel = inverse of imageTransform. Text is optional: a JRE without fonts
 * throws on any text call, and the map renders without numbers (as {@code PropertyMapRenderer}).
 */
public class CandidateMapRenderer {

    static final Color[] PALETTE = {new Color(0xFFEB3B), new Color(0x00E5FF), new Color(0xFF4081), new Color(0x76FF03),
            new Color(0xFF9100), new Color(0xE040FB), new Color(0x18FFFF), new Color(0xFFFFFF)};
    static final float FILL_ALPHA = 0.22f;

    private final ObjectMapper json = new ObjectMapper();

    /** One candidate to draw: its number in the report table and its GeoJSON in lon/lat. */
    public record Shape(int number, String geometry) {}

    /**
     * @return the PNG as a data URI, or null when the image or its georeference cannot be used (the
     *         report then shows the candidates' table only)
     */
    public String render(String trueColorDataUri, String imageCrs, List<?> imageTransform, List<Shape> shapes) {
        BufferedImage base = decode(trueColorDataUri);
        Integer epsg = epsg(imageCrs);
        if (base == null || epsg == null || imageTransform == null || imageTransform.size() != 6) return null;
        if (epsg != 4326 && !Utm.isUtm(epsg)) return null;
        double[] t = new double[6];
        for (int i = 0; i < 6; i++) {
            if (!(imageTransform.get(i) instanceof Number n)) return null;
            t[i] = n.doubleValue();
        }
        if (t[1] != 0 || t[3] != 0 || t[0] == 0 || t[4] == 0) return null;   // rotated grids are not drawn

        BufferedImage map = new BufferedImage(base.getWidth(), base.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = map.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(base, 0, 0, null);
            float stroke = Math.max(1.5f, Math.max(base.getWidth(), base.getHeight()) / 400f);
            for (Shape shape : shapes) {
                Path2D path = path(shape.geometry(), epsg, t);
                if (path == null) continue;
                Color color = PALETTE[(shape.number() - 1) % PALETTE.length];
                g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, FILL_ALPHA));
                g.setColor(color);
                g.fill(path);
                g.setComposite(AlphaComposite.SrcOver);
                g.setStroke(new BasicStroke(stroke));
                g.draw(path);
                label(g, String.valueOf(shape.number()), path.getBounds2D(), color, base.getWidth());
            }
        } finally {
            g.dispose();
        }
        return encode(map);
    }

    /** The polygon rings in pixels; null for a geometry that cannot be read. */
    private Path2D path(String geoJson, int epsg, double[] t) {
        JsonNode geometry;
        try {
            geometry = json.readTree(geoJson);
        } catch (Exception e) {
            return null;
        }
        JsonNode polygons = "Polygon".equals(geometry.path("type").asText())
                ? json.createArrayNode().add(geometry.path("coordinates")) : geometry.path("coordinates");
        Path2D path = new Path2D.Double(Path2D.WIND_EVEN_ODD);
        for (JsonNode polygon : polygons) {
            for (JsonNode ring : polygon) {
                boolean first = true;
                for (JsonNode point : ring) {
                    double lon = point.path(0).asDouble();
                    double lat = point.path(1).asDouble();
                    double[] xy = epsg == 4326 ? new double[] {lon, lat} : Utm.forward(lon, lat, epsg);
                    double col = (xy[0] - t[2]) / t[0];
                    double row = (xy[1] - t[5]) / t[4];
                    if (first) path.moveTo(col, row);
                    else path.lineTo(col, row);
                    first = false;
                }
                path.closePath();
            }
        }
        return path;
    }

    private static void label(Graphics2D g, String text, Rectangle2D box, Color color, int imageWidth) {
        try {
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 1).deriveFont(Math.max(11f, imageWidth / 60f)));
            FontMetrics metrics = g.getFontMetrics();
            float x = (float) (box.getCenterX() - metrics.stringWidth(text) / 2.0);
            float y = (float) (box.getCenterY() + metrics.getAscent() / 2.0 - 1);
            g.setColor(new Color(0, 0, 0, 170));
            g.drawString(text, x + 1, y + 1);
            g.setColor(color);
            g.drawString(text, x, y);
        } catch (Throwable fontFailure) { // NOSONAR: font errors are Errors (UnsatisfiedLinkError, InternalError)
            // no fonts on the host: the outlines are still drawn, the table keeps the numbers
        }
    }

    static Integer epsg(String crs) {
        if (crs == null || !crs.toUpperCase().startsWith("EPSG:")) return null;
        try {
            return Integer.valueOf(crs.substring(5).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static BufferedImage decode(String dataUri) {
        if (dataUri == null) return null;
        int comma = dataUri.indexOf(',');
        if (!dataUri.startsWith("data:image/png;base64") || comma < 0) return null;
        try {
            return ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(dataUri.substring(comma + 1))));
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
    }

    private static String encode(BufferedImage image) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", out);
        } catch (IOException e) {
            return null;
        }
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
    }
}
