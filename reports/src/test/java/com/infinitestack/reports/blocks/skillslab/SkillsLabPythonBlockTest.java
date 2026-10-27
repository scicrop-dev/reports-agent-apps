package com.infinitestack.reports.blocks.skillslab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.infinitestack.reports.engine.block.BlockRegistry;
import com.infinitestack.reports.engine.block.ExecutionMetadata;

class SkillsLabPythonBlockTest {

    static final ExecutionMetadata META = new ExecutionMetadata(7L, 1, 2, "user-42", "USER");

    /** A host whose run ends after {@code pollsUntilDone} polls with {@code result}, printing {@code log}. */
    static final class FakeRuns implements WorkflowRuns {
        final String result;
        final String log;
        final int pollsUntilDone;
        final int sliceChars;
        int polls;
        boolean stopped;
        String startedProject;
        String startedWorkflow;
        Map<String, Object> startedParams;
        String startedFor;
        final List<Long> reads = new ArrayList<>();

        FakeRuns(String result, String log, int pollsUntilDone, int sliceChars) {
            this.result = result;
            this.log = log;
            this.pollsUntilDone = pollsUntilDone;
            this.sliceChars = sliceChars;
        }

        FakeRuns(String result, String log) {
            this(result, log, 2, 1_000_000);
        }

        @Override
        public String start(String projectId, String workflow, Map<String, Object> params, String onBehalfOf) {
            startedProject = projectId;
            startedWorkflow = workflow;
            startedParams = params;
            startedFor = onBehalfOf;
            return "t-1";
        }

        @Override
        public RunState get(String projectId, String triggerId) {
            polls++;
            return polls >= pollsUntilDone ? new RunState(true, result) : new RunState(false, "RUNNING");
        }

        @Override
        public OutputSlice output(String projectId, String triggerId, long from, int max) {
            reads.add(from);
            int start = (int) Math.min(from, log.length());
            int end = Math.min(log.length(), start + sliceChars);
            return new OutputSlice(true, log.length(), end, end < log.length(), log.substring(start, end));
        }

        @Override
        public boolean stop(String projectId, String triggerId) {
            stopped = true;
            return true;
        }
    }

    static SkillsLabPythonBlock block(WorkflowRuns runs) {
        return new SkillsLabPythonBlock(runs, Duration.ofMillis(1));
    }

    static Map<String, Object> inputs(Object... pairs) {
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("projectId", "p1");
        inputs.put("workflow", "wf-report");
        for (int i = 0; i < pairs.length; i += 2) inputs.put((String) pairs[i], pairs[i + 1]);
        return inputs;
    }

    @Test
    void definitionIsValid() {
        assertTrue(new BlockRegistry(List.of(block(new FakeRuns("SUCCESS", "")))).find("skillslab-python").isPresent());
    }

    @Test
    void runsTheWorkflowAndReturnsTheLastResultLine() throws Exception {
        FakeRuns runs = new FakeRuns("SUCCESS",
                "loading\nIS_REPORTS_RESULT {\"draft\":true}\nworking\nIS_REPORTS_RESULT {\"summary\":\"ok\",\"total\":3}\ndone\n");
        Map<String, Object> geometry = Map.of("type", "Point", "coordinates", List.of(-70.1, -8.2));

        Map<String, Object> out = block(runs).execute(inputs(
                "params", Map.of("mode", "full", "text", "overwritten"),
                "text", "AC-1200302-X",
                "geometry", geometry), META);

        assertEquals("p1", runs.startedProject);
        assertEquals("wf-report", runs.startedWorkflow);
        assertEquals("user-42", runs.startedFor);
        assertEquals(Map.of("mode", "full", "text", "AC-1200302-X", "geometry", geometry), runs.startedParams);

        assertEquals(Map.of("summary", "ok", "total", 3), out.get("result"));
        assertEquals(true, out.get("hasResult"));
        assertEquals("SUCCESS", out.get("status"));
        assertEquals("t-1", out.get("triggerId"));
        assertEquals("loading\nworking\ndone", out.get("log"));
        assertFalse(runs.stopped);
    }

    @Test
    void readsALongLogSliceBySlice() throws Exception {
        FakeRuns runs = new FakeRuns("WARNING", "a\nb\nIS_REPORTS_RESULT {\"n\":1}\n", 1, 4);

        Map<String, Object> out = block(runs).execute(inputs(), META);

        assertEquals(Map.of("n", 1), out.get("result"));
        assertEquals("WARNING", out.get("status"));
        assertTrue(runs.reads.size() > 2);
    }

    @Test
    void noResultLineIsNotAFailure() throws Exception {
        Map<String, Object> out = block(new FakeRuns("SUCCESS", "only logs\n")).execute(inputs(), META);

        assertNull(out.get("result"));
        assertEquals(false, out.get("hasResult"));
    }

    @Test
    void aFailedRunFailsTheBlockWithTheEndOfTheLog() {
        FakeRuns runs = new FakeRuns("FAILED", "Traceback (most recent call last):\nKeyError: 'codImovel'\n");

        Exception e = assertThrows(IllegalStateException.class, () -> block(runs).execute(inputs(), META));

        assertTrue(e.getMessage().contains("ended FAILED"), e.getMessage());
        assertTrue(e.getMessage().contains("KeyError: 'codImovel'"), e.getMessage());
    }

    @Test
    void aRunPastTheTimeoutIsStopped() {
        FakeRuns runs = new FakeRuns("SUCCESS", "", Integer.MAX_VALUE, 10);
        SkillsLabPythonBlock slow = new SkillsLabPythonBlock(runs, Duration.ofMillis(200));

        Exception e = assertThrows(IllegalStateException.class,
                () -> slow.execute(inputs("timeoutSeconds", 1), META));

        assertTrue(e.getMessage().contains("did not finish within 5s"), e.getMessage()); // clamped to the minimum
        assertTrue(runs.stopped);
    }

    @Test
    void aResultThatIsNotAnObjectIsRejected() {
        FakeRuns runs = new FakeRuns("SUCCESS", "IS_REPORTS_RESULT [1, 2]\n");

        Exception e = assertThrows(IllegalArgumentException.class, () -> block(runs).execute(inputs(), META));

        assertTrue(e.getMessage().contains("not a JSON object"), e.getMessage());
    }

    @Test
    void projectAndWorkflowAreRequired() {
        Map<String, Object> missing = inputs("workflow", " ");
        assertThrows(IllegalArgumentException.class, () -> block(new FakeRuns("SUCCESS", "")).execute(missing, META));
    }

    @Test
    void timeoutIsClamped() {
        assertEquals(SkillsLabPythonBlock.DEFAULT_TIMEOUT_SECONDS, SkillsLabPythonBlock.timeoutSeconds(null));
        assertEquals(SkillsLabPythonBlock.MIN_TIMEOUT_SECONDS, SkillsLabPythonBlock.timeoutSeconds(1));
        assertEquals(SkillsLabPythonBlock.MAX_TIMEOUT_SECONDS, SkillsLabPythonBlock.timeoutSeconds(900));
        assertEquals(30, SkillsLabPythonBlock.timeoutSeconds(30.7));
    }
}
