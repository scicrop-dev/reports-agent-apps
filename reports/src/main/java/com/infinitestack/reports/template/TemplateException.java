package com.infinitestack.reports.template;

import java.util.List;

/** The template cannot be rendered; {@link #problems()} says why. */
public class TemplateException extends Exception {

    private final List<String> problems;

    public TemplateException(List<String> problems) {
        super("Invalid template: " + String.join("; ", problems));
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }
}
