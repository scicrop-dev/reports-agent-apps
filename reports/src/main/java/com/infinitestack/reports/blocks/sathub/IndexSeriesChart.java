package com.infinitestack.reports.blocks.sathub;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;

import com.infinitestack.reports.blocks.car.PropertyMapRenderer;

/**
 * Draws a vegetation index time series as a PNG for the PDF: a band (the 10th to 90th percentile), the
 * pixel-weighted mean as a line with points, horizontal grid with values and month ticks (day ticks
 * for a series of two months or less). The BR Weather daily Block draws temperatures with it too
 * (mean line, minimum to maximum band), in its own color and label format. Like the
 * property map, every label is optional: a host JRE without fonts gets the chart without text.
 */
public class IndexSeriesChart {

    /** One acquisition date of the series: the mean and the band around it. */
    public record Point(LocalDate date, double mean, double min, double max) {}

    /**
     * One line of {@link #renderLines}: its points (mean, and min/max for the band), a legend label,
     * dashed or solid, with or without the band, on the left or on the right axis (an index on another
     * scale, such as the radar RVI from 0 to 4 next to NDVI from -1 to 1).
     */
    public record Line(String label, Color color, List<Point> points, boolean dashed, boolean band, boolean rightAxis) {}

    /** A highlighted value drawn over the lines (e.g. a drop), on the left axis. */
    public record Marker(LocalDate date, double value, Color color) {}

    /** A shaded period behind the lines (e.g. a harvest interval), clipped to the time axis of the lines. */
    public record Span(LocalDate from, LocalDate to, Color color) {}

    /** Distinct colors for the lines of a comparison, in order. */
    public static final List<Color> PALETTE = List.of(new Color(0x2E7D32), new Color(0x1565C0), new Color(0xEF6C00),
            new Color(0x6A1B9A), new Color(0x00838F), new Color(0x8D6E63));

    private static final Color GREEN = new Color(0x2E7D32);
    private static final Color GRID = new Color(0xE0E0E0);
    private static final Color AXIS = new Color(0x9E9E9E);
    private static final Color TEXT = new Color(0x424242);

    private static final int LEFT = 52;
    private static final int RIGHT = 16;
    private static final int TOP = 14;
    private static final int BOTTOM = 30;

    private final boolean drawText;
    private final Color line;
    private final Color band;
    private final String valueFormat;

    public IndexSeriesChart() {
        this(true);
    }

    /** @param valueFormat axis label format, e.g. "%.0f" for temperatures */
    public IndexSeriesChart(Color line, String valueFormat) {
        this(true, line, valueFormat);
    }

    IndexSeriesChart(boolean drawText) {
        this(drawText, GREEN, "%.2f");
    }

    private IndexSeriesChart(boolean drawText, Color line, String valueFormat) {
        this.drawText = drawText;
        this.line = line;
        this.band = new Color(line.getRed(), line.getGreen(), line.getBlue(), 45);
        this.valueFormat = valueFormat;
    }

    public byte[] render(List<Point> points, int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, width, height);
            int plotW = width - LEFT - RIGHT;
            int plotH = height - TOP - BOTTOM;
            if (points.isEmpty()) {
                text(g, "no observation in the period", width / 2, height / 2, true);
                return PropertyMapRenderer.png(image);
            }

            double lo = points.stream().mapToDouble(Point::min).min().orElse(0);
            double hi = points.stream().mapToDouble(Point::max).max().orElse(1);
            double step = niceStep((hi - lo) / 5);
            lo = Math.floor(lo / step) * step;
            hi = Math.ceil(hi / step) * step;
            if (hi <= lo) hi = lo + step;
            LocalDate first = points.get(0).date();
            LocalDate last = points.get(points.size() - 1).date();
            long days = Math.max(1, ChronoUnit.DAYS.between(first, last));

            // Grid and value labels
            g.setStroke(new BasicStroke(1f));
            for (double v = lo; v <= hi + step / 2; v += step) {
                int y = TOP + (int) Math.round(plotH * (hi - v) / (hi - lo));
                g.setColor(GRID);
                g.drawLine(LEFT, y, LEFT + plotW, y);
                text(g, String.format(Locale.ROOT, valueFormat, v), LEFT - 6, y + 4, false);
            }
            drawTimeAxis(g, first, last, days, LEFT, TOP, plotW, plotH);

            // Band (min-max) and mean line
            Path2D band = new Path2D.Double();
            Path2D line = new Path2D.Double();
            for (int i = 0; i < points.size(); i++) {
                Point p = points.get(i);
                double x = x(p.date(), first, days, plotW);
                double yMax = y(p.max(), lo, hi, plotH);
                if (i == 0) band.moveTo(x, yMax); else band.lineTo(x, yMax);
                double yMean = y(p.mean(), lo, hi, plotH);
                if (i == 0) line.moveTo(x, yMean); else line.lineTo(x, yMean);
            }
            for (int i = points.size() - 1; i >= 0; i--) {
                Point p = points.get(i);
                band.lineTo(x(p.date(), first, days, plotW), y(p.min(), lo, hi, plotH));
            }
            band.closePath();
            g.setColor(this.band);
            g.fill(band);
            g.setColor(this.line);
            g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(line);
            for (Point p : points) {
                int x = (int) Math.round(x(p.date(), first, days, plotW));
                int y = (int) Math.round(y(p.mean(), lo, hi, plotH));
                g.fillOval(x - 3, y - 3, 6, 6);
            }
            return PropertyMapRenderer.png(image);
        } finally {
            g.dispose();
        }
    }

    /**
     * Several series on one time axis, with a legend on top: the left axis for the lines on the main
     * scale, a right axis (labels on the right) when some line has {@code rightAxis}, and markers drawn
     * as rings over the lines. {@link #render} stays the single-series chart.
     */
    public byte[] renderLines(List<Line> lines, List<Marker> markers, int width, int height) {
        return renderLines(lines, markers, List.of(), width, height);
    }

    /** {@link #renderLines(List, List, int, int)} with shaded periods under the lines. */
    public byte[] renderLines(List<Line> lines, List<Marker> markers, List<Span> spans, int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, width, height);
            List<Line> drawn = lines.stream().filter(l -> !l.points().isEmpty()).toList();
            if (drawn.isEmpty()) {
                text(g, "no observation in the period", width / 2, height / 2, true);
                return PropertyMapRenderer.png(image);
            }
            // Without lines on the left, the right-axis ones move there: one scale needs one axis.
            boolean anyLeft = drawn.stream().anyMatch(l -> !l.rightAxis());
            boolean anyRight = anyLeft && drawn.stream().anyMatch(Line::rightAxis);
            int left = LEFT;
            int right = anyRight ? LEFT : RIGHT;
            int top = TOP + 18;
            int plotW = width - left - right;
            int plotH = height - top - BOTTOM;

            LocalDate first = drawn.stream().map(l -> l.points().get(0).date()).min(LocalDate::compareTo).orElseThrow();
            LocalDate last = drawn.stream().map(l -> l.points().get(l.points().size() - 1).date()).max(LocalDate::compareTo).orElseThrow();
            long days = Math.max(1, ChronoUnit.DAYS.between(first, last));

            double[] leftRange = range(drawn, false, anyRight, markers);
            double[] rightRange = anyRight ? range(drawn, true, true, List.of()) : null;

            g.setStroke(new BasicStroke(1f));
            for (double v = leftRange[0]; v <= leftRange[1] + leftRange[2] / 2; v += leftRange[2]) {
                int y = top + (int) Math.round(plotH * (leftRange[1] - v) / (leftRange[1] - leftRange[0]));
                g.setColor(GRID);
                g.drawLine(left, y, left + plotW, y);
                text(g, String.format(Locale.ROOT, valueFormat, v), left - 6, y + 4, false);
            }
            if (rightRange != null) {
                for (double v = rightRange[0]; v <= rightRange[1] + rightRange[2] / 2; v += rightRange[2]) {
                    int y = top + (int) Math.round(plotH * (rightRange[1] - v) / (rightRange[1] - rightRange[0]));
                    textLeftAligned(g, String.format(Locale.ROOT, valueFormat, v), left + plotW + 6, y + 4);
                }
            }
            drawTimeAxis(g, first, last, days, left, top, plotW, plotH);
            if (spans != null) {
                for (Span span : spans) {
                    if (span.from() == null || span.to() == null || span.to().isBefore(first) || span.from().isAfter(last)) continue;
                    double x0 = left + plotW * Math.max(0, ChronoUnit.DAYS.between(first, span.from())) / (double) days;
                    double x1 = left + plotW * Math.min(days, ChronoUnit.DAYS.between(first, span.to())) / (double) days;
                    g.setColor(span.color());
                    g.fill(new java.awt.geom.Rectangle2D.Double(x0, top, Math.max(2, x1 - x0), plotH));
                }
            }

            int legendX = left;
            for (Line line : drawn) {
                boolean onRight = anyRight && line.rightAxis();
                double[] r = onRight ? rightRange : leftRange;
                Path2D path = new Path2D.Double();
                Path2D band = new Path2D.Double();
                List<Point> points = line.points();
                for (int i = 0; i < points.size(); i++) {
                    Point p = points.get(i);
                    double x = left + plotW * ChronoUnit.DAYS.between(first, p.date()) / (double) days;
                    double y = top + plotH * (r[1] - p.mean()) / (r[1] - r[0]);
                    double yMax = top + plotH * (r[1] - p.max()) / (r[1] - r[0]);
                    if (i == 0) {
                        path.moveTo(x, y);
                        band.moveTo(x, yMax);
                    } else {
                        path.lineTo(x, y);
                        band.lineTo(x, yMax);
                    }
                }
                if (line.band()) {
                    for (int i = points.size() - 1; i >= 0; i--) {
                        Point p = points.get(i);
                        band.lineTo(left + plotW * ChronoUnit.DAYS.between(first, p.date()) / (double) days,
                                top + plotH * (r[1] - p.min()) / (r[1] - r[0]));
                    }
                    band.closePath();
                    Color c = line.color();
                    g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 35));
                    g.fill(band);
                }
                g.setColor(line.color());
                g.setStroke(stroke(line.dashed()));
                g.draw(path);
                if (!line.dashed()) {
                    for (Point p : points) {
                        int x = (int) Math.round(left + plotW * ChronoUnit.DAYS.between(first, p.date()) / (double) days);
                        int y = (int) Math.round(top + plotH * (r[1] - p.mean()) / (r[1] - r[0]));
                        g.fillOval(x - 3, y - 3, 6, 6);
                    }
                }
                legendX = legendEntry(g, legendX, line.color(), line.dashed(),
                        (line.label() == null ? "" : line.label()) + (onRight ? " (right axis)" : ""));
            }
            if (markers != null && !markers.isEmpty()) {
                g.setStroke(new BasicStroke(2f));
                for (Marker m : markers) {
                    int x = (int) Math.round(left + plotW * ChronoUnit.DAYS.between(first, m.date()) / (double) days);
                    int y = (int) Math.round(top + plotH * (leftRange[1] - m.value()) / (leftRange[1] - leftRange[0]));
                    g.setColor(m.color());
                    g.drawOval(x - 6, y - 6, 12, 12);
                }
            }
            return PropertyMapRenderer.png(image);
        } finally {
            g.dispose();
        }
    }

    /** {lo, hi, step} of one axis, rounded to the grid step; {@code split} keeps only that axis' lines. */
    private static double[] range(List<Line> lines, boolean rightAxis, boolean split, List<Marker> markers) {
        double lo = Double.POSITIVE_INFINITY;
        double hi = Double.NEGATIVE_INFINITY;
        for (Line line : lines) {
            if (split && line.rightAxis() != rightAxis) continue;
            for (Point p : line.points()) {
                lo = Math.min(lo, line.band() ? p.min() : p.mean());
                hi = Math.max(hi, line.band() ? p.max() : p.mean());
            }
        }
        if (markers != null) {
            for (Marker m : markers) {
                lo = Math.min(lo, m.value());
                hi = Math.max(hi, m.value());
            }
        }
        if (!Double.isFinite(lo) || !Double.isFinite(hi)) {
            lo = 0;
            hi = 1;
        }
        double step = niceStep((hi - lo) / 5);
        lo = Math.floor(lo / step) * step;
        hi = Math.ceil(hi / step) * step;
        if (hi <= lo) hi = lo + step;
        return new double[] {lo, hi, step};
    }

    private static BasicStroke stroke(boolean dashed) {
        return dashed
                ? new BasicStroke(1.6f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, new float[] {6f, 4f}, 0f)
                : new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
    }

    /** A short line sample and its label at the top; returns where the next entry starts. */
    private int legendEntry(Graphics2D g, int x, Color color, boolean dashed, String label) {
        int y = TOP;
        g.setColor(color);
        g.setStroke(stroke(dashed));
        g.drawLine(x, y, x + 18, y);
        int labelWidth = textLeftAligned(g, label, x + 24, y + 4);
        return x + 24 + labelWidth + 16;
    }

    /** Draws text starting at x; returns its width (0 when there is no font). */
    private int textLeftAligned(Graphics2D g, String text, int x, int y) {
        if (!drawText) return 0;
        try {
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
            g.setColor(TEXT);
            g.drawString(text, x, y);
            return g.getFontMetrics().stringWidth(text);
        } catch (Throwable fontFailure) { // NOSONAR: font errors are Errors (UnsatisfiedLinkError, InternalError)
            return 0;
        }
    }

    /** Base line plus month ticks (day ticks for a series of two months or less) under the plot. */
    private void drawTimeAxis(Graphics2D g, LocalDate first, LocalDate last, long days, int left, int top, int plotW, int plotH) {
        // Month ticks; a short series gets a tick every few days instead
        g.setColor(AXIS);
        g.drawLine(left, top + plotH, left + plotW, top + plotH);
        if (days <= 62) {
            int stepDays = days <= 16 ? 2 : 7;
            for (LocalDate day = first; !day.isAfter(last); day = day.plusDays(stepDays)) {
                int x = left + (int) Math.round(plotW * ChronoUnit.DAYS.between(first, day) / (double) days);
                g.setColor(AXIS);
                g.drawLine(x, top + plotH, x, top + plotH + 4);
                text(g, String.format(Locale.ROOT, "%02d/%02d", day.getDayOfMonth(), day.getMonthValue()), x, top + plotH + 18, true);
            }
        }
        LocalDate month = first.withDayOfMonth(1).plusMonths(1);
        int every = days > 500 ? 3 : 1;
        for (int i = 0; days > 62 && !month.isAfter(last); month = month.plusMonths(1), i++) {
            int x = left + (int) Math.round(plotW * ChronoUnit.DAYS.between(first, month) / (double) days);
            g.setColor(AXIS);
            g.drawLine(x, top + plotH, x, top + plotH + 4);
            if (i % every == 0) text(g, String.format(Locale.ROOT, "%02d/%d", month.getMonthValue(), month.getYear() % 100),
                    x, top + plotH + 18, true);
        }
    }

    private static double x(LocalDate date, LocalDate first, long days, int plotW) {
        return LEFT + plotW * ChronoUnit.DAYS.between(first, date) / (double) days;
    }

    private static double y(double value, double lo, double hi, int plotH) {
        return TOP + plotH * (hi - value) / (hi - lo);
    }

    /** 1, 2 or 5 times a power of ten, so the grid lines fall on round values. */
    static double niceStep(double raw) {
        if (raw <= 0) return 0.1;
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
