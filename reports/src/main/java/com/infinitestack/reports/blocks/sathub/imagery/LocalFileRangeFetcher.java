package com.infinitestack.reports.blocks.sathub.imagery;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;

/**
 * {@link RangeFetcher} over local files: the "url" is a path on the host's disk. Used for rasters other
 * apps keep in the lake, such as BR Weather's CHIRPS clips; the caller checks the path is allowed.
 */
public class LocalFileRangeFetcher implements RangeFetcher {

    @Override
    public byte[] fetch(String url, long offset, int length) throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(Path.of(url).toFile(), "r")) {
            int n = (int) Math.max(0, Math.min(length, file.length() - offset));
            byte[] out = new byte[n];
            file.seek(offset);
            file.readFully(out);
            return out;
        }
    }
}
