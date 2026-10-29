package com.infinitestack.reports.blocks.brweather;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;

import com.infinitestack.reports.blocks.car.PropertyMapRenderer;

/**
 * Draws daily values as bars (the rain of each day) as a PNG for the PDF, with a horizontal grid and
 * date ticks. A day without value leaves a gap. Optional lines (e.g. the soil water storage of a water
 * balance) are drawn over the bars on the same axis, one point per day at the centre of its slot. Like the other charts, every label is optional: a
 * host JRE without fonts gets the chart without text.
 */
public class DailyBarChart {

    /** One day; {@code value} null when there is no data. */
    public record Bar(LocalDate date, Double value) {}

    /** A line over the bars, on the same axis; a point with a null value breaks the line. */
    public record Line(Color color, List<Bar> points) {}

    private static final Color BAR = new Color(0x1565C0);
    private static final Color GRID = new Color(0xE0E0E0);
    private static final Color AXIS = new Color(0x9E9E9E);
    private static final Color TEXT = new Color(0x424242);

    private static final int LEFT = 52;
    private static final int RIGHT = 16;
    private static final int TOP = 14;
    private static final int BOTTOM = 30;

    private final boolean drawText;

    public DailyBarChart() {
        this(true);
    }

    DailyBarChart(boolean drawText) {
        this.drawText = drawText;
    }

    public byte[] render(List<Bar> bars, int width, int height) {
        return render(bars, List.of(), width, height);
    }

    /** The bars and, over them, the lines; the axis covers both. */
    public byte[] render(List<Bar> bars, List<Line> lines, int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, width, height);
            int plotW = width - LEFT - RIGHT;
            int plotH = height - TOP - BOTTOM;
            if (bars.isEmpty()) {
                text(g, "no data in the period", width / 2, height / 2, true);
                return PropertyMapRenderer.png(image);
            }

            double hi = bars.stream().filter(b -> b.value() != null).mapToDouble(Bar::value).max().orElse(1);
            for (Line line : lines) {
                hi = Math.max(hi, line.points().stream().filter(b -> b.value() != null).mapToDouble(Bar::value).max().orElse(0));
            }
            double step = niceStep(Math.max(hi, 1) / 5);
            hi = Math.max(step, Math.ceil(hi / step) * step);
            LocalDate first = bars.get(0).date();
            long days = ChronoUnit.DAYS.between(first, bars.get(bars.size() - 1).date()) + 1;
            double slot = plotW / (double) days;

            g.setStroke(new BasicStroke(1f));
            for (double v = 0; v <= hi + step / 2; v += step) {
                int y = TOP + (int) Math.round(plotH * (hi - v) / hi);
                g.setColor(GRID);
                g.drawLine(LEFT, y, LEFT + plotW, y);
                text(g, String.format(Locale.ROOT, "%.0f", v), LEFT - 6, y + 4, false);
            }

            g.setColor(BAR);
            for (Bar bar : bars) {
                if (bar.value() == null || bar.value() <= 0) continue;
                double x = LEFT + slot * ChronoUnit.DAYS.between(first, bar.date());
                int h = (int) Math.round(plotH * bar.value() / hi);
                int w = Math.max(1, (int) Math.floor(slot * 0.8));
                g.fillRect((int) Math.round(x + slot * 0.1), TOP + plotH - h, w, h);
            }

            g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            for (Line line : lines) {
                g.setColor(line.color());
                int[] last = null;
                for (Bar point : line.points()) {
                    if (point.value() == null) {
                        last = null;
                        continue;
                    }
                    int x = LEFT + (int) Math.round(slot * (ChronoUnit.DAYS.between(first, point.date()) + 0.5));
                    int y = TOP + (int) Math.round(plotH * (hi - Math.max(0, point.value())) / hi);
                    if (last != null) g.drawLine(last[0], last[1], x, y);
                    else g.fillOval(x - 1, y - 1, 3, 3);
                    last = new int[] {x, y};
                }
            }
            g.setStroke(new BasicStroke(1f));

            g.setColor(AXIS);
            g.drawLine(LEFT, TOP + plotH, LEFT + plotW, TOP + plotH);
            long every = days <= 16 ? 2 : days <= 62 ? 7 : days <= 190 ? 30 : 61;
            for (long d = 0; d < days; d += every) {
                LocalDate day = first.plusDays(d);
                int x = LEFT + (int) Math.round(slot * (d + 0.5));
                g.setColor(AXIS);
                g.drawLine(x, TOP + plotH, x, TOP + plotH + 4);
                text(g, String.format(Locale.ROOT, "%02d/%02d", day.getDayOfMonth(), day.getMonthValue()), x, TOP + plotH + 18, true);
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
