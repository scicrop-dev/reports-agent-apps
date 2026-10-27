package com.infinitestack.reports.blocks.sathub.imagery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

import org.junit.jupiter.api.Test;

class IndexStatsTest {

    @Test
    void statisticsOverValidNonNanPixels() {
        float[] values = {1, 2, 3, 4, Float.NaN, 100};
        boolean[] valid = {true, true, true, true, true, false};
        IndexStats s = IndexStats.of(values, valid);
        assertThat(s.validPixels()).isEqualTo(4);
        assertThat(s.totalPixels()).isEqualTo(5);
        assertThat(s.validPercent()).isCloseTo(80, offset(1e-9));
        assertThat(s.mean()).isCloseTo(2.5, offset(1e-9));
        assertThat(s.min()).isEqualTo(1);
        assertThat(s.max()).isEqualTo(4);
        assertThat(s.stddev()).isCloseTo(Math.sqrt(1.25), offset(1e-9)); // population
        // numpy.percentile([1, 2, 3, 4], [10, 50, 90]) = 1.3, 2.5, 3.7
        assertThat(s.p10()).isCloseTo(1.3, offset(1e-9));
        assertThat(s.p50()).isCloseTo(2.5, offset(1e-9));
        assertThat(s.p90()).isCloseTo(3.7, offset(1e-9));
    }

    @Test
    void singleValue() {
        IndexStats s = IndexStats.of(new float[] {0.5f}, new boolean[] {true});
        assertThat(s.p10()).isEqualTo(0.5);
        assertThat(s.p90()).isEqualTo(0.5);
        assertThat(s.stddev()).isZero();
    }

    @Test
    void emptyGivesNan() {
        IndexStats s = IndexStats.of(new float[] {Float.NaN, 3}, new boolean[] {true, false});
        assertThat(s.validPixels()).isZero();
        assertThat(s.totalPixels()).isEqualTo(1);
        assertThat(s.mean()).isNaN();
        assertThat(s.p50()).isNaN();
        assertThat(s.validPercent()).isZero();
        assertThat(IndexStats.of(new float[0], new boolean[0]).validPercent()).isZero();
    }
}
