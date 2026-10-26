package com.infinitestack.reports.execution;

/** Execution lifecycle (ARCHITECTURE sections 23-24). GENERATING_OUTPUT arrives with the PDF (Phase 5). */
public enum ExecutionStatus {
    CREATED, VALIDATING, RUNNING, RENDERING, GENERATING_OUTPUT, COMPLETED, FAILED, CANCELLED
}
