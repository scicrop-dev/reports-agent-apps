package com.infinitestack.reports.blocks.sathub.imagery;

import java.io.IOException;

/** Reads a byte range of a remote file (HTTP Range); a test double reads a local file instead. */
public interface RangeFetcher {

    /** Bytes [offset, offset + length) of {@code url}; fewer only at the end of the file. */
    byte[] fetch(String url, long offset, int length) throws IOException;
}
