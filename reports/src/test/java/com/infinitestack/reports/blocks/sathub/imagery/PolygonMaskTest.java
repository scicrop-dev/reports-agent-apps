package com.infinitestack.reports.blocks.sathub.imagery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

import java.util.List;

import org.junit.jupiter.api.Test;

class PolygonMaskTest {

    /** 10 x 10 pixels of 10 m, top-left corner at (1000, 2000). */
    private static final RasterGrid GRID = new RasterGrid(10, 10, 1000, 2000, 10, 32722);

    /** Axis-aligned square ring from pixel edges (c0, r0) to (c1, r1), in grid metres, closed. */
    static double[][] square(double c0, double r0, double c1, double r1) {
        double x0 = 1000 + c0 * 10;
        double x1 = 1000 + c1 * 10;
        double y0 = 2000 - r0 * 10;
        double y1 = 2000 - r1 * 10;
        return new double[][] {{x0, y0}, {x1, y0}, {x1, y1}, {x0, y1}, {x0, y0}};
    }

    private static boolean at(PolygonMask mask, int col, int row) {
        return mask.inside()[row * GRID.width() + col];
    }

    @Test
    void square() {
        PolygonMask mask = PolygonMask.of(GRID, List.of(List.<double[][]>of(square(2, 3, 6, 5))));
        assertThat(mask.insideCount()).isEqualTo(4 * 2);
        assertThat(at(mask, 2, 3)).isTrue();
        assertThat(at(mask, 5, 4)).isTrue();
        assertThat(at(mask, 6, 4)).isFalse();
        assertThat(at(mask, 2, 5)).isFalse();
        double[][] ring = mask.pixelRings().get(0);
        assertThat(ring[0][0]).isCloseTo(2, offset(1e-9));
        assertThat(ring[0][1]).isCloseTo(3, offset(1e-9));
        assertThat(ring[2][0]).isCloseTo(6, offset(1e-9));
        assertThat(ring[2][1]).isCloseTo(5, offset(1e-9));
    }

    @Test
    void holeStaysEmpty() {
        PolygonMask mask = PolygonMask.of(GRID, List.of(List.<double[][]>of(square(1, 1, 9, 9), square(3, 3, 5, 5))));
        assertThat(mask.insideCount()).isEqualTo(64 - 4);
        assertThat(at(mask, 3, 3)).isFalse();
        assertThat(at(mask, 4, 4)).isFalse();
        assertThat(at(mask, 2, 2)).isTrue();
        assertThat(at(mask, 5, 5)).isTrue();
        assertThat(mask.pixelRings()).hasSize(2);
    }

    @Test
    void multiPolygonAndOpenRings() {
        double[][] open = {{1000 + 70, 2000 - 70}, {1000 + 90, 2000 - 70}, {1000 + 90, 2000 - 90}, {1000 + 70, 2000 - 90}};
        PolygonMask mask = PolygonMask.of(GRID, List.of(List.<double[][]>of(square(0, 0, 2, 2)), List.<double[][]>of(open)));
        assertThat(mask.insideCount()).isEqualTo(4 + 4);
        assertThat(at(mask, 0, 0)).isTrue();
        assertThat(at(mask, 8, 8)).isTrue();
        assertThat(at(mask, 5, 5)).isFalse();
    }

    @Test
    void polygonPartlyOutsideTheGrid() {
        PolygonMask mask = PolygonMask.of(GRID, List.of(List.<double[][]>of(square(-5, -5, 3, 2))));
        assertThat(mask.insideCount()).isEqualTo(3 * 2);
        assertThat(at(mask, 0, 0)).isTrue();
        assertThat(at(mask, 2, 1)).isTrue();
        assertThat(at(mask, 3, 0)).isFalse();
        PolygonMask outside = PolygonMask.of(GRID, List.of(List.<double[][]>of(square(20, 20, 30, 30))));
        assertThat(outside.insideCount()).isZero();
    }

    @Test
    void triangleUsesPixelCentres() {
        // right triangle above the diagonal: centre (c + .5, r + .5) is inside when c >= r (centres
        // exactly on a left edge count, spans are [x0, x1))
        double[][] tri = {{1000, 2000}, {1100, 2000}, {1100, 1900}};
        PolygonMask mask = PolygonMask.of(GRID, List.of(List.<double[][]>of(tri)));
        assertThat(mask.insideCount()).isEqualTo(55);
        assertThat(at(mask, 4, 4)).isTrue();
        assertThat(at(mask, 3, 4)).isFalse();
        assertThat(at(mask, 9, 0)).isTrue();
        assertThat(at(mask, 0, 9)).isFalse();
    }
}
