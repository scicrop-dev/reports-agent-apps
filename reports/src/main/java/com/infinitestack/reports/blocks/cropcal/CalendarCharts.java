package com.infinitestack.reports.blocks.cropcal;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

import com.infinitestack.reports.blocks.car.PropertyMapRenderer;
import com.infinitestack.reports.blocks.sathub.IndexSeriesChart;

/**
 * The images of the crop calendar: the field series (EVI and CIre of Sentinel-2, VH of Sentinel-1 on the
 * right axis, with the field intervals shaded), the share of the field in each state per day (stacked)
 * and a map of the 100 m blocks colored by the middle of an interval. Like the other charts, every label
 * is optional: a host JRE without fonts gets the images without text.
 */
class CalendarCharts {

    /** The states of is_crop_calendar_state, in stacking order, with their color and label (report language). */
    static final Map<String, Color> STATE_COLORS = new LinkedHashMap<>();
    static final Map<String, String> STATE_LABELS = new LinkedHashMap<>();

    static {
        STATE_COLORS.put("PRE_PLANTING", new Color(0xD7CCC8));
        STATE_COLORS.put("ACTIVE", new Color(0x2E7D32));
        STATE_COLORS.put("SENESCENT", new Color(0xF9A825));
        STATE_COLORS.put("HARVESTED", new Color(0x8D6E63));
        STATE_COLORS.put("UNKNOWN", new Color(0xE0E0E0));
        STATE_LABELS.put("PRE_PLANTING", "Antes do plantio");
        STATE_LABELS.put("ACTIVE", "Cultura ativa");
        STATE_LABELS.put("SENESCENT", "Em senescência");
        STATE_LABELS.put("HARVESTED", "Colhido");
        STATE_LABELS.put("UNKNOWN", "Sem data");
    }

    static final Color PLANTING = new Color(46, 125, 50, 45);
    static final Color SENESCENCE = new Color(249, 168, 37, 55);
    static final Color HARVEST = new Color(141, 110, 99, 60);

    /** Five classes of dates, early to late (viridis), and the color of a block without a date. */
    static final List<Color> DATE_CLASSES = List.of(new Color(0x440154), new Color(0x3B528B), new Color(0x21918C),
            new Color(0x5EC962), new Color(0xFDE725));
    static final Color NO_DATE = new Color(0xE0E0E0);

    static final DateTimeFormatter DAY_MONTH = DateTimeFormatter.ofPattern("dd/MM");

    private static final Color GRID = new Color(0xE0E0E0);
    private static final Color AXIS = new Color(0x9E9E9E);
    private static final Color TEXT = new Color(0x424242);
    private static final Color OUTLINE = new Color(0xB71C1C);

    /** An interval shaded behind the series. */
    record Interval(LocalDate from, LocalDate to, Color color) {}

    /** A map and the legend of its colors ({color, label}). */
    record MapImage(byte[] png, List<Map<String, Object>> legend) {}

    private final boolean drawText;

    CalendarCharts(boolean drawText) {
        this.drawText = drawText;
    }

    /** EVI and CIre (left axis) and VH dB (right axis) on one time axis, the intervals behind them. */
    byte[] series(List<CalendarResults.SeriesPoint> points, List<Interval> intervals, int width, int height) {
        List<IndexSeriesChart.Point> evi = new ArrayList<>();
        List<IndexSeriesChart.Point> cire = new ArrayList<>();
        List<IndexSeriesChart.Point> vh = new ArrayList<>();
        for (CalendarResults.SeriesPoint p : points) {
            if ("s2".equals(p.source())) {
                if (p.evi() != null) evi.add(new IndexSeriesChart.Point(p.date(), p.evi(), p.evi(), p.evi()));
                if (p.cire() != null) cire.add(new IndexSeriesChart.Point(p.date(), p.cire(), p.cire(), p.cire()));
            } else if (p.vhDb() != null) {
                vh.add(new IndexSeriesChart.Point(p.date(), p.vhDb(), p.vhDb(), p.vhDb()));
            }
        }
        List<IndexSeriesChart.Line> lines = List.of(
                new IndexSeriesChart.Line("EVI (S2)", new Color(0x2E7D32), evi, false, false, false),
                new IndexSeriesChart.Line("CIre (S2)", new Color(0x6A1B9A), cire, false, false, false),
                new IndexSeriesChart.Line("VH dB (S1)", new Color(0x1565C0), vh, true, false, true));
        List<IndexSeriesChart.Span> spans = intervals.stream()
                .map(i -> new IndexSeriesChart.Span(i.from(), i.to(), i.color())).toList();
        return new IndexSeriesChart(new Color(0x2E7D32), "%.1f").renderLines(lines, List.of(), spans, width, height);
    }

    /** The share of the field in each state, per day, stacked to 100%. */
    byte[] states(List<CalendarResults.StatePoint> points, int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, width, height);
            TreeMap<LocalDate, Map<String, Double>> byDay = new TreeMap<>();
            for (CalendarResults.StatePoint p : points) {
                byDay.computeIfAbsent(p.date(), d -> new LinkedHashMap<>()).merge(p.state(), p.areaPct(), Double::sum);
            }
            if (byDay.size() < 2) {
                text(g, "no state per day", width / 2, height / 2, 0);
                return PropertyMapRenderer.png(image);
            }
            int left = 44, right = 12, top = 28, bottom = 30;
            int plotW = width - left - right;
            int plotH = height - top - bottom;
            LocalDate first = byDay.firstKey();
            LocalDate last = byDay.lastKey();
            long days = Math.max(1, ChronoUnit.DAYS.between(first, last));

            // Each state is the band between the running total before it and after it.
            double[] below = new double[byDay.size()];
            List<LocalDate> dates = new ArrayList<>(byDay.keySet());
            int legendX = left;
            for (Map.Entry<String, Color> state : STATE_COLORS.entrySet()) {
                double[] above = new double[dates.size()];
                for (int i = 0; i < dates.size(); i++) {
                    above[i] = Math.min(100, below[i] + byDay.get(dates.get(i)).getOrDefault(state.getKey(), 0.0));
                }
                Path2D band = new Path2D.Double();
                for (int i = 0; i < dates.size(); i++) {
                    double x = left + plotW * ChronoUnit.DAYS.between(first, dates.get(i)) / (double) days;
                    double y = top + plotH * (100 - above[i]) / 100.0;
                    if (i == 0) band.moveTo(x, y); else band.lineTo(x, y);
                }
                for (int i = dates.size() - 1; i >= 0; i--) {
                    band.lineTo(left + plotW * ChronoUnit.DAYS.between(first, dates.get(i)) / (double) days,
                            top + plotH * (100 - below[i]) / 100.0);
                }
                band.closePath();
                g.setColor(state.getValue());
                g.fill(band);
                below = above;
                g.fillRect(legendX, 8, 12, 10);
                legendX += 18 + text(g, STATE_LABELS.get(state.getKey()), legendX + 16, 17, -1) + 12;
            }
            g.setStroke(new BasicStroke(1f));
            for (int v = 0; v <= 100; v += 25) {
                int y = top + (int) Math.round(plotH * (100 - v) / 100.0);
                g.setColor(GRID);
                g.drawLine(left, y, left + plotW, y);
                text(g, v + "%", left - 6, y + 4, 1);
            }
            g.setColor(AXIS);
            g.drawLine(left, top + plotH, left + plotW, top + plotH);
            int every = days > 500 ? 3 : days > 62 ? 1 : 0;
            for (LocalDate month = first.withDayOfMonth(1).plusMonths(1); every > 0 && !month.isAfter(last); month = month.plusMonths(every)) {
                int x = left + (int) Math.round(plotW * ChronoUnit.DAYS.between(first, month) / (double) days);
                g.setColor(AXIS);
                g.drawLine(x, top + plotH, x, top + plotH + 4);
                text(g, String.format(Locale.ROOT, "%02d/%02d", month.getMonthValue(), month.getYear() % 100), x, top + plotH + 18, 0);
            }
            if (every == 0) {
                text(g, first.format(DAY_MONTH), left, top + plotH + 18, -1);
                text(g, last.format(DAY_MONTH), left + plotW, top + plotH + 18, 1);
            }
            return PropertyMapRenderer.png(image);
        } finally {
            g.dispose();
        }
    }

    /**
     * The blocks colored by the middle of their interval ({@code interval} gives from and to), in five
     * classes of equal length between the earliest and the latest middle (the legend lists the classes
     * drawn); the field outline over them.
     */
    MapImage dateMap(String aoiGeoJson, List<CalendarResults.BlockCell> blocks,
                     Function<CalendarResults.BlockCell, LocalDate[]> interval, int width, int height) {
        double[] box = {Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        for (CalendarResults.BlockCell b : blocks) extend(box, b.geoJson());
        extend(box, aoiGeoJson);
        if (box[0] > box[2]) return null;
        PropertyMapRenderer.Viewport view = PropertyMapRenderer.Viewport.fit(box[0], box[1], box[2], box[3], width, height);

        List<LocalDate> middles = new ArrayList<>();
        for (CalendarResults.BlockCell b : blocks) middles.add(middle(interval.apply(b)));
        LocalDate earliest = middles.stream().filter(d -> d != null).min(LocalDate::compareTo).orElse(null);
        LocalDate latest = middles.stream().filter(d -> d != null).max(LocalDate::compareTo).orElse(null);
        long span = earliest == null ? 0 : ChronoUnit.DAYS.between(earliest, latest);
        int classes = earliest == null ? 0 : (int) Math.min(DATE_CLASSES.size(), span + 1);

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, width, height);
            boolean anyWithout = false;
            boolean[] used = new boolean[DATE_CLASSES.size()];
            for (int i = 0; i < blocks.size(); i++) {
                Path2D path = PropertyMapRenderer.path(view, blocks.get(i).geoJson());
                if (path == null) continue;
                LocalDate m = middles.get(i);
                anyWithout |= m == null;
                int c = m == null ? -1 : classOf(m, earliest, span, classes);
                if (c >= 0) used[c] = true;
                g.setColor(c < 0 ? NO_DATE : DATE_CLASSES.get(c));
                g.fill(path);
                g.setColor(Color.WHITE);
                g.setStroke(new BasicStroke(0.5f));
                g.draw(path);
            }
            Path2D field = PropertyMapRenderer.path(view, aoiGeoJson);
            if (field != null) {
                g.setColor(OUTLINE);
                g.setStroke(new BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.draw(field);
            }
            new PropertyMapRenderer(drawText).drawDecorations(g, view);

            List<Map<String, Object>> legend = new ArrayList<>();
            for (int c = 0; c < classes; c++) {
                if (!used[c]) continue;
                LocalDate from = earliest.plusDays(Math.round(span * c / (double) classes));
                LocalDate to = c == classes - 1 ? latest : earliest.plusDays(Math.round(span * (c + 1) / (double) classes) - 1);
                legend.add(legendItem(DATE_CLASSES.get(c), from.equals(to) ? from.format(DAY_MONTH)
                        : from.format(DAY_MONTH) + " a " + to.format(DAY_MONTH)));
            }
            if (anyWithout) legend.add(legendItem(NO_DATE, "sem data"));
            return new MapImage(PropertyMapRenderer.png(image), legend);
        } finally {
            g.dispose();
        }
    }

    static LocalDate middle(LocalDate[] interval) {
        if (interval == null || interval[0] == null || interval[1] == null) return null;
        return interval[0].plusDays(ChronoUnit.DAYS.between(interval[0], interval[1]) / 2);
    }

    static int classOf(LocalDate date, LocalDate earliest, long span, int classes) {
        if (span <= 0 || classes <= 1) return 0;
        int c = (int) (ChronoUnit.DAYS.between(earliest, date) * classes / (span + 1));
        return Math.max(0, Math.min(classes - 1, c));
    }

    private static Map<String, Object> legendItem(Color color, String label) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("color", String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue()));
        item.put("label", label);
        return item;
    }

    /** Grows {minLon, minLat, maxLon, maxLat} with every coordinate of a GeoJSON geometry. */
    private static void extend(double[] box, String geoJson) {
        if (geoJson == null) return;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\\[\\s*(-?[0-9.eE+-]+)\\s*,\\s*(-?[0-9.eE+-]+)\\s*\\]").matcher(geoJson);
        while (m.find()) {
            double lon = Double.parseDouble(m.group(1));
            double lat = Double.parseDouble(m.group(2));
            box[0] = Math.min(box[0], lon);
            box[1] = Math.min(box[1], lat);
            box[2] = Math.max(box[2], lon);
            box[3] = Math.max(box[3], lat);
        }
    }

    /** Draws text (align: -1 left, 0 center, 1 right); returns its width, 0 when there is no font. */
    private int text(Graphics2D g, String text, int x, int y, int align) {
        if (!drawText) return 0;
        try {
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
            FontMetrics metrics = g.getFontMetrics();
            int width = metrics.stringWidth(text);
            g.setColor(TEXT);
            g.drawString(text, align < 0 ? x : align == 0 ? x - width / 2 : x - width, y);
            return width;
        } catch (Throwable fontFailure) { // NOSONAR: font errors are Errors (UnsatisfiedLinkError, InternalError)
            return 0;
        }
    }
}
