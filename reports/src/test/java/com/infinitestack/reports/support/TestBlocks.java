package com.infinitestack.reports.support;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.infinitestack.reports.engine.DataType;
import com.infinitestack.reports.engine.block.BlockDefinition;
import com.infinitestack.reports.engine.block.BlockPort;
import com.infinitestack.reports.engine.block.DataRequirement;
import com.infinitestack.reports.engine.block.ExecutionMetadata;
import com.infinitestack.reports.engine.block.ReportBlock;

/**
 * Blocks that exist only for tests. They are not product Blocks: those are discussed one by one
 * in Phase 8 of the ROADMAP.
 */
public final class TestBlocks {

    private TestBlocks() {}

    public static List<ReportBlock> all() {
        return List.of(new Upper(), new Sum(), new Slow(), new Failing(), new NeedsTable());
    }

    /** text -> upper-cased text. */
    public static final class Upper implements ReportBlock {
        @Override public BlockDefinition definition() {
            return new BlockDefinition("test-upper", "Upper", "Upper-cases a text", "1.0.0",
                    List.of(BlockPort.required("text", DataType.STRING, "text")),
                    List.of(BlockPort.required("text", DataType.STRING, "upper-cased")), List.of(), null);
        }
        @Override public Map<String, Object> execute(Map<String, Object> inputs, ExecutionMetadata metadata) {
            return Map.of("text", String.valueOf(inputs.get("text")).toUpperCase());
        }
    }

    /** a + b. */
    public static final class Sum implements ReportBlock {
        @Override public BlockDefinition definition() {
            return new BlockDefinition("test-sum", "Sum", "Adds two numbers", "1.0.0",
                    List.of(BlockPort.required("a", DataType.NUMBER, "a"), BlockPort.optional("b", DataType.NUMBER, "b")),
                    List.of(BlockPort.required("total", DataType.NUMBER, "a + b")), List.of(), null);
        }
        @Override public Map<String, Object> execute(Map<String, Object> inputs, ExecutionMetadata metadata) {
            double b = inputs.get("b") == null ? 0 : ((Number) inputs.get("b")).doubleValue();
            return Map.of("total", ((Number) inputs.get("a")).doubleValue() + b);
        }
    }

    /** Sleeps longer than its 100 ms timeout. */
    public static final class Slow implements ReportBlock {
        @Override public BlockDefinition definition() {
            return new BlockDefinition("test-slow", "Slow", "Never finishes in time", "1.0.0",
                    List.of(), List.of(BlockPort.optional("done", DataType.BOOLEAN, "never")), List.of(), Duration.ofMillis(100));
        }
        @Override public Map<String, Object> execute(Map<String, Object> inputs, ExecutionMetadata metadata) throws Exception {
            Thread.sleep(5_000);
            return Map.of("done", true);
        }
    }

    /** Always throws. */
    public static final class Failing implements ReportBlock {
        @Override public BlockDefinition definition() {
            return new BlockDefinition("test-failing", "Failing", "Always fails", "1.0.0",
                    List.of(BlockPort.optional("text", DataType.STRING, "ignored")),
                    List.of(BlockPort.optional("text", DataType.STRING, "never")), List.of(), null);
        }
        @Override public Map<String, Object> execute(Map<String, Object> inputs, ExecutionMetadata metadata) {
            throw new IllegalStateException("coordinates outside supported geometry");
        }
    }

    /** Needs gold.reports_test_area(code, name) in the catalog; echoes the code. */
    public static final class NeedsTable implements ReportBlock {
        public static final DataRequirement REQUIREMENT = DataRequirement.table("gold", "reports_test_area", "code", "name");
        @Override public BlockDefinition definition() {
            return new BlockDefinition("test-area", "Area", "Reads the test area table", "1.0.0",
                    List.of(BlockPort.required("code", DataType.STRING, "code")),
                    List.of(BlockPort.required("code", DataType.STRING, "code"),
                            BlockPort.optional("details", DataType.OBJECT, "anything")),
                    List.of(REQUIREMENT), null);
        }
        @Override public Map<String, Object> execute(Map<String, Object> inputs, ExecutionMetadata metadata) {
            return Map.of("code", inputs.get("code"), "details", Map.of("name", "Area " + inputs.get("code")));
        }
    }
}
