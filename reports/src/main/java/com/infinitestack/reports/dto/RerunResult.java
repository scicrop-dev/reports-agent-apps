package com.infinitestack.reports.dto;

import java.util.List;

import com.infinitestack.reports.execution.Execution;

/**
 * A re-run of a stored execution.
 *
 * @param identicalValues every Block produced the same outputs as the first time
 * @param warnings Blocks whose version changed or that no longer exist
 */
public record RerunResult(Execution execution, boolean identicalValues, List<String> warnings) {
}
