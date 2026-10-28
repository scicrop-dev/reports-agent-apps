package com.infinitestack.reports.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.infinitestack.reports.config.ReportsLimits;
import com.infinitestack.reports.engine.block.BlockRegistry;
import com.infinitestack.reports.engine.block.ExecutionMetadata;
import com.infinitestack.reports.engine.flow.BlockRun;
import com.infinitestack.reports.engine.flow.FlowConnection;
import com.infinitestack.reports.engine.flow.FlowDefinition;
import com.infinitestack.reports.engine.flow.FlowExecutor;
import com.infinitestack.reports.engine.flow.FlowNode;
import com.infinitestack.reports.engine.flow.FlowRun;
import com.infinitestack.reports.engine.flow.FlowValidator;
import com.infinitestack.reports.report.InputDefinition;
import com.infinitestack.reports.support.TestBlocks;

class FlowExecutorTest {

    private final ReportsLimits limits = ReportsLimits.defaults();
    private final FlowValidator validator = new FlowValidator(new BlockRegistry(TestBlocks.all()), limits);
    private final FlowExecutor executor = new FlowExecutor(limits);
    private final ExecutionMetadata metadata = new ExecutionMetadata(null, 1, 1, "u", "USER");
    private final List<InputDefinition> inputs = List.of(
            new InputDefinition("name", DataType.STRING, true, "Name"),
            new InputDefinition("n", DataType.NUMBER, false, "N"));

    private FlowRun run(FlowDefinition flow, Map<String, Object> values) throws Exception {
        return executor.run(validator.validate(flow, inputs), values, metadata);
    }

    @Test
    void runsALinearFlowAndBuildsTheContext() throws Exception {
        FlowRun run = run(new FlowDefinition(
                List.of(new FlowNode("first", "test-sum", Map.of("b", 2)), new FlowNode("second", "test-sum", Map.of("b", 10))),
                List.of(new FlowConnection("inputs.n", "first.a"), new FlowConnection("first.total", "second.a"))),
                Map.of("name", "x", "n", 1));
        assertTrue(run.completed());
        assertEquals(3.0, run.context().values().get("first").get("total"));
        assertEquals(13.0, run.context().values().get("second").get("total"));
        assertEquals(1, run.context().templateModel(Map.of()).get("inputs") instanceof Map<?, ?> m ? m.get("n") : null);
        assertEquals(List.of("first", "second"), run.blocks().stream().map(BlockRun::nodeId).toList());
    }

    @Test
    void runsABranchedFlow() throws Exception {
        FlowRun run = run(new FlowDefinition(
                List.of(new FlowNode("root", "test-upper", Map.of()), new FlowNode("left", "test-upper", Map.of()),
                        new FlowNode("right", "test-upper", Map.of())),
                List.of(new FlowConnection("inputs.name", "root.text"), new FlowConnection("root.text", "left.text"),
                        new FlowConnection("root.text", "right.text"))),
                Map.of("name", "garça"));
        assertTrue(run.completed());
        assertEquals("GARÇA", run.context().values().get("left").get("text"));
        assertEquals("GARÇA", run.context().values().get("right").get("text"));
    }

    @Test
    void stopsAtTheFirstFailureAndSaysWhere() throws Exception {
        FlowRun run = run(new FlowDefinition(
                List.of(new FlowNode("ok", "test-upper", Map.of("text", "a")), new FlowNode("broken", "test-failing", Map.of()),
                        new FlowNode("never", "test-upper", Map.of("text", "b"))),
                List.of()), Map.of("name", "x"));
        assertEquals("broken", run.failure().nodeId());
        assertEquals("test-failing", run.failure().blockId());
        assertEquals("coordinates outside supported geometry", run.failure().message());
        assertEquals(2, run.blocks().size());
        assertEquals(BlockRun.Status.FAILED, run.blocks().get(1).status());
        assertNull(run.context().values().get("never"));
    }

    @Test
    void skipsAnOptionalNodeWhoseRequiredInputIsEmptyAndTheOptionalNodesFedByIt() throws Exception {
        // test-upper's "text" is required; "n" is an optional report input left empty.
        FlowRun run = run(new FlowDefinition(
                List.of(new FlowNode("sum", "test-sum", Map.of("b", 1), null, true),
                        new FlowNode("after", "test-sum", Map.of("b", 1), null, true),
                        new FlowNode("upper", "test-upper", Map.of())),
                List.of(new FlowConnection("inputs.n", "sum.a"), new FlowConnection("sum.total", "after.a"),
                        new FlowConnection("inputs.name", "upper.text"))),
                java.util.Collections.singletonMap("name", "x"));
        assertTrue(run.completed(), String.valueOf(run.failure()));
        assertEquals(List.of(BlockRun.Status.SKIPPED, BlockRun.Status.SKIPPED, BlockRun.Status.COMPLETED),
                run.blocks().stream().map(BlockRun::status).toList());
        // Present with null outputs, so a strict template can still name them.
        assertTrue(run.context().values().get("sum").containsKey("total"));
        assertNull(run.context().values().get("sum").get("total"));
        assertEquals("X", run.context().values().get("upper").get("text"));
    }

    @Test
    void anOptionalNodeThatFailsDoesNotStopTheFlow() throws Exception {
        FlowRun run = run(new FlowDefinition(
                List.of(new FlowNode("broken", "test-failing", Map.of(), null, true), new FlowNode("next", "test-upper", Map.of("text", "b"))),
                List.of()), Map.of("name", "x"));
        assertTrue(run.completed(), String.valueOf(run.failure()));
        assertEquals(BlockRun.Status.FAILED, run.blocks().get(0).status());
        assertTrue(run.blocks().get(0).error().contains("coordinates outside supported geometry"), run.blocks().get(0).error());
        assertEquals("B", run.context().values().get("next").get("text"));
    }

    @Test
    void aRequiredNodeFedByASkippedOneStillFails() throws Exception {
        FlowRun run = run(new FlowDefinition(
                List.of(new FlowNode("sum", "test-sum", Map.of("b", 1), null, true), new FlowNode("after", "test-sum", Map.of("b", 1))),
                List.of(new FlowConnection("inputs.n", "sum.a"), new FlowConnection("sum.total", "after.a"))),
                java.util.Collections.singletonMap("name", "x"));
        assertEquals("after", run.failure().nodeId());
    }

    @Test
    void enforcesTheBlockTimeout() throws Exception {
        long start = System.currentTimeMillis();
        FlowRun run = run(new FlowDefinition(List.of(new FlowNode("slow", "test-slow", Map.of())), List.of()), Map.of("name", "x"));
        assertEquals(BlockRun.Status.TIMED_OUT, run.blocks().get(0).status());
        assertTrue(System.currentTimeMillis() - start < 2_000, "the timeout did not cut the block");
    }

    @Test
    void anOptionalInputWithoutValueIsLeftOut() throws Exception {
        FlowRun run = run(new FlowDefinition(
                List.of(new FlowNode("s", "test-sum", Map.of("a", 5))),
                List.of(new FlowConnection("inputs.n", "s.b"))), Map.of("name", "x"));
        assertTrue(run.completed());
        assertEquals(5.0, run.context().values().get("s").get("total"));
    }
}
