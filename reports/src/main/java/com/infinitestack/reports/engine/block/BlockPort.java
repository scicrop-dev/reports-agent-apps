package com.infinitestack.reports.engine.block;

import com.infinitestack.reports.engine.DataType;

/** One named, typed input or output of a Block (ARCHITECTURE section 12). */
public record BlockPort(String name, DataType type, boolean required, String description) {

    public static BlockPort required(String name, DataType type, String description) {
        return new BlockPort(name, type, true, description);
    }

    public static BlockPort optional(String name, DataType type, String description) {
        return new BlockPort(name, type, false, description);
    }
}
