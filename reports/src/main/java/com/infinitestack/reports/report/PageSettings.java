package com.infinitestack.reports.report;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Paper of the report: size, orientation and margins. Turned into a CSS {@code @page} rule that
 * comes before the template's own CSS, so a template can still refine it (for example with
 * {@code @top-center} headers).
 */
public record PageSettings(String size, String orientation, Integer marginMm) {

    private static final Set<String> SIZES = Set.of("A3", "A4", "A5", "LETTER", "LEGAL");
    private static final Set<String> ORIENTATIONS = Set.of("portrait", "landscape");

    public static PageSettings defaults() {
        return new PageSettings("A4", "portrait", 20);
    }

    /** Fills what is missing with the defaults. */
    public static PageSettings orDefaults(PageSettings page) {
        if (page == null) return defaults();
        PageSettings d = defaults();
        return new PageSettings(page.size() == null ? d.size() : page.size().toUpperCase(),
                page.orientation() == null ? d.orientation() : page.orientation().toLowerCase(),
                page.marginMm() == null ? d.marginMm() : page.marginMm());
    }

    public List<String> problems() {
        List<String> problems = new ArrayList<>();
        PageSettings p = orDefaults(this);
        if (!SIZES.contains(p.size())) problems.add("page size must be one of " + SIZES);
        if (!ORIENTATIONS.contains(p.orientation())) problems.add("page orientation must be portrait or landscape");
        if (p.marginMm() < 0 || p.marginMm() > 60) problems.add("page margin must be between 0 and 60 mm");
        return problems;
    }

    public String css() {
        PageSettings p = orDefaults(this);
        return "@page { size: " + p.size() + " " + p.orientation() + "; margin: " + p.marginMm() + "mm; }\n";
    }
}
