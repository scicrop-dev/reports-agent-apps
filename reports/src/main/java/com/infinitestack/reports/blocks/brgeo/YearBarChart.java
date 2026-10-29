package com.infinitestack.reports.blocks.brgeo;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Locale;

import com.infinitestack.reports.blocks.car.PropertyMapRenderer;

/**
 * Draws one value per year as bars (a PAM series) as a PNG for the PDF, with a horizontal grid and the
 * years under the bars. A year without value leaves a gap. Like the other charts, every label is
 * optional: a host JRE without fonts gets the chart without text.
 */
public class YearBarChart {

    /** One year; {@code value} null when there is no data. */
    public record Bar(int year, Double value) {}

    private static final Color BAR = new Color(0x2E7D32);
    private static final Color GRID = new Color(0xE0E0E0);
    private static final Color AXIS = new Color(0x9E9E9E);
    private static final Color TEXT = new Color(0x424242);

    private static final int LEFT = 64;
    private static final int RIGHT = 16;
    private static final int TOP = 14;
    private static final int BOTTOM = 30;

    private final boolean drawText;

    public YearBarChart() {
        this(true);
    }

    YearBarChart(boolean drawText) {
        this.drawText = drawText;
    }

    public byte[] render(List<Bar> bars, int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, width, height);
            int plotW = width - LEFT - RIGHT;
            int plotH = height - TOP - BOTTOM;
            if (bars.isEmpty()) {
                text(g, "no data", width / 2, height / 2, true);
                return PropertyMapRenderer.png(image);
            }

            double hi = bars.stream().filter(b -> b.value() != null).mapToDouble(Bar::value).max().orElse(1);
            double step = niceStep(Math.max(hi, 1e-9) / 5);
            hi = Math.max(step, Math.ceil(hi / step) * step);
            int first = bars.get(0).year();
            int years = bars.get(bars.size() - 1).year() - first + 1;
            double slot = plotW / (double) years;

            g.setStroke(new BasicStroke(1f));
            for (double v = 0; v <= hi + step / 2; v += step) {
                int y = TOP + (int) Math.round(plotH * (hi - v) / hi);
                g.setColor(GRID);
                g.drawLine(LEFT, y, LEFT + plotW, y);
                text(g, label(v, step), LEFT - 6, y + 4, false);
            }

            g.setColor(BAR);
            for (Bar bar : bars) {
                if (bar.value() == null || bar.value() <= 0) continue;
                double x = LEFT + slot * (bar.year() - first);
                int h = (int) Math.round(plotH * bar.value() / hi);
                int w = Math.max(1, (int) Math.floor(slot * 0.7));
                g.fillRect((int) Math.round(x + slot * 0.15), TOP + plotH - h, w, h);
            }

            g.setColor(AXIS);
            g.drawLine(LEFT, TOP + plotH, LEFT + plotW, TOP + plotH);
            int every = years <= 15 ? 1 : years <= 30 ? 2 : 5;
            for (int i = 0; i < years; i += every) {
                int x = LEFT + (int) Math.round(slot * (i + 0.5));
                text(g, String.valueOf(first + i), x, TOP + plotH + 18, true);
            }
            return PropertyMapRenderer.png(image);
        } finally {
            g.dispose();
        }
    }

    /** 1, 2 or 5 times a power of ten, so the grid lines fall on round values. */
    static double niceStep(double raw) {
        if (raw <= 0) return 1;
        double power = Math.pow(10, Math.floor(Math.log10(raw)));
        double fraction = raw / power;
        return (fraction <= 1 ? 1 : fraction <= 2 ? 2 : fraction <= 5 ? 5 : 10) * power;
    }

    /** Axis label: thousands as "12k" so large areas and values fit the margin. */
    static String label(double value, double step) {
        if (step >= 1000 && value >= 1000) return String.format(Locale.ROOT, "%.0fk", value / 1000);
        return step >= 1 ? String.format(Locale.ROOT, "%.0f", value) : String.format(Locale.ROOT, "%.1f", value);
    }

    private void text(Graphics2D g, String text, int x, int y, boolean center) {
        if (!drawText) return;
        try {
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
            FontMetrics metrics = g.getFontMetrics();
            int width = metrics.stringWidth(text);
            g.setColor(TEXT);
            g.drawString(text, center ? x - width / 2 : x - width, y);
        } catch (Throwable fontFailure) { // NOSONAR: font errors are Errors (UnsatisfiedLinkError, InternalError)
            // skip the label
        }
    }
}
