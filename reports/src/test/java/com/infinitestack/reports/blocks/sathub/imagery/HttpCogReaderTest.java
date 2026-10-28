package com.infinitestack.reports.blocks.sathub.imagery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.offset;

import java.net.URISyntaxException;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The COG reader on {@code cog/pattern-32719.tif}, a 600 x 500 uint16 COG made with GDAL 3.8:
 * EPSG:32719, origin (300000, 9100000), 10 m, DEFLATE + predictor 2, 128 px tiles, nodata 0,
 * nearest overviews 300x250, 150x125 and 75x62. Values follow {@link #value} (numpy, then
 * {@code gdal_translate -a_srs EPSG:32719 -a_ullr 300000 9100000 306000 9095000 -a_nodata 0 -of COG
 * -co COMPRESS=DEFLATE -co PREDICTOR=2 -co BLOCKSIZE=128 -co OVERVIEWS=AUTO -co OVERVIEW_RESAMPLING=NEAREST}),
 * with a nodata block (0) at rows 100-149, columns 200-299.
 */
class HttpCogReaderTest {

    private static final int EPSG = 32719;
    private static final double ORIGIN_X = 300_000;
    private static final double ORIGIN_Y = 9_100_000;

    private FileRangeFetcher fetcher;
    private HttpCogReader reader;
    private String url;

    @BeforeEach
    void setUp() throws URISyntaxException {
        fetcher = new FileRangeFetcher();
        reader = new HttpCogReader(fetcher);
        url = Path.of(getClass().getResource("/cog/pattern-32719.tif").toURI()).toString();
    }

    /** The source value at (col, row) of the full resolution; 0 = nodata. */
    private static int value(int col, int row) {
        if (row >= 100 && row < 150 && col >= 200 && col < 300) return 0;
        return 1 + (col * col * 7 + row * 131 + col * row) % 4000;
    }

    private static float expected(int col, int row) {
        int v = value(col, row);
        return v == 0 ? Float.NaN : v;
    }

    /** A grid of {@code pixelSize} whose top-left corner is source pixel (col, row) of that pixel size. */
    private static RasterGrid grid(int col, int row, int width, int height, double pixelSize) {
        return new RasterGrid(width, height, ORIGIN_X + col * pixelSize, ORIGIN_Y - row * pixelSize, pixelSize, EPSG);
    }

    @Test
    void parsesFullResolutionAndOverviews() throws Exception {
        HttpCogReader.Cog cog = reader.header(url);
        assertThat(cog.epsg()).isEqualTo(EPSG);
        assertThat(cog.originX()).isEqualTo(ORIGIN_X);
        assertThat(cog.originY()).isEqualTo(ORIGIN_Y);
        assertThat(cog.nodata()).isEqualTo(0);
        assertThat(cog.levels()).extracting(HttpCogReader.Level::width).containsExactly(600, 300, 150, 75);
        assertThat(cog.levels().get(1).pixelSizeX()).isEqualTo(20);
        assertThat(cog.levels().get(3).pixelSizeY()).isCloseTo(5000.0 / 62, offset(1e-9));
        assertThat(cog.levelFor(10).width()).isEqualTo(600);
        assertThat(cog.levelFor(25).width()).isEqualTo(300);
        assertThat(cog.levelFor(5).width()).isEqualTo(600);
        assertThat(cog.levelFor(1000).width()).isEqualTo(75);
    }

    @Test
    void alignedGridReadsTheSourceValues() throws Exception {
        // Spans tiles 0-1 across and 0-1 down
        RasterGrid grid = grid(100, 90, 70, 60, 10);
        Raster nearest = reader.read(url, grid, Resampling.NEAREST);
        Raster bilinear = reader.read(url, grid, Resampling.BILINEAR);
        for (int row = 0; row < grid.height(); row++) {
            for (int col = 0; col < grid.width(); col++) {
                assertThat(nearest.get(col, row)).isEqualTo(expected(100 + col, 90 + row));
                if (value(100 + col, 90 + row) != 0) {
                    assertThat(bilinear.get(col, row)).isCloseTo(value(100 + col, 90 + row), offset(1e-2f));
                }
            }
        }
    }

    @Test
    void nodataIsNaN() throws Exception {
        Raster raster = reader.read(url, grid(190, 95, 20, 10, 10), Resampling.NEAREST);
        assertThat(raster.get(9, 4)).isEqualTo(value(199, 99));
        assertThat(raster.get(10, 5)).isNaN(); // (200, 100)
        assertThat(raster.get(19, 9)).isNaN();
        assertThat(raster.get(9, 9)).isEqualTo(value(199, 104));
        Raster bilinear = reader.read(url, grid(190, 95, 20, 10, 10), Resampling.BILINEAR);
        assertThat(bilinear.get(15, 7)).isNaN();
    }

    @Test
    void bilinearAveragesNeighboursAndSkipsNodata() throws Exception {
        // Shifted half a pixel: every grid centre is the corner of four source pixels
        RasterGrid grid = new RasterGrid(4, 4, ORIGIN_X + 50 * 10 + 5, ORIGIN_Y - 60 * 10 - 5, 10, EPSG);
        Raster raster = reader.read(url, grid, Resampling.BILINEAR);
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 4; col++) {
                int c = 50 + col;
                int r = 60 + row;
                double mean = (value(c, r) + value(c + 1, r) + value(c, r + 1) + value(c + 1, r + 1)) / 4.0;
                assertThat(raster.get(col, row)).isCloseTo((float) mean, offset(1e-2f));
            }
        }
        // Between columns 199 (valid) and 200 (nodata): only the valid pair counts
        RasterGrid edge = new RasterGrid(1, 1, ORIGIN_X + 199 * 10 + 5, ORIGIN_Y - 120 * 10 - 5, 10, EPSG);
        float v = reader.read(url, edge, Resampling.BILINEAR).get(0, 0);
        assertThat(v).isCloseTo((value(199, 120) + value(199, 121)) / 2f, offset(1e-2f));
    }

    @Test
    void coarserGridUsesTheOverview() throws Exception {
        reader.header(url);
        fetcher.reset();
        // 20 m grid: the nearest overview keeps source pixel (2i, 2j) of the full resolution
        RasterGrid grid = grid(30, 20, 40, 30, 20);
        Raster raster = reader.read(url, grid, Resampling.NEAREST);
        for (int row = 0; row < grid.height(); row++) {
            for (int col = 0; col < grid.width(); col++) {
                // Full resolution at 20 m would pick (2i + 1, 2j + 1) instead
                assertThat(raster.get(col, row)).isEqualTo(expected(2 * (30 + col), 2 * (20 + row)));
            }
        }
        long overviewBytes = fetcher.bytes.get();

        fetcher.reset();
        reader.read(url, grid(60, 40, 80, 60, 10), Resampling.NEAREST);
        assertThat(overviewBytes).isLessThan(fetcher.bytes.get() / 2);
    }

    @Test
    void windowPartlyOutsideTheImageIsNaNThere() throws Exception {
        // 10 columns west of the image and 5 rows north of it
        RasterGrid grid = new RasterGrid(30, 20, ORIGIN_X - 100, ORIGIN_Y + 50, 10, EPSG);
        Raster raster = reader.read(url, grid, Resampling.BILINEAR);
        for (int row = 0; row < grid.height(); row++) {
            for (int col = 0; col < grid.width(); col++) {
                if (col < 10 || row < 5) {
                    assertThat(raster.get(col, row)).isNaN();
                } else {
                    assertThat(raster.get(col, row)).isCloseTo(value(col - 10, row - 5), offset(1e-2f));
                }
            }
        }
        // South-east corner
        Raster corner = reader.read(url, grid(595, 495, 10, 10, 10), Resampling.NEAREST);
        assertThat(corner.get(4, 4)).isEqualTo(value(599, 499));
        assertThat(corner.get(5, 4)).isNaN();
        assertThat(corner.get(4, 5)).isNaN();
    }

    @Test
    void windowOutsideTheImageFetchesNoTile() throws Exception {
        reader.header(url);
        fetcher.reset();
        Raster raster = reader.read(url, grid(1000, 0, 10, 10, 10), Resampling.BILINEAR);
        assertThat(raster.values()).containsOnly(Float.NaN);
        assertThat(fetcher.requests.get()).isZero();
    }

    @Test
    void headerIsReadOnceAndCloseTilesInOneRequest() throws Exception {
        reader.read(url, grid(0, 0, 10, 10, 10), Resampling.NEAREST);
        assertThat(fetcher.headerRequests.get()).isEqualTo(1);
        fetcher.reset();
        // Tiles (0..2, 0..1): the tiles between them are less than 64 KB, so one range
        reader.read(url, grid(0, 0, 300, 200, 10), Resampling.NEAREST);
        assertThat(fetcher.headerRequests.get()).isZero();
        assertThat(fetcher.requests.get()).isEqualTo(1);
    }

    @Test
    void rejectsAGridInAnotherCrs() {
        RasterGrid grid = new RasterGrid(10, 10, ORIGIN_X, ORIGIN_Y, 10, 32720);
        assertThatThrownBy(() -> reader.read(url, grid, Resampling.NEAREST))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32720");
    }

    /**
     * {@code cog/rain-4326.tif}: a clip like BR Weather's CHIRPS days (GDAL 3.8, float32, EPSG:4326,
     * 256 px tiles, DEFLATE + floating point predictor 3, nodata -9999), 300 x 260 pixels of 0.05
     * degree from (-74.5, 6.0); value ((col * 3 + row * 7) % 97) / 4, nodata at rows 40-59, columns 250-279.
     */
    @Test
    void readsFloatSamplesWithTheFloatingPointPredictorInLonLat() throws Exception {
        String rain = Path.of(getClass().getResource("/cog/rain-4326.tif").toURI()).toString();
        HttpCogReader.Cog cog = reader.header(rain);
        assertThat(cog.epsg()).isEqualTo(4326);
        assertThat(cog.originX()).isCloseTo(-74.5, offset(1e-9));
        assertThat(cog.originY()).isCloseTo(6.0, offset(1e-9));
        assertThat(cog.nodata()).isEqualTo(-9999.0);

        // Crosses the tile border (256) on both axes and reaches the last row
        RasterGrid grid = new RasterGrid(40, 30, -74.5 + 240 * 0.05, 6.0 - 230 * 0.05, 0.05, 4326);
        Raster raster = reader.read(rain, grid, Resampling.NEAREST);
        for (int row = 0; row < grid.height(); row++) {
            for (int col = 0; col < grid.width(); col++) {
                int c = 240 + col;
                int r = 230 + row;
                float expected = r >= 40 && r < 60 && c >= 250 && c < 280 ? Float.NaN : ((c * 3 + r * 7) % 97) / 4.0f;
                assertThat(raster.get(col, row)).as("col %d row %d", c, r).isEqualTo(expected);
            }
        }
        RasterGrid nodata = new RasterGrid(5, 5, -74.5 + 255 * 0.05, 6.0 - 42 * 0.05, 0.05, 4326);
        assertThat(reader.read(rain, nodata, Resampling.NEAREST).values()).containsOnly(Float.NaN);
    }
}
