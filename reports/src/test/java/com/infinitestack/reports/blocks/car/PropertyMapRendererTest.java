package com.infinitestack.reports.blocks.car;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.List;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.infinitestack.reports.blocks.car.PropertyMapRenderer.Layer;
import com.infinitestack.reports.blocks.car.PropertyMapRenderer.MapData;
import com.infinitestack.reports.blocks.car.PropertyMapRenderer.Result;
import com.infinitestack.reports.blocks.car.PropertyMapRenderer.Viewport;

/** The map renderer on hand-written GeoJSON: no database, no fonts required. */
class PropertyMapRendererTest {

    /** Unit square (lon/lat 0..1) with a hole in the middle. */
    private static final String SQUARE_WITH_HOLE = """
            {"type":"MultiPolygon","coordinates":[[
              [[0,0],[1,0],[1,1],[0,1],[0,0]],
              [[0.4,0.4],[0.6,0.4],[0.6,0.6],[0.4,0.6],[0.4,0.4]]]]}""";
    private static final String SMALL_SQUARE = """
            {"type":"Polygon","coordinates":[[[0.1,0.1],[0.3,0.1],[0.3,0.3],[0.1,0.3],[0.1,0.1]]]}""";
    private static final String NEIGHBOUR = """
            {"type":"Polygon","coordinates":[[[1,0],[2,0],[2,1],[1,1],[1,0]]]}""";
    private static final String EMPTY = """
            {"type":"MultiPolygon","coordinates":[]}""";

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    private static MapData sample() {
        return new MapData(SQUARE_WITH_HOLE,
                List.of(new Layer("VEGETACAO_NATIVA", "Native vegetation", List.of(SQUARE_WITH_HOLE)),
                        new Layer("APPS", "APP", List.of(SMALL_SQUARE)),
                        new Layer("RESERVA_LEGAL", "Legal reserve", List.of(EMPTY))),
                List.of(NEIGHBOUR));
    }

    @Test
    void drawsLayersWithHolesAndLegendOfDrawnLayers() throws Exception {
        Viewport view = Viewport.fit(0, 0, 1, 1, 800, 600);
        Result result = new PropertyMapRenderer().render(view, sample());

        BufferedImage image = ImageIO.read(new ByteArrayInputStream(result.png()));
        assertThat(image.getWidth()).isEqualTo(800);
        assertThat(image.getHeight()).isEqualTo(600);

        Color vegetation = PropertyMapRenderer.LAYER_COLORS.get("VEGETACAO_NATIVA");
        Color app = PropertyMapRenderer.LAYER_COLORS.get("APPS");
        assertColor(image, view, 0.8, 0.8, overWhite(vegetation));
        assertColor(image, view, 0.2, 0.2, over(app, overWhite(vegetation)));
        assertColor(image, view, 0.5, 0.5, Color.WHITE); // the hole
        assertColor(image, view, 1.1, 0.5, Color.WHITE); // inside the neighbour: outline only
        assertThat(isPropertyRed(image, view, 0, 0.5)).as("property outline").isTrue();

        assertThat(result.legend()).extracting(PropertyMapRenderer.LegendEntry::layer)
                .containsExactly("VEGETACAO_NATIVA", "APPS");
        assertThat(result.legend().get(0).color()).isEqualTo("#4caf50");
    }

    @Test
    void rendersWithoutText() throws Exception {
        Viewport view = Viewport.fit(0, 0, 1, 1, 400, 300);
        Result result = new PropertyMapRenderer(false).render(view, sample());
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(result.png()));
        assertThat(image.getWidth()).isEqualTo(400);
        assertColor(image, view, 0.8, 0.8, overWhite(PropertyMapRenderer.LAYER_COLORS.get("VEGETACAO_NATIVA")));
    }

    @Test
    void viewportKeepsShapesUnstretchedAwayFromTheEquator() {
        Viewport view = Viewport.fit(-40, -60.5, -39, -59.5, 1000, 1000);
        double widthPx = view.x(-39) - view.x(-40);
        double heightPx = view.y(-60.5) - view.y(-59.5);
        assertThat(widthPx / heightPx).isCloseTo(Math.cos(Math.toRadians(60)), offset(0.01));
        assertThat(view.minLon()).isLessThan(-40);
        assertThat(view.maxLat()).isGreaterThan(-59.5);
    }

    @Test
    void scaleBarUsesRoundDistances() {
        assertThat(PropertyMapRenderer.niceDistance(740)).isEqualTo(500);
        assertThat(PropertyMapRenderer.niceDistance(2300)).isEqualTo(2000);
        assertThat(PropertyMapRenderer.niceDistance(19)).isEqualTo(10);
        assertThat(PropertyMapRenderer.formatDistance(2000)).isEqualTo("2 km");
        assertThat(PropertyMapRenderer.formatDistance(500)).isEqualTo("500 m");
    }

    @Test
    void sizeIsClamped() {
        assertThat(CarPropertyMapBlock.size(null, 1000)).isEqualTo(1000);
        assertThat(CarPropertyMapBlock.size(50L, 1000)).isEqualTo(300);
        assertThat(CarPropertyMapBlock.size(9999.0, 1000)).isEqualTo(2000);
        assertThat(CarPropertyMapBlock.size(640, 1000)).isEqualTo(640);
    }

    private static Color overWhite(Color c) {
        return over(c, Color.WHITE);
    }

    private static Color over(Color top, Color bottom) {
        double a = Math.round(PropertyMapRenderer.FILL_ALPHA * 255) / 255.0;
        return new Color(mix(top.getRed(), bottom.getRed(), a), mix(top.getGreen(), bottom.getGreen(), a),
                mix(top.getBlue(), bottom.getBlue(), a));
    }

    private static int mix(int top, int bottom, double a) {
        return (int) Math.round(top * a + bottom * (1 - a));
    }

    private static void assertColor(BufferedImage image, Viewport view, double lon, double lat, Color expected) {
        Color actual = new Color(image.getRGB((int) view.x(lon), (int) view.y(lat)));
        int distance = Math.abs(actual.getRed() - expected.getRed())
                + Math.abs(actual.getGreen() - expected.getGreen())
                + Math.abs(actual.getBlue() - expected.getBlue());
        assertThat(distance).as("pixel at %s,%s: %s vs %s", lon, lat, actual, expected).isLessThanOrEqualTo(9);
    }

    private static boolean isPropertyRed(BufferedImage image, Viewport view, double lon, double lat) {
        Color c = new Color(image.getRGB((int) Math.round(view.x(lon)), (int) Math.round(view.y(lat))));
        return c.getRed() > 120 && c.getGreen() < 80 && c.getBlue() < 80;
    }
}
