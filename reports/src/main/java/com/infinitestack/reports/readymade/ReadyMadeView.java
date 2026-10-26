package com.infinitestack.reports.readymade;

import java.util.List;

import com.infinitestack.reports.report.InputDefinition;

/**
 * A ready-made report as the catalog screen shows it: what it is, what it asks for, and whether
 * each Block its Flow needs is usable by the caller right now. {@code runnable} is the conclusion:
 * a report created from it can run only when every Block is usable (an admin releases them).
 */
public record ReadyMadeView(
        String id,
        String version,
        String name,
        String description,
        String category,
        List<InputDefinition> inputs,
        List<RequiredBlock> blocks,
        boolean runnable) {

    /** @param problem why the Block is not usable; null when it is */
    public record RequiredBlock(String id, boolean usable, String problem) {}

    /** The view plus the whole content (Flow, template, page) of the ready-made report. */
    public record Detail(ReadyMadeView summary, ReadyMadeReport content) {}

    /** Body of POST /ready-made/{id}/instantiate; both optional (default: the ready-made's own). */
    public record InstantiateRequest(String name, String description) {}
}
