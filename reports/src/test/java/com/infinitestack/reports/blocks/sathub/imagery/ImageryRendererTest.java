package com.infinitestack.reports.blocks.sathub.imagery;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Renders a synthetic scene (dark forest with a bright clearing, a cloud band, a nodata corner and a
 * round property with a hole) and writes the PNGs to {@code target/imagery/} to look at.
 */
class ImageryRendererTest {

    static final int W = 480;
    static final int H = 360;
    static final double PX = 10;
    static final RasterGrid GRID = new RasterGrid(W, H, 500_000, 7_500_000, PX, 32722);
    static final Path OUT = Path.of("target", "imagery");

    static float[] red = new float[W * H];
    static float[] green = new float[W * H];
    static float[] blue = new float[W * H];
    static float[] nir = new float[W * H];
    static boolean[] clear = new boolean[W * H];
    static PolygonMask area;

    @BeforeAll
    static void scene() throws IOException {
        System.setProperty("java.awt.headless", "true");
        Files.createDirectories(OUT);
        for (int row = 0; row < H; row++) {
            for (int col = 0; col < W; col++) {
                int i = row * W + col;
                double gx = (double) col / W;
                // forest: dark, slightly greener to the east, with texture
                double tex = 0.006 * Math.sin(col * 0.35) * Math.cos(row * 0.29);
                double r = 0.025 + 0.01 * gx + tex;
                double g = 0.045 + 0.015 * gx + tex;
                double b = 0.022 + 0.006 * gx + tex;
                double n = 0.30 + 0.05 * gx + 3 * tex;
                // bare soil clearing in the west of the property
                double dx = col - 190;
                double dy = row - 200;
                if (dx * dx + dy * dy < 45 * 45) {
                    r = 0.16; g = 0.13; b = 0.10; n = 0.22;
                }
                // pasture strip in the south
                if (row > 260 && row < 300) {
                    r = 0.07; g = 0.09; b = 0.05; n = 0.28;
                }
                boolean cloud = row > 95 && row < 130 && col > 120;
                if (cloud) {
                    r = 0.55; g = 0.56; b = 0.58; n = 0.6;
                }
                clear[i] = !cloud;
                boolean nodata = col > W - 60 && row > H - 50;
                red[i] = nodata ? Float.NaN : (float) r;
                green[i] = nodata ? Float.NaN : (float) g;
                blue[i] = nodata ? Float.NaN : (float) b;
                nir[i] = nodata ? Float.NaN : (float) n;
            }
        }
        area = PolygonMask.of(GRID, List.of(List.of(
                circle(GRID.x(240), GRID.y(180), 150 * PX, 72),
                circle(GRID.x(300), GRID.y(150), 25 * PX, 24))));
    }

    static double[][] circle(double cx, double cy, double radius, int points) {
        double[][] ring = new double[points + 1][];
        for (int k = 0; k <= points; k++) {
            double a = 2 * Math.PI * k / points;
            // slightly irregular, like a property boundary
            double rr = radius * (1 + 0.08 * Math.sin(3 * a) + 0.04 * Math.cos(7 * a));
            ring[k] = new double[] {cx + rr * Math.cos(a), cy + rr * Math.sin(a)};
        }
        return ring;
    }

    static BufferedImage decode(byte[] png) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(png));
    }

    static int rgb(BufferedImage image, int col, int row) {
        return image.getRGB(col, row) & 0xffffff;
    }

    static int luminance(int rgb) {
        return (((rgb >> 16) & 0xff) * 299 + ((rgb >> 8) & 0xff) * 587 + (rgb & 0xff) * 114) / 1000;
    }

    @Test
    void trueColorStretchesADarkForest() throws IOException {
        byte[] png = new ImageryRenderer().trueColor(red, green, blue, clear, area);
        Files.write(OUT.resolve("true-color.png"), png);
        BufferedImage image = decode(png);
        assertThat(image.getWidth()).isEqualTo(W);
        assertThat(image.getHeight()).isEqualTo(H);

        int forest = rgb(image, 330, 230); // inside, forest
        assertThat(luminance(forest)).as("forest is not black").isGreaterThan(50);
        assertThat((forest >> 8) & 0xff).as("forest is green").isGreaterThan((forest >> 16) & 0xff);
        assertThat(luminance(rgb(image, 190, 200))).as("clearing is bright").isGreaterThan(200);
        assertThat(rgb(image, W - 5, H - 5)).as("nodata, outside: dimmed grey").isEqualTo(
                Palette.lerp(ImageryRenderer.NODATA_RGB, 0xffffff, ImageryRenderer.TRUE_COLOR_DIM));
        // outside the area the forest is dimmed toward white
        assertThat(luminance(rgb(image, 5, 340))).isGreaterThan(luminance(forest));
        // outline: yellow on the boundary at the west of the circle
        assertThat(hasColorNear(image, 240 - 150 * 0.96, 180, 0xffd400, 6)).isTrue();
    }

    @Test
    void indexMapShowsPaletteCloudsAndNodata() throws IOException {
        float[] ndvi = new float[W * H];
        for (int i = 0; i < ndvi.length; i++) {
            ndvi[i] = Float.isNaN(nir[i]) ? Float.NaN : (nir[i] - red[i]) / (nir[i] + red[i]);
        }
        Palette palette = Palette.vegetation();
        byte[] png = new ImageryRenderer().indexMap(ndvi, clear, area, palette, -0.2, 1.0);
        Files.write(OUT.resolve("ndvi.png"), png);
        BufferedImage image = decode(png);
        assertThat(image.getWidth()).isEqualTo(W);
        assertThat(image.getHeight()).isEqualTo(H);

        int i = 230 * W + 330;
        assertThat(rgb(image, 330, 230)).isEqualTo(palette.colorAt((ndvi[i] + 0.2) / 1.2));
        // cloud inside: white or hatch
        assertThat(rgb(image, 240, 110)).isIn(ImageryRenderer.CLOUD_RGB, ImageryRenderer.HATCH_RGB);
        assertThat(rgb(image, 240, 110 + (6 - (240 + 110) % 6))).isEqualTo(ImageryRenderer.HATCH_RGB);
        // nodata outside, dimmed strongly
        assertThat(rgb(image, W - 5, H - 5)).isEqualTo(
                Palette.lerp(ImageryRenderer.NODATA_RGB, 0xffffff, ImageryRenderer.INDEX_DIM));
        assertThat(hasColorNear(image, 240 - 150 * 0.96, 180, 0x222222, 6)).isTrue();

        // the moisture palette, on a synthetic NDMI-like field
        float[] ndmi = new float[W * H];
        for (int k = 0; k < ndmi.length; k++) ndmi[k] = Float.isNaN(nir[k]) ? Float.NaN : (float) ((k % W) / (double) W * 1.2 - 0.6);
        Files.write(OUT.resolve("ndmi.png"), new ImageryRenderer().indexMap(ndmi, clear, area, Palette.water(), -0.6, 0.6));
    }

    @Test
    void allClearAndNoArea() throws IOException {
        PolygonMask empty = PolygonMask.of(GRID, List.of());
        byte[] png = new ImageryRenderer().trueColor(red, green, blue, null, empty);
        BufferedImage image = decode(png);
        assertThat(image.getWidth()).isEqualTo(W);
        // whole-grid fallback stretch: the forest is still visible (dimmed, as everything is outside)
        assertThat(luminance(rgb(image, 330, 230))).isLessThan(250);
    }

    @Test
    void legendAndDataUri() {
        List<Map<String, Object>> legend = ImageryRenderer.legend(Palette.vegetation(), -0.2, 1.0, 5);
        assertThat(legend).hasSize(5);
        assertThat(legend.get(0)).containsEntry("color", "#785a3c").containsEntry("label", "-0.20");
        assertThat(legend.get(2)).containsEntry("label", "0.40");
        assertThat(legend.get(4)).containsEntry("color", "#0a5019").containsEntry("label", "1.00");
        assertThat((double) legend.get(4).get("value")).isEqualTo(1.0);
        String uri = ImageryRenderer.dataUri(new byte[] {1, 2, 3});
        assertThat(uri).startsWith("data:image/png;base64,");
        assertThat(Base64.getDecoder().decode(uri.substring(uri.indexOf(',') + 1))).containsExactly(1, 2, 3);
    }

    /** Whether some pixel within {@code radius} of (x, y) is close to {@code color}. */
    static boolean hasColorNear(BufferedImage image, double x, double y, int color, int radius) {
        List<Integer> seen = new ArrayList<>();
        for (int dy = -radius; dy <= radius; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                int c = rgb(image, (int) x + dx, (int) y + dy);
                seen.add(c);
                if (Math.abs(((c >> 16) & 0xff) - ((color >> 16) & 0xff)) < 30
                        && Math.abs(((c >> 8) & 0xff) - ((color >> 8) & 0xff)) < 30
                        && Math.abs((c & 0xff) - (color & 0xff)) < 30) return true;
            }
        }
        return false;
    }
}
