package com.infinitestack.reports.blocks.sathub.imagery;

import java.util.Arrays;

/**
 * Statistics of an index over the valid pixels (inside the area, not cloud, not NaN), the same set
 * Satellite Hub stores: mean, min, max, standard deviation (population), p10, p50 (median), p90.
 * Percentiles interpolate linearly between order statistics (numpy's default "linear" method:
 * rank = q * (n - 1)). {@code totalPixels} counts every pixel flagged valid, NaN ones included, so
 * {@link #validPercent()} tells how much of the area had a usable value.
 */
public record IndexStats(int validPixels, int totalPixels, double mean, double min, double max, double stddev,
                         double p10, double p50, double p90) {

    /** @param valid pixels to count; NaN values are skipped even when valid */
    public static IndexStats of(float[] values, boolean[] valid) {
        int total = 0;
        double[] usable = new double[values.length];
        int n = 0;
        for (int i = 0; i < values.length; i++) {
            if (!valid[i]) continue;
            total++;
            if (!Float.isNaN(values[i])) usable[n++] = values[i];
        }
        if (n == 0) return new IndexStats(0, total, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                Double.NaN, Double.NaN, Double.NaN);
        double[] sorted = Arrays.copyOf(usable, n);
        Arrays.sort(sorted);
        double sum = 0;
        for (double v : sorted) sum += v;
        double mean = sum / n;
        double squares = 0;
        for (double v : sorted) squares += (v - mean) * (v - mean);
        return new IndexStats(n, total, mean, sorted[0], sorted[n - 1], Math.sqrt(squares / n),
                percentile(sorted, 0.10), percentile(sorted, 0.50), percentile(sorted, 0.90));
    }

    /**
     * Linear-interpolated percentile of a sorted, non-empty array (numpy "linear"). Public so the
     * renderers' stretch uses the same definition.
     *
     * @param q quantile in 0..1
     */
    public static double percentile(double[] sorted, double q) {
        double rank = q * (sorted.length - 1);
        int lo = (int) Math.floor(rank);
        int hi = Math.min(sorted.length - 1, lo + 1);
        return sorted[lo] + (sorted[hi] - sorted[lo]) * (rank - lo);
    }

    /** Valid share of {@code totalPixels}, percent. */
    public double validPercent() {
        return totalPixels == 0 ? 0 : 100.0 * validPixels / totalPixels;
    }
}
