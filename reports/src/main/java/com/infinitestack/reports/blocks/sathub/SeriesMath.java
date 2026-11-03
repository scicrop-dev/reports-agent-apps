package com.infinitestack.reports.blocks.sathub;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The arithmetic of the Satellite Hub comparison and anomaly Blocks, kept free of the database so it
 * is unit-tested on its own: matching the dates of two series, Pearson correlation, rolling median
 * and the same-month mean of earlier years.
 */
final class SeriesMath {

    private SeriesMath() {}

    /** One value at one date. */
    record Value(LocalDate date, double value) {}

    /** Two values taken as the same observation: one of each series, at most a few days apart. */
    record Match(Value a, Value b) {}

    /**
     * Pairs each value of {@code a} (oldest first) with the nearest still unpaired value of {@code b}
     * at most {@code maxDayGap} days away; ties go to the earlier date. Each value is used once, so
     * a series with images every 5 days does not pair many times with one every 16.
     */
    static List<Match> matchDates(List<Value> a, List<Value> b, int maxDayGap) {
        boolean[] used = new boolean[b.size()];
        List<Match> out = new ArrayList<>();
        for (Value va : a) {
            int best = -1;
            long bestGap = Long.MAX_VALUE;
            for (int j = 0; j < b.size(); j++) {
                if (used[j]) continue;
                long gap = Math.abs(ChronoUnit.DAYS.between(va.date(), b.get(j).date()));
                if (gap <= maxDayGap && gap < bestGap) {
                    best = j;
                    bestGap = gap;
                }
            }
            if (best >= 0) {
                used[best] = true;
                out.add(new Match(va, b.get(best)));
            }
        }
        return out;
    }

    /** Mean of a - b over the matches (the bias of a against b); null without matches. */
    static Double meanDifference(List<Match> matches) {
        if (matches.isEmpty()) return null;
        return matches.stream().mapToDouble(m -> m.a().value() - m.b().value()).average().orElseThrow();
    }

    /**
     * Pearson correlation of the matched values; null with fewer than 3 matches or when one side is
     * constant (the correlation is undefined then).
     */
    static Double correlation(List<Match> matches) {
        int n = matches.size();
        if (n < 3) return null;
        double meanA = 0, meanB = 0;
        for (Match m : matches) {
            meanA += m.a().value();
            meanB += m.b().value();
        }
        meanA /= n;
        meanB /= n;
        double cov = 0, varA = 0, varB = 0;
        for (Match m : matches) {
            double da = m.a().value() - meanA;
            double db = m.b().value() - meanB;
            cov += da * db;
            varA += da * da;
            varB += db * db;
        }
        if (varA <= 1e-12 || varB <= 1e-12) return null;
        return cov / Math.sqrt(varA * varB);
    }

    /**
     * For each position i, the median of the {@code window} values before it (i - window .. i - 1),
     * or of the ones there are when at least {@code minCount} exist; null before that. The value
     * itself is never part of its own baseline, so a sudden drop stands out against it.
     */
    static List<Double> rollingMedian(List<Double> values, int window, int minCount) {
        List<Double> out = new ArrayList<>(values.size());
        for (int i = 0; i < values.size(); i++) {
            int start = Math.max(0, i - window);
            if (i - start < Math.max(1, minCount)) {
                out.add(null);
                continue;
            }
            out.add(median(values.subList(start, i)));
        }
        return out;
    }

    static double median(List<Double> values) {
        double[] sorted = values.stream().mapToDouble(Double::doubleValue).toArray();
        Arrays.sort(sorted);
        int n = sorted.length;
        return n % 2 == 1 ? sorted[n / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2;
    }

    /** Mean of the values in one calendar month, per earlier year, and over all of them. */
    record SeasonalMean(int month, Map<Integer, Double> byYear, double mean, int observations) {}

    /**
     * The values of {@code history} in the month of {@code latest}, in the years before it. Null when
     * no earlier year has that month or the history is shorter than {@code minYears} years (the first
     * observation is less than {@code minYears} years before {@code latest}).
     */
    static SeasonalMean sameMonthEarlierYears(List<Value> history, LocalDate latest, int minYears) {
        if (history.isEmpty() || history.get(0).date().isAfter(latest.minusYears(minYears))) return null;
        Map<Integer, double[]> sums = new TreeMap<>();
        for (Value v : history) {
            if (v.date().getMonthValue() != latest.getMonthValue() || v.date().getYear() >= latest.getYear()) continue;
            double[] acc = sums.computeIfAbsent(v.date().getYear(), y -> new double[2]);
            acc[0] += v.value();
            acc[1]++;
        }
        if (sums.isEmpty()) return null;
        Map<Integer, Double> byYear = new TreeMap<>();
        double total = 0;
        int count = 0;
        for (Map.Entry<Integer, double[]> e : sums.entrySet()) {
            byYear.put(e.getKey(), e.getValue()[0] / e.getValue()[1]);
            total += e.getValue()[0];
            count += (int) e.getValue()[1];
        }
        return new SeasonalMean(latest.getMonthValue(), byYear, total / count, count);
    }
}
