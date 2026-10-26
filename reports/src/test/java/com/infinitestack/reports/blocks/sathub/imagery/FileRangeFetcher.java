package com.infinitestack.reports.blocks.sathub.imagery;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Test double of {@link RangeFetcher}: the "url" is a local file path; counts requests and bytes. */
class FileRangeFetcher implements RangeFetcher {

    final AtomicInteger requests = new AtomicInteger();
    final AtomicLong bytes = new AtomicLong();
    /** Requests that started at offset 0 (header reads). */
    final AtomicInteger headerRequests = new AtomicInteger();

    @Override
    public byte[] fetch(String url, long offset, int length) throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(Path.of(url).toFile(), "r")) {
            int n = (int) Math.max(0, Math.min(length, file.length() - offset));
            byte[] out = new byte[n];
            file.seek(offset);
            file.readFully(out);
            requests.incrementAndGet();
            bytes.addAndGet(n);
            if (offset == 0) headerRequests.incrementAndGet();
            return out;
        }
    }

    void reset() {
        requests.set(0);
        bytes.set(0);
        headerRequests.set(0);
    }
}
