package com.infinitestack.reports.blocks.sathub.imagery;

import java.io.IOException;
import java.time.Duration;

/**
 * {@link RangeFetcher} over HTTP(S) with {@code Range: bytes=a-b} (java.net.http), with connect and
 * request timeouts and one retry on a transient error (5xx, timeout). A 200 answer to a range
 * request (server ignoring Range) is an error: it would download the whole band.
 *
 * <p>OWNER: agent 1 (COG reader + UTM). Stub until implemented.
 */
public class HttpRangeFetcher implements RangeFetcher {

    private final Duration timeout;

    public HttpRangeFetcher(Duration timeout) {
        this.timeout = timeout;
    }

    @Override
    public byte[] fetch(String url, long offset, int length) throws IOException {
        throw new UnsupportedOperationException("HttpRangeFetcher not implemented yet");
    }
}
