package com.infinitestack.reports.engine.flow;

/**
 * Feeds an input port from a report input ({@code inputs.<name>}) or from another node's output
 * ({@code <node>.<port>}). The target is always {@code <node>.<port>}.
 */
public record FlowConnection(String from, String to) {
}
