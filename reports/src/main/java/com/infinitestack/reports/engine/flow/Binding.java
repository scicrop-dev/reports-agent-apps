package com.infinitestack.reports.engine.flow;

/** Where one input port of a node gets its value: a fixed value, a report input or another node. */
sealed interface Binding {

    record Fixed(Object value) implements Binding {}

    record FromInput(String inputName) implements Binding {}

    record FromNode(String nodeId, String port) implements Binding {}
}
