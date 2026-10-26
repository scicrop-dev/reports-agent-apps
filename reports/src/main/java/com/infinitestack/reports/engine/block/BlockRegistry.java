package com.infinitestack.reports.engine.block;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * Every Block the app knows, by id. Built once from the {@link ReportBlock} beans; a malformed
 * definition stops the boot instead of failing later in the middle of an execution.
 */
@Component
public class BlockRegistry {

    private static final Pattern BLOCK_ID = Pattern.compile("[a-z][a-z0-9]*(-[a-z0-9]+)*");
    private static final Pattern PORT_NAME = Pattern.compile("[a-zA-Z][a-zA-Z0-9_]*");

    private final Map<String, ReportBlock> blocks = new LinkedHashMap<>();

    public BlockRegistry(List<ReportBlock> discovered) {
        for (ReportBlock block : discovered) {
            BlockDefinition definition = block.definition();
            List<String> problems = check(definition);
            if (!problems.isEmpty()) {
                throw new IllegalStateException("Invalid block definition " + definition.id() + ": " + problems);
            }
            if (blocks.putIfAbsent(definition.id(), block) != null) {
                throw new IllegalStateException("Duplicate block id: " + definition.id());
            }
        }
    }

    public Optional<ReportBlock> find(String id) {
        return Optional.ofNullable(id == null ? null : blocks.get(id));
    }

    public Collection<ReportBlock> all() {
        return blocks.values();
    }

    static List<String> check(BlockDefinition d) {
        List<String> problems = new ArrayList<>();
        if (d.id() == null || !BLOCK_ID.matcher(d.id()).matches()) problems.add("id must be kebab-case");
        if (d.version() == null || d.version().isBlank()) problems.add("version is required");
        checkPorts("input", d.inputs(), problems);
        checkPorts("output", d.outputs(), problems);
        return problems;
    }

    private static void checkPorts(String kind, List<BlockPort> ports, List<String> problems) {
        Set<String> seen = new HashSet<>();
        for (BlockPort port : ports) {
            if (port.name() == null || !PORT_NAME.matcher(port.name()).matches()) {
                problems.add(kind + " port name '" + port.name() + "' is invalid");
            } else if (!seen.add(port.name())) {
                problems.add("duplicate " + kind + " port '" + port.name() + "'");
            }
            if (port.type() == null) problems.add(kind + " port '" + port.name() + "' has no type");
        }
    }
}
