package com.infinitestack.reports.blocks.skillslab;

import java.io.IOException;
import java.util.Map;

/**
 * Workflow runs on the IS host, as the internal port {@code /api/extensions/internal/workflows}
 * offers them to an agent app's backend (infinitestack-back#1280). An interface so the Block can be
 * tested without a host.
 */
public interface WorkflowRuns {

    /**
     * Starts a run of a workflow granted to this app.
     *
     * @param params     merged into the {@code --context} of the workflow's first script, this run only
     * @param onBehalfOf the IS user who runs the report, or null; the host checks their access
     * @return the run's triggerId
     */
    String start(String projectId, String workflow, Map<String, Object> params, String onBehalfOf) throws IOException;

    RunState get(String projectId, String triggerId) throws IOException;

    /** Up to {@code max} bytes of the run's output log from byte {@code from}. */
    OutputSlice output(String projectId, String triggerId, long from, int max) throws IOException;

    /** @return false when the run was not running any more */
    boolean stop(String projectId, String triggerId) throws IOException;

    /**
     * @param result RUNNING, QUEUED, SUCCESS, WARNING, FAILED, INTERRUPTED or UNKNOWN
     */
    record RunState(boolean done, String result) {}

    /**
     * @param exists false while the run has not written any output yet
     * @param size   the whole log's size in bytes
     * @param next   the offset after this slice
     */
    record OutputSlice(boolean exists, long size, long next, boolean more, String content) {}
}
