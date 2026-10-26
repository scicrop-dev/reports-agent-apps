package com.infinitestack.reports.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.infinitestack.reports.config.ReportsLimits;
import com.infinitestack.reports.engine.block.BlockRegistry;
import com.infinitestack.reports.engine.flow.FlowConnection;
import com.infinitestack.reports.engine.flow.FlowDefinition;
import com.infinitestack.reports.engine.flow.FlowNode;
import com.infinitestack.reports.engine.flow.FlowValidationException;
import com.infinitestack.reports.engine.flow.FlowValidator;
import com.infinitestack.reports.engine.flow.ValidatedFlow;
import com.infinitestack.reports.report.InputDefinition;
import com.infinitestack.reports.support.TestBlocks;

class FlowValidatorTest {

    private final FlowValidator validator = new FlowValidator(new BlockRegistry(TestBlocks.all()), ReportsLimits.defaults());
    private final List<InputDefinition> inputs = List.of(
            new InputDefinition("name", DataType.STRING, true, "Name"),
            new InputDefinition("n", DataType.NUMBER, false, "N"));

    @Test
    void ordersNodesByDependencyNotByDeclaration() throws Exception {
        FlowDefinition flow = new FlowDefinition(
                List.of(new FlowNode("second", "test-sum", Map.of("b", 1)),
                        new FlowNode("first", "test-sum", Map.of("a", 2))),
                List.of(new FlowConnection("first.total", "second.a")));
        ValidatedFlow validated = validator.validate(flow, inputs);
        assertEquals(List.of("first", "second"), validated.steps().stream().map(s -> s.node().id()).toList());
    }

    @Test
    void rejectsACycle() {
        FlowDefinition flow = new FlowDefinition(
                List.of(new FlowNode("a", "test-sum", Map.of()), new FlowNode("b", "test-sum", Map.of())),
                List.of(new FlowConnection("a.total", "b.a"), new FlowConnection("b.total", "a.a")));
        FlowValidationException e = assertThrows(FlowValidationException.class, () -> validator.validate(flow, inputs));
        assertTrue(e.problems().stream().anyMatch(p -> p.contains("cycle")), e.problems().toString());
    }

    @Test
    void rejectsATypeMismatch() {
        FlowDefinition flow = new FlowDefinition(
                List.of(new FlowNode("upper", "test-upper", Map.of())),
                List.of(new FlowConnection("inputs.n", "upper.text")));
        FlowValidationException e = assertThrows(FlowValidationException.class, () -> validator.validate(flow, inputs));
        assertTrue(e.problems().get(0).contains("type mismatch"), e.problems().toString());
    }

    @Test
    void collectsEveryProblemAtOnce() {
        FlowDefinition flow = new FlowDefinition(
                List.of(new FlowNode("inputs", "test-sum", Map.of()),
                        new FlowNode("x", "no-such-block", Map.of()),
                        new FlowNode("y", "test-upper", Map.of()),
                        new FlowNode("z", "test-sum", Map.of("a", "not a number", "c", 1))),
                List.of(new FlowConnection("inputs.missing", "y.text"), new FlowConnection("bad", "y.text")));
        FlowValidationException e = assertThrows(FlowValidationException.class, () -> validator.validate(flow, inputs));
        String all = String.join("\n", e.problems());
        assertTrue(all.contains("reserved"), all);
        assertTrue(all.contains("unknown block 'no-such-block'"), all);
        assertTrue(all.contains("has no input 'missing'"), all);
        assertTrue(all.contains("use 'node.port'"), all);
        assertTrue(all.contains("required input 'text' is not bound"), all);
        assertTrue(all.contains("expected number"), all);
        assertTrue(all.contains("has no input 'c'"), all);
    }

    @Test
    void refusesTwoSourcesForTheSameInput() {
        FlowDefinition flow = new FlowDefinition(
                List.of(new FlowNode("up", "test-upper", Map.of("text", "fixed"))),
                List.of(new FlowConnection("inputs.name", "up.text")));
        FlowValidationException e = assertThrows(FlowValidationException.class, () -> validator.validate(flow, inputs));
        assertTrue(e.problems().get(0).contains("already bound"), e.problems().toString());
    }

    @Test
    void enforcesTheNodeLimit() {
        FlowValidator small = new FlowValidator(new BlockRegistry(TestBlocks.all()), new ReportsLimits(1, 30, 120, 1000, 1000));
        FlowDefinition flow = new FlowDefinition(
                List.of(new FlowNode("a", "test-sum", Map.of("a", 1)), new FlowNode("b", "test-sum", Map.of("a", 1))), List.of());
        FlowValidationException e = assertThrows(FlowValidationException.class, () -> small.validate(flow, inputs));
        assertTrue(e.problems().get(0).contains("limit"), e.problems().toString());
    }
}
