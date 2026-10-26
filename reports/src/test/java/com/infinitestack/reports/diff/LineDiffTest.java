package com.infinitestack.reports.diff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.infinitestack.reports.diff.LineDiff.Line;
import com.infinitestack.reports.diff.LineDiff.Op;

class LineDiffTest {

    @Test
    void alignsChangesBetweenCommonLines() {
        List<Line> lines = LineDiff.diff("<h1>{{a.b}}</h1>\n<p>old</p>\n<footer/>", "<h1>{{a.b}}</h1>\n<p>new</p>\n<p>more</p>\n<footer/>");
        assertEquals(List.of(new Line(Op.SAME, "<h1>{{a.b}}</h1>"), new Line(Op.REMOVED, "<p>old</p>"),
                new Line(Op.ADDED, "<p>new</p>"), new Line(Op.ADDED, "<p>more</p>"), new Line(Op.SAME, "<footer/>")), lines);
        assertTrue(LineDiff.changed(lines));
    }

    @Test
    void identicalAndEmptyTexts() {
        assertFalse(LineDiff.changed(LineDiff.diff("a\nb", "a\nb")));
        assertEquals(List.of(new Line(Op.ADDED, "x")), LineDiff.diff("", "x"));
        assertEquals(List.of(new Line(Op.REMOVED, "x")), LineDiff.diff("x", null));
    }

    @Test
    void hugeMiddlesFallBackToRemoveThenAdd() {
        StringBuilder a = new StringBuilder();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 2_100; i++) {
            a.append("a").append(i).append('\n');
            b.append("b").append(i).append('\n');
        }
        List<Line> lines = LineDiff.diff(a.toString(), b.toString());
        assertEquals(Op.REMOVED, lines.get(0).op());
        assertEquals(Op.ADDED, lines.get(lines.size() - 2).op());
    }
}
