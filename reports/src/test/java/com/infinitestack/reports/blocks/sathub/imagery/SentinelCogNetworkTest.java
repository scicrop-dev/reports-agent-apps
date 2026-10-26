package com.infinitestack.reports.blocks.sathub.imagery;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * The COG reader on a real Sentinel-2 L2A scene on AWS (tile 19LCL, 2026-10-25): EPSG:32719, origin
 * (300000, 9100000), B04 10 m (1024 px tiles, overviews 5490/2745/1373/687) and SCL 20 m (8-bit).
 * Expected values from GDAL 3.8 ({@code gdallocationinfo /vsicurl/...}, and
 * {@code gdal_translate -oo OVERVIEW_LEVEL=0} for the 20 m overview). Needs internet:
 * {@code SENTINEL_NETWORK_TEST=1 mvn test -Dtest=SentinelCogNetworkTest}.
 */
@EnabledIfEnvironmentVariable(named = "SENTINEL_NETWORK_TEST", matches = "1")
class SentinelCogNetworkTest {

    private static final String SCENE = "https://sentinel-cogs.s3.us-west-2.amazonaws.com/sentinel-s2-l2a-cogs/"
            + "19/L/CL/2026/10/S2C_19LCL_20261025_0_L2A/";
    private static final int EPSG = 32719;

    /** Counts what goes over the wire. */
    private static final class CountingFetcher implements RangeFetcher {
        final RangeFetcher http = new HttpRangeFetcher(Duration.ofSeconds(30));
        final AtomicInteger requests = new AtomicInteger();
        final AtomicLong bytes = new AtomicLong();

        @Override
        public byte[] fetch(String url, long offset, int length) throws IOException {
            byte[] out = http.fetch(url, offset, length);
            requests.incrementAndGet();
            bytes.addAndGet(out.length);
            return out;
        }
    }

    @Test
    void twoKilometresAtFullResolutionMatchGdal() throws IOException {
        CountingFetcher fetcher = new CountingFetcher();
        // Source pixels 5000..5199 of the 10 m band
        RasterGrid grid = new RasterGrid(200, 200, 350_000, 9_050_000, 10, EPSG);
        long start = System.nanoTime();
        Raster b04 = new HttpCogReader(fetcher).read(SCENE + "B04.tif", grid, Resampling.NEAREST);
        System.out.printf("B04 2x2 km at 10 m: %d requests, %d bytes, %d ms%n",
                fetcher.requests.get(), fetcher.bytes.get(), (System.nanoTime() - start) / 1_000_000);
        assertThat(b04.get(0, 0)).isEqualTo(271);
        assertThat(b04.get(3, 10)).isEqualTo(249);
        assertThat(b04.get(150, 199)).isEqualTo(247);
        assertThat(b04.get(199, 0)).isEqualTo(252);
        assertThat(b04.get(100, 100)).isEqualTo(224);

        // SCL is 8-bit at 20 m: source pixels 2500..2599
        RasterGrid scl = new RasterGrid(100, 100, 350_000, 9_050_000, 20, EPSG);
        Raster classes = new HttpCogReader(fetcher).read(SCENE + "SCL.tif", scl, Resampling.NEAREST);
        assertThat(classes.get(0, 0)).isEqualTo(4);
        assertThat(classes.get(50, 60)).isEqualTo(4);
        assertThat(classes.get(99, 99)).isEqualTo(4);
    }

    @Test
    void twentyKilometresAtTwentyMetresReadTheOverview() throws IOException {
        CountingFetcher fetcher = new CountingFetcher();
        // Overview pixels 2500..3499 of the 20 m overview
        RasterGrid grid = new RasterGrid(1000, 1000, 350_000, 9_050_000, 20, EPSG);
        long start = System.nanoTime();
        Raster b04 = new HttpCogReader(fetcher).read(SCENE + "B04.tif", grid, Resampling.BILINEAR);
        long millis = (System.nanoTime() - start) / 1_000_000;
        System.out.printf("B04 20x20 km at 20 m: %d requests, %d bytes, %d ms%n",
                fetcher.requests.get(), fetcher.bytes.get(), millis);
        assertThat(b04.get(0, 0)).isEqualTo(271f);
        assertThat(b04.get(200, 400)).isEqualTo(242f);
        assertThat(b04.get(999, 999)).isEqualTo(276f);
        assertThat(b04.get(500, 100)).isEqualTo(246f);
        // Overview tiles only: far below the ~200 MB of the band
        assertThat(fetcher.bytes.get()).isLessThan(20_000_000L);
    }
}
