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
            // Month ticks; a short series gets a tick every few days instead
            g.setColor(AXIS);
            g.drawLine(LEFT, TOP + plotH, LEFT + plotW, TOP + plotH);
            if (days <= 62) {
                int stepDays = days <= 16 ? 2 : 7;
                for (LocalDate day = first; !day.isAfter(last); day = day.plusDays(stepDays)) {
                    int x = LEFT + (int) Math.round(plotW * ChronoUnit.DAYS.between(first, day) / (double) days);
                    g.setColor(AXIS);
                    g.drawLine(x, TOP + plotH, x, TOP + plotH + 4);
                    text(g, String.format(Locale.ROOT, "%02d/%02d", day.getDayOfMonth(), day.getMonthValue()), x, TOP + plotH + 18, true);
                }
            }
            LocalDate month = first.withDayOfMonth(1).plusMonths(1);
            int every = days > 500 ? 3 : 1;
            for (int i = 0; days > 62 && !month.isAfter(last); month = month.plusMonths(1), i++) {
                int x = LEFT + (int) Math.round(plotW * ChronoUnit.DAYS.between(first, month) / (double) days);
                g.setColor(AXIS);
                g.drawLine(x, TOP + plotH, x, TOP + plotH + 4);
                if (i % every == 0) text(g, String.format(Locale.ROOT, "%02d/%d", month.getMonthValue(), month.getYear() % 100),
                        x, TOP + plotH + 18, true);
            }

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
