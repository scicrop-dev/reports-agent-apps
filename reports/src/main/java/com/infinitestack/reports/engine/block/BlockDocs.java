package com.infinitestack.reports.engine.block;

import java.util.List;

/**
 * What the person building a Flow reads about a Block: where its data comes from, how it works,
 * what to connect to it and what it cannot tell. Documentation only: the engine never reads it, so
 * changing it does not change the Block's version.
 *
 * @param category    the group in the Flow editor's palette (the data source, e.g. "CAR", "BR Geo")
 * @param summary     one or two sentences: what the Block answers
 * @param howItWorks  the steps the Block takes, in plain words
 * @param connect     typical wiring: which outputs feed its inputs and where its outputs go
 * @param limitations what the result does not mean, gaps in the data, caveats
 * @param source      the dataset and who publishes it
 * @param example     a template snippet that uses the outputs, with {@code <node>} as the node id
 */
public record BlockDocs(
        String category,
        String summary,
        List<String> howItWorks,
        List<String> connect,
        List<String> limitations,
        String source,
        String example) {

    public BlockDocs {
        howItWorks = howItWorks == null ? List.of() : List.copyOf(howItWorks);
        connect = connect == null ? List.of() : List.copyOf(connect);
        limitations = limitations == null ? List.of() : List.copyOf(limitations);
    }

    /** For a Block that documents nothing beyond its contract. */
    public static BlockDocs basic(String category) {
        return new BlockDocs(category, null, List.of(), List.of(), List.of(), null, null);
    }
}
