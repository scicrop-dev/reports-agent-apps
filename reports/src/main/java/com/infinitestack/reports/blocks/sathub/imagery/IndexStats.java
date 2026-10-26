package com.infinitestack.reports.blocks.sathub.imagery;

/**
 * Statistics of an index over the valid pixels (inside the area, not cloud, not NaN), the same set
 * Satellite Hub stores: mean, min, max, standard deviation, p10, p50 (median), p90.
 *
 * <p>OWNER: agent 3 (rendering). Stub until implemented.
 */
public record IndexStats(int validPixels, int totalPixels, double mean, double min, double max, double stddev,
                         double p10, double p50, double p90) {

    /** @param valid pixels to count; NaN values are skipped even when valid */
    public static IndexStats of(float[] values, boolean[] valid) {
        throw new UnsupportedOperationException("IndexStats not implemented yet");
    }

    /** Valid share of {@code totalPixels}, percent. */
    public double validPercent() {
        return totalPixels == 0 ? 0 : 100.0 * validPixels / totalPixels;
    }
}
