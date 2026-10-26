package com.infinitestack.reports.engine.block;

import java.util.Map;

/**
 * A functional unit of a report: receives typed inputs, produces typed outputs (ARCHITECTURE
 * section 10). Implementations are Spring beans, discovered by the {@link BlockRegistry}, so a new
 * Block does not change the engine.
 *
 * <p>A Block must not know the template, the PDF or other Blocks. Outputs not declared in the
 * definition are dropped by the engine.
 */
public interface ReportBlock {

    BlockDefinition definition();

    /** What the Flow editor shows about this Block; documentation, not contract. */
    default BlockDocs docs() {
        return BlockDocs.basic("Other");
    }

    /**
     * @param inputs already checked against the declared input types; optional ports may be absent
     * @return one entry per declared output
     */
    Map<String, Object> execute(Map<String, Object> inputs, ExecutionMetadata metadata) throws Exception;
}
