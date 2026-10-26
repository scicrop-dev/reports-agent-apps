package com.infinitestack.reports.blocks.sathub.imagery;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * A color ramp in the Satellite Hub format ({@code [{"pos": 0.0, "color": "#785a3c"}, ...]}, pos in
 * 0..1), interpolated linearly in RGB. Built-in ramps for when Satellite Hub has none.
 */
public record Palette(String name, List<Stop> stops) {

    public record Stop(double pos, int rgb) {}

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Copied from the Satellite Hub palettes table ("Vegetação"). */
    private static final String VEGETATION_JSON = """
            [{"pos": 0.00, "color": "#785a3c"}, {"pos": 0.40, "color": "#785a3c"}, {"pos": 0.50, "color": "#beaa8c"},
             {"pos": 0.60, "color": "#e6dc78"}, {"pos": 0.70, "color": "#96cd50"}, {"pos": 0.80, "color": "#3ca02d"},
             {"pos": 0.95, "color": "#0a5019"}, {"pos": 1.00, "color": "#0a5019"}]""";
    /** Copied from the Satellite Hub palettes table ("Umidade"). */
    private static final String WATER_JSON = """
            [{"pos": 0.00, "color": "#a6611a"}, {"pos": 0.25, "color": "#dfc27d"}, {"pos": 0.50, "color": "#f5f5f5"},
             {"pos": 0.75, "color": "#80cdc1"}, {"pos": 1.00, "color": "#018571"}]""";

    public Palette {
        if (stops == null || stops.isEmpty()) throw new IllegalArgumentException("palette without stops");
        List<Stop> sorted = new ArrayList<>(stops);
        sorted.sort(Comparator.comparingDouble(Stop::pos));
        stops = List.copyOf(sorted);
    }

    /** RGB (0xRRGGBB) at t in 0..1 (clamped). NaN gives the first stop. */
    public int colorAt(double t) {
        if (Double.isNaN(t)) t = 0;
        t = Math.max(0, Math.min(1, t));
        Stop first = stops.get(0);
        if (t <= first.pos()) return first.rgb();
        for (int i = 1; i < stops.size(); i++) {
            Stop b = stops.get(i);
            if (t <= b.pos()) {
                Stop a = stops.get(i - 1);
                double span = b.pos() - a.pos();
                double f = span <= 0 ? 1 : (t - a.pos()) / span;
                return lerp(a.rgb(), b.rgb(), f);
            }
        }
        return stops.get(stops.size() - 1).rgb();
    }

    static int lerp(int a, int b, double f) {
        int r = (int) Math.round(((a >> 16) & 0xff) + (((b >> 16) & 0xff) - ((a >> 16) & 0xff)) * f);
        int g = (int) Math.round(((a >> 8) & 0xff) + (((b >> 8) & 0xff) - ((a >> 8) & 0xff)) * f);
        int bl = (int) Math.round((a & 0xff) + ((b & 0xff) - (a & 0xff)) * f);
        return (r << 16) | (g << 8) | bl;
    }

    /**
     * Parses the Satellite Hub stops JSON.
     *
     * @throws IllegalArgumentException when the JSON is not a non-empty array of {pos in 0..1,
     *                                  color "#rrggbb"}
     */
    public static Palette parse(String name, String stopsJson) {
        JsonNode root;
        try {
            root = JSON.readTree(stopsJson == null ? "" : stopsJson);
        } catch (IOException e) {
            throw new IllegalArgumentException("invalid palette JSON: " + e.getMessage(), e);
        }
        if (root == null || !root.isArray() || root.isEmpty()) {
            throw new IllegalArgumentException("palette JSON must be a non-empty array");
        }
        List<Stop> stops = new ArrayList<>();
        for (JsonNode node : root) {
            JsonNode pos = node.get("pos");
            JsonNode color = node.get("color");
            if (pos == null || !pos.isNumber() || color == null || !color.isTextual()) {
                throw new IllegalArgumentException("palette stop needs numeric pos and text color: " + node);
            }
            double p = pos.asDouble();
            if (!(p >= 0 && p <= 1)) throw new IllegalArgumentException("palette pos out of 0..1: " + p);
            stops.add(new Stop(p, parseColor(color.asText())));
        }
        return new Palette(name, stops);
    }

    private static int parseColor(String text) {
        String hex = text.trim();
        if (hex.startsWith("#")) hex = hex.substring(1);
        if (!hex.matches("[0-9a-fA-F]{6}")) throw new IllegalArgumentException("palette color is not #rrggbb: " + text);
        return Integer.parseInt(hex, 16);
    }

    /** Brown-yellow-green ramp for vegetation indices (NDVI, EVI, SAVI, NDRE, GNDVI). */
    public static Palette vegetation() {
        return parse("Vegetation", VEGETATION_JSON);
    }

    /** Brown-white-blue ramp for water and moisture indices (NDWI, NDMI). */
    public static Palette water() {
        return parse("Moisture", WATER_JSON);
    }
}
