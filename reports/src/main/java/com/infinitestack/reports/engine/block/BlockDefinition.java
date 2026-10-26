package com.infinitestack.reports.engine.block;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * The explicit contract of a Block (MASTER section 8): identity, version, typed ports and the
 * catalog data it depends on. The Flow Engine knows Blocks only through this.
 *
 * @param timeout null to use the default block timeout
 */
public record BlockDefinition(
        String id,
        String name,
        String description,
        String version,
        List<BlockPort> inputs,
        List<BlockPort> outputs,
        List<DataRequirement> dataRequirements,
        Duration timeout) {

    public BlockDefinition {
        inputs = inputs == null ? List.of() : List.copyOf(inputs);
        outputs = outputs == null ? List.of() : List.copyOf(outputs);
        dataRequirements = dataRequirements == null ? List.of() : List.copyOf(dataRequirements);
    }

    public Optional<BlockPort> input(String name) {
        return inputs.stream().filter(p -> p.name().equals(name)).findFirst();
    }

    public Optional<BlockPort> output(String name) {
        return outputs.stream().filter(p -> p.name().equals(name)).findFirst();
    }
}
