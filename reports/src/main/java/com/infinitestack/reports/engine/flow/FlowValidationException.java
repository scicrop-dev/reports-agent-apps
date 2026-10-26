package com.infinitestack.reports.engine.flow;

import java.util.List;

/** The Flow cannot run; {@link #problems()} lists every reason found, not only the first. */
public class FlowValidationException extends Exception {

    private final List<String> problems;

    public FlowValidationException(List<String> problems) {
        super("Invalid flow: " + String.join("; ", problems));
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }
}
