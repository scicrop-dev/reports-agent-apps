package com.infinitestack.reports.blocks.sathub.imagery;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.infinitestack.reports.blocks.car.PropertyMapRenderer;

/**
 * Draws the PNGs of the latest-image Block, sized as the grid: true color (red/green/blue reflectance
 * with a percentile stretch over the area, gamma), and one index map per index (palette over
 * [min, max], clouds and nodata in grey, outside the area dimmed). Both draw the area outline. PNG
 * encoding uses {@code PropertyMapRenderer.png}. No text is drawn (the template draws the legend).
 *
 * <p>Design choices:
 * <ul>
 *   <li>True color: one joint stretch for the three channels (keeps the hue; a per-channel stretch
 *       tints scenes), from the 2nd to the 98th percentile of the clear pixels inside the area
 *       (fallback: all area pixels, then the whole grid), then gamma 1/1.4 so a dark forest is not
 *       black. Clouds are left as they are: in true color a cloud reads as a cloud.</li>
 *   <li>Index map: clouds are white with a light grey diagonal hatch, nodata is flat grey, so the
 *       two are told apart without a legend entry for each.</li>
 *   <li>Pixels are written one to one (no smoothing, no upscaling); only the outline is
 *       anti-aliased.</li>
 * </ul>
 */
public class ImageryRenderer {

    static final int NODATA_RGB = 0xd0d0d0;
    static final int CLOUD_RGB = 0xffffff;
    static final int HATCH_RGB = 0xc4c4c4;
    static final int HATCH_PERIOD = 6;
    static final double TRUE_COLOR_GAMMA = 1.4;
    static final double TRUE_COLOR_DIM = 0.35;
    static final double INDEX_DIM = 0.70;
    static final Color TRUE_COLOR_OUTLINE = new Color(0xffd400);
    static final Color TRUE_COLOR_HALO = new Color(0, 0, 0, 140);
    static final Color INDEX_OUTLINE = new Color(0x222222);
    static final Color INDEX_HALO = new Color(255, 255, 255, 220);

    /**
     * @param red   reflectance (0..1, NaN = nodata) on the grid, same for green and blue
     * @param clear pixels without cloud/shadow (from SCL); null = all clear
     */
    public byte[] trueColor(float[] red, float[] green, float[] blue, boolean[] clear, PolygonMask area) {
        RasterGrid grid = area.grid();
        boolean[] inside = area.inside();
        double[] range = stretchRange(red, green, blue, clear, inside);
        double lo = range[0];
        double span = Math.max(1e-6, range[1] - range[0]);
        BufferedImage image = new BufferedImage(grid.width(), grid.height(), BufferedImage.TYPE_INT_RGB);
        int[] rgb = new int[grid.width() * grid.height()];
        for (int i = 0; i < rgb.length; i++) {
            int color;
            if (Float.isNaN(red[i]) || Float.isNaN(green[i]) || Float.isNaN(blue[i])) {
                color = NODATA_RGB;
            } else {
                color = (channel(red[i], lo, span) << 16) | (channel(green[i], lo, span) << 8) | channel(blue[i], lo, span);
            }
            rgb[i] = inside[i] ? color : Palette.lerp(color, 0xffffff, TRUE_COLOR_DIM);
        }
        image.setRGB(0, 0, grid.width(), grid.height(), rgb, 0, grid.width());
        drawOutline(image, area, TRUE_COLOR_OUTLINE, TRUE_COLOR_HALO);
        return PropertyMapRenderer.png(image);
    }

    /**
     * @param values index values on the grid (NaN = no value)
     * @param clear  pixels without cloud/shadow; null = all clear
     */
    public byte[] indexMap(float[] values, boolean[] clear, PolygonMask area, Palette palette, double min, double max) {
        RasterGrid grid = area.grid();
        boolean[] inside = area.inside();
        double span = max - min;
        BufferedImage image = new BufferedImage(grid.width(), grid.height(), BufferedImage.TYPE_INT_RGB);
        int[] rgb = new int[grid.width() * grid.height()];
        for (int row = 0; row < grid.height(); row++) {
            for (int col = 0; col < grid.width(); col++) {
                int i = row * grid.width() + col;
                int color;
                if (clear != null && !clear[i]) {
                    color = (col + row) % HATCH_PERIOD == 0 ? HATCH_RGB : CLOUD_RGB;
                } else if (Float.isNaN(values[i])) {
                    color = NODATA_RGB;
                } else {
                    color = palette.colorAt(span > 0 ? (values[i] - min) / span : 0.5);
                }
                rgb[i] = inside[i] ? color : Palette.lerp(color, 0xffffff, INDEX_DIM);
            }
        }
        image.setRGB(0, 0, grid.width(), grid.height(), rgb, 0, grid.width());
        drawOutline(image, area, INDEX_OUTLINE, INDEX_HALO);
        return PropertyMapRenderer.png(image);
    }

    /**
     * Legend entries for the template: {@code steps} evenly spaced values from {@code min} to
     * {@code max} (both included), each {@code {color: "#rrggbb", value: number, label: "0.25"}}.
     */
    public static List<Map<String, Object>> legend(Palette palette, double min, double max, int steps) {
        int n = Math.max(2, steps);
        List<Map<String, Object>> out = new ArrayList<>();
        for (int k = 0; k < n; k++) {
            double t = (double) k / (n - 1);
            double value = min + (max - min) * t;
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("color", String.format("#%06x", palette.colorAt(t)));
            entry.put("value", value);
            entry.put("label", String.format(Locale.ROOT, "%.2f", value + 0.0)); // + 0.0 turns -0.0 into 0.0
            out.add(entry);
        }
        return out;
    }

    /** {@code data:image/png;base64,...} for an {@code <img src>} in the report HTML. */
    public static String dataUri(byte[] png) {
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(png);
    }

    // --- true color stretch -----------------------------------------------------------------

    /** 2nd and 98th percentiles of the three channels together, over the best available pixel set. */
    static double[] stretchRange(float[] red, float[] green, float[] blue, boolean[] clear, boolean[] inside) {
        double[] samples = samples(red, green, blue, clear, inside, true);
        if (samples.length == 0) samples = samples(red, green, blue, null, inside, true);
        if (samples.length == 0) samples = samples(red, green, blue, null, inside, false);
        if (samples.length == 0) return new double[] {0, 0.3};
        Arrays.sort(samples);
        double lo = IndexStats.percentile(samples, 0.02);
        double hi = IndexStats.percentile(samples, 0.98);
        if (hi - lo < 1e-4) hi = lo + 1e-4; // flat scene: avoid a division by ~0
        return new double[] {lo, hi};
    }

    private static double[] samples(float[] red, float[] green, float[] blue, boolean[] clear, boolean[] inside,
                                    boolean insideOnly) {
        double[] out = new double[red.length * 3];
        int n = 0;
        for (int i = 0; i < red.length; i++) {
            if (insideOnly && !inside[i]) continue;
            if (clear != null && !clear[i]) continue;
            if (Float.isNaN(red[i]) || Float.isNaN(green[i]) || Float.isNaN(blue[i])) continue;
            out[n++] = red[i];
            out[n++] = green[i];
            out[n++] = blue[i];
        }
        return Arrays.copyOf(out, n);
    }

    private static int channel(float value, double lo, double span) {
        double s = Math.max(0, Math.min(1, (value - lo) / span));
        return (int) Math.round(255 * Math.pow(s, 1 / TRUE_COLOR_GAMMA));
    }

    // --- outline ----------------------------------------------------------------------------

    /** 2 px line over a 4 px halo, anti-aliased; rings are in pixel coordinates already. */
    private static void drawOutline(BufferedImage image, PolygonMask area, Color line, Color halo) {
        Path2D path = new Path2D.Double(Path2D.WIND_EVEN_ODD);
        for (double[][] ring : area.pixelRings()) {
            if (ring.length < 2) continue;
            path.moveTo(ring[0][0], ring[0][1]);
            for (int i = 1; i < ring.length; i++) path.lineTo(ring[i][0], ring[i][1]);
            path.closePath();
        }
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setStroke(new BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(halo);
            g.draw(path);
            g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(line);
            g.draw(path);
        } finally {
            g.dispose();
        }
    }
}
