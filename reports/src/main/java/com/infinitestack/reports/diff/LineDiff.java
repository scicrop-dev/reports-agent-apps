package com.infinitestack.reports.diff;

import java.util.ArrayList;
import java.util.List;

/**
 * Line diff for comparing two versions of a report. Common head and tail are cut first, then the
 * rest is aligned by longest common subsequence. Very large differing middles (above
 * {@link #MAX_CELLS}) are shown as removed-then-added instead of aligned, to bound memory.
 */
public final class LineDiff {

    static final long MAX_CELLS = 4_000_000;

    public enum Op { SAME, ADDED, REMOVED }

    public record Line(Op op, String text) {}

    private LineDiff() {}

    public static List<Line> diff(String before, String after) {
        String[] a = split(before);
        String[] b = split(after);
        int head = 0;
        while (head < a.length && head < b.length && a[head].equals(b[head])) head++;
        int tail = 0;
        while (tail < a.length - head && tail < b.length - head
                && a[a.length - 1 - tail].equals(b[b.length - 1 - tail])) tail++;

        List<Line> out = new ArrayList<>();
        for (int i = 0; i < head; i++) out.add(new Line(Op.SAME, a[i]));
        middle(a, head, a.length - tail, b, head, b.length - tail, out);
        for (int i = a.length - tail; i < a.length; i++) out.add(new Line(Op.SAME, a[i]));
        return out;
    }

    public static boolean changed(List<Line> lines) {
        return lines.stream().anyMatch(l -> l.op() != Op.SAME);
    }

    private static void middle(String[] a, int a0, int a1, String[] b, int b0, int b1, List<Line> out) {
        int n = a1 - a0;
        int m = b1 - b0;
        if ((long) n * m > MAX_CELLS) {
            for (int i = a0; i < a1; i++) out.add(new Line(Op.REMOVED, a[i]));
            for (int j = b0; j < b1; j++) out.add(new Line(Op.ADDED, b[j]));
            return;
        }
        int[][] lcs = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                lcs[i][j] = a[a0 + i].equals(b[b0 + j]) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        int i = 0, j = 0;
        while (i < n && j < m) {
            if (a[a0 + i].equals(b[b0 + j])) {
                out.add(new Line(Op.SAME, a[a0 + i]));
                i++;
                j++;
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                out.add(new Line(Op.REMOVED, a[a0 + i++]));
            } else {
                out.add(new Line(Op.ADDED, b[b0 + j++]));
            }
        }
        while (i < n) out.add(new Line(Op.REMOVED, a[a0 + i++]));
        while (j < m) out.add(new Line(Op.ADDED, b[b0 + j++]));
    }

    private static String[] split(String text) {
        if (text == null || text.isEmpty()) return new String[0];
        return text.split("\r?\n", -1);
    }
}
