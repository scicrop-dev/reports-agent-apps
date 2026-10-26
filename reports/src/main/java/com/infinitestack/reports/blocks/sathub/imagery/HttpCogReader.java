package com.infinitestack.reports.blocks.sathub.imagery;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * {@link CogReader} over HTTP Range requests. Parses the TIFF IFDs (full resolution and
 * overviews; classic TIFF and BigTIFF), the GeoTIFF tags (ModelPixelScale, ModelTiepoint,
 * ProjectedCSType, GDAL_NODATA), and decodes DEFLATE (or uncompressed) tiles with horizontal
 * predictor (2) for 8- and 16-bit unsigned samples. Parsed headers are cached per URL for the life
 * of the reader; tiles are not, each read fetches the tiles of its window.
 *
 * <p>The header is read with a first 64 KB range, which holds every IFD and tile array of a GDAL
 * COG (Sentinel-2 L2A included); anything stored past it is fetched on demand. The tiles a window
 * needs are sorted by file offset and fetched in as few requests as possible, merging ranges less
 * than 64 KB apart (the tiles of one row are contiguous in a COG). Thread-safe.
 */
public class HttpCogReader implements CogReader {

    static final int HEADER_BYTES = 64 * 1024;
    /** Two tile ranges closer than this are fetched in one request (the gap is downloaded and dropped). */
    static final int MERGE_GAP = 64 * 1024;
    /** Upper bound of one merged request. */
    static final int MAX_REQUEST = 32 * 1024 * 1024;

    // TIFF and GeoTIFF tags
    private static final int NEW_SUBFILE_TYPE = 254;
    private static final int IMAGE_WIDTH = 256;
    private static final int IMAGE_LENGTH = 257;
    private static final int BITS_PER_SAMPLE = 258;
    private static final int COMPRESSION = 259;
    private static final int SAMPLES_PER_PIXEL = 277;
    private static final int PREDICTOR = 317;
    private static final int TILE_WIDTH = 322;
    private static final int TILE_LENGTH = 323;
    private static final int TILE_OFFSETS = 324;
    private static final int TILE_BYTE_COUNTS = 325;
    private static final int SAMPLE_FORMAT = 339;
    private static final int MODEL_PIXEL_SCALE = 33550;
    private static final int MODEL_TIEPOINT = 33922;
    private static final int GEO_KEY_DIRECTORY = 34735;
    private static final int GDAL_NODATA = 42113;
    private static final int GT_RASTER_TYPE_KEY = 1025;
    private static final int PROJECTED_CS_TYPE_KEY = 3072;

    private static final int MAX_IFDS = 64;

    private final RangeFetcher fetcher;
    private final Map<String, Cog> headers = new ConcurrentHashMap<>();

    public HttpCogReader(RangeFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /** One resolution of the image: the full one or an overview. */
    record Level(int width, int height, int tileWidth, int tileHeight, long[] tileOffsets, long[] tileByteCounts,
                 int bitsPerSample, int compression, int predictor, double pixelSizeX, double pixelSizeY) {

        int tilesAcross() {
            return (width + tileWidth - 1) / tileWidth;
        }
    }

    /**
     * A parsed COG header. ({@code originX}, {@code originY}) is the top-left corner of the image,
     * shared by every level; {@code nodata} is NaN when the file declares none.
     */
    record Cog(int epsg, double originX, double originY, double nodata, ByteOrder order, List<Level> levels) {

        /** The coarsest level whose pixel is not larger than {@code pixelSize}; the full resolution if none. */
        Level levelFor(double pixelSize) {
            Level chosen = levels.get(0);
            for (Level level : levels) {
                if (level.pixelSizeX() <= pixelSize * (1 + 1e-9) && level.pixelSizeX() > chosen.pixelSizeX()) {
                    chosen = level;
                }
            }
            return chosen;
        }
    }

    @Override
    public Raster read(String url, RasterGrid grid, Resampling resampling) throws IOException {
        Cog cog = header(url);
        if (grid.epsg() != cog.epsg()) {
            throw new IllegalArgumentException("grid is EPSG:" + grid.epsg() + ", COG is EPSG:" + cog.epsg() + ": " + url);
        }
        Level level = cog.levelFor(grid.pixelSize());

        // Source pixel coordinates (integer = pixel centre) of the first and last grid pixel centres
        double colFirst = sourceCol(cog, level, grid.x(0));
        double colLast = sourceCol(cog, level, grid.x(grid.width() - 1));
        double rowFirst = sourceRow(cog, level, grid.y(0));
        double rowLast = sourceRow(cog, level, grid.y(grid.height() - 1));
        // floor() and floor() + 1 cover both nearest and the bilinear neighbours
        int colMin = clamp((int) Math.floor(colFirst), level.width());
        int colMax = clamp((int) Math.floor(colLast) + 1, level.width());
        int rowMin = clamp((int) Math.floor(rowFirst), level.height());
        int rowMax = clamp((int) Math.floor(rowLast) + 1, level.height());

        Window window = new Window(colMin, rowMin, colMax - colMin + 1, rowMax - rowMin + 1);
        boolean intersects = colFirst < level.width() - 0.5 && colLast >= -0.5
                && rowFirst < level.height() - 0.5 && rowLast >= -0.5;
        if (intersects) readTiles(url, cog, level, window);

        float[] values = new float[grid.width() * grid.height()];
        for (int row = 0; row < grid.height(); row++) {
            double r = sourceRow(cog, level, grid.y(row));
            for (int col = 0; col < grid.width(); col++) {
                double c = sourceCol(cog, level, grid.x(col));
                float value = Float.NaN;
                if (intersects && c >= -0.5 && c < level.width() - 0.5 && r >= -0.5 && r < level.height() - 0.5) {
                    value = resampling == Resampling.NEAREST ? nearest(window, c, r) : bilinear(window, c, r);
                }
                values[row * grid.width() + col] = value;
            }
        }
        return new Raster(grid, values);
    }

    private static double sourceCol(Cog cog, Level level, double x) {
        return (x - cog.originX()) / level.pixelSizeX() - 0.5;
    }

    private static double sourceRow(Cog cog, Level level, double y) {
        return (cog.originY() - y) / level.pixelSizeY() - 0.5;
    }

    private static int clamp(int index, int size) {
        return Math.max(0, Math.min(size - 1, index));
    }

    // ------------------------------------------------------------------ sampling

    /** Decoded samples of the source window, NaN for nodata; NaN outside it too. */
    private static final class Window {
        final int col0;
        final int row0;
        final int width;
        final int height;
        final float[] values;

        Window(int col0, int row0, int width, int height) {
            this.col0 = col0;
            this.row0 = row0;
            this.width = width;
            this.height = height;
            this.values = new float[width * height];
            Arrays.fill(values, Float.NaN);
        }

        float get(int col, int row) {
            int c = col - col0;
            int r = row - row0;
            if (c < 0 || r < 0 || c >= width || r >= height) return Float.NaN;
            return values[r * width + c];
        }
    }

    private static float nearest(Window window, double c, double r) {
        return window.get((int) Math.floor(c + 0.5), (int) Math.floor(r + 0.5));
    }

    /** Bilinear over the valid neighbours only, renormalising their weights; NaN when none is valid. */
    private static float bilinear(Window window, double c, double r) {
        int c0 = (int) Math.floor(c);
        int r0 = (int) Math.floor(r);
        double fx = c - c0;
        double fy = r - r0;
        double sum = 0;
        double weights = 0;
        for (int dy = 0; dy <= 1; dy++) {
            for (int dx = 0; dx <= 1; dx++) {
                double w = (dx == 0 ? 1 - fx : fx) * (dy == 0 ? 1 - fy : fy);
                float v = window.get(c0 + dx, r0 + dy);
                if (w > 0 && !Float.isNaN(v)) {
                    sum += w * v;
                    weights += w;
                }
            }
        }
        return weights > 0 ? (float) (sum / weights) : Float.NaN;
    }

    // ------------------------------------------------------------------ tiles

    private record TileRef(int col, int row, long offset, long byteCount) {}

    /** Fetches the tiles intersecting the window, merging close ranges, and decodes them into it. */
    private void readTiles(String url, Cog cog, Level level, Window window) throws IOException {
        List<TileRef> tiles = new ArrayList<>();
        int tx0 = window.col0 / level.tileWidth();
        int tx1 = (window.col0 + window.width - 1) / level.tileWidth();
        int ty0 = window.row0 / level.tileHeight();
        int ty1 = (window.row0 + window.height - 1) / level.tileHeight();
        for (int ty = ty0; ty <= ty1; ty++) {
            for (int tx = tx0; tx <= tx1; tx++) {
                int index = ty * level.tilesAcross() + tx;
                if (index >= level.tileOffsets().length) throw new IOException("tile index out of range in " + url);
                long count = level.tileByteCounts()[index];
                if (count == 0) {
                    // Sparse tile (GDAL): never written, all zeros
                    copyTile(new int[level.tileWidth() * level.tileHeight()], tx, ty, cog, level, window);
                } else {
                    tiles.add(new TileRef(tx, ty, level.tileOffsets()[index], count));
                }
            }
        }
        tiles.sort(Comparator.comparingLong(TileRef::offset));

        int i = 0;
        while (i < tiles.size()) {
            long start = tiles.get(i).offset();
            long end = start + tiles.get(i).byteCount();
            int j = i + 1;
            while (j < tiles.size()) {
                TileRef next = tiles.get(j);
                long nextEnd = Math.max(end, next.offset() + next.byteCount());
                if (next.offset() - end > MERGE_GAP || nextEnd - start > MAX_REQUEST) break;
                end = nextEnd;
                j++;
            }
            if (end - start > Integer.MAX_VALUE) throw new IOException("tile too large in " + url);
            byte[] bytes = fetcher.fetch(url, start, (int) (end - start));
            for (int k = i; k < j; k++) {
                TileRef tile = tiles.get(k);
                int from = (int) (tile.offset() - start);
                if (from + tile.byteCount() > bytes.length) throw new IOException("truncated tile in " + url);
                int[] samples = decodeTile(bytes, from, (int) tile.byteCount(), cog.order(), level);
                copyTile(samples, tile.col(), tile.row(), cog, level, window);
            }
            i = j;
        }
    }

    /** Inflates (when compressed) and undoes the horizontal predictor: one sample per pixel, unsigned. */
    static int[] decodeTile(byte[] data, int offset, int length, ByteOrder order, Level level) throws IOException {
        int pixels = level.tileWidth() * level.tileHeight();
        int bytesPerSample = level.bitsPerSample() / 8;
        byte[] raw;
        int rawOffset;
        if (level.compression() == 1) {
            if (length < pixels * bytesPerSample) throw new IOException("uncompressed tile too short");
            raw = data;
            rawOffset = offset;
        } else {
            raw = inflate(data, offset, length, pixels * bytesPerSample);
            rawOffset = 0;
        }
        int[] samples = new int[pixels];
        boolean little = order == ByteOrder.LITTLE_ENDIAN;
        for (int p = 0; p < pixels; p++) {
            if (bytesPerSample == 1) {
                samples[p] = raw[rawOffset + p] & 0xFF;
            } else {
                int b0 = raw[rawOffset + 2 * p] & 0xFF;
                int b1 = raw[rawOffset + 2 * p + 1] & 0xFF;
                samples[p] = little ? b0 | (b1 << 8) : (b0 << 8) | b1;
            }
        }
        if (level.predictor() == 2) {
            int mask = bytesPerSample == 1 ? 0xFF : 0xFFFF;
            for (int row = 0; row < level.tileHeight(); row++) {
                int base = row * level.tileWidth();
                for (int col = 1; col < level.tileWidth(); col++) {
                    samples[base + col] = (samples[base + col] + samples[base + col - 1]) & mask;
                }
            }
        }
        return samples;
    }

    private static byte[] inflate(byte[] data, int offset, int length, int expected) throws IOException {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(data, offset, length);
            byte[] out = new byte[expected];
            int n = 0;
            while (n < expected && !inflater.finished()) {
                int got = inflater.inflate(out, n, expected - n);
                if (got == 0 && (inflater.needsInput() || inflater.needsDictionary())) break;
                n += got;
            }
            if (n < expected) throw new IOException("DEFLATE tile inflated to " + n + " of " + expected + " bytes");
            return out;
        } catch (DataFormatException e) {
            throw new IOException("corrupt DEFLATE tile: " + e.getMessage(), e);
        } finally {
            inflater.end();
        }
    }

    /** Copies the part of a decoded tile inside the window, nodata as NaN. */
    private static void copyTile(int[] samples, int tileCol, int tileRow, Cog cog, Level level, Window window) {
        int tileX = tileCol * level.tileWidth();
        int tileY = tileRow * level.tileHeight();
        int colFrom = Math.max(window.col0, tileX);
        int colTo = Math.min(window.col0 + window.width, tileX + level.tileWidth());
        int rowFrom = Math.max(window.row0, tileY);
        int rowTo = Math.min(window.row0 + window.height, tileY + level.tileHeight());
        boolean hasNodata = !Double.isNaN(cog.nodata());
        for (int row = rowFrom; row < rowTo; row++) {
            int src = (row - tileY) * level.tileWidth();
            int dst = (row - window.row0) * window.width;
            for (int col = colFrom; col < colTo; col++) {
                int v = samples[src + col - tileX];
                window.values[dst + col - window.col0] = hasNodata && v == cog.nodata() ? Float.NaN : v;
            }
        }
    }

    // ------------------------------------------------------------------ header

    /** The parsed header of {@code url}, from the cache or fetched once. */
    Cog header(String url) throws IOException {
        Cog cog = headers.get(url);
        if (cog == null) {
            cog = new HeaderParser(url).parse();
            Cog raced = headers.putIfAbsent(url, cog);
            if (raced != null) cog = raced;
        }
        return cog;
    }

    /** One IFD entry; {@code inline} holds the value field when the value fits in it. */
    private record Entry(int type, long count, ByteBuffer inline, long valueOffset) {}

    /** Reads the TIFF structure of one file: a 64 KB prefix, plus on-demand ranges past it. */
    private final class HeaderParser {
        private final String url;
        private byte[] prefix;
        private ByteOrder order;
        private boolean bigTiff;

        HeaderParser(String url) {
            this.url = url;
        }

        Cog parse() throws IOException {
            prefix = fetcher.fetch(url, 0, HEADER_BYTES);
            if (prefix.length < 16) throw new IOException("not a TIFF (too short): " + url);
            if (prefix[0] == 'I' && prefix[1] == 'I') {
                order = ByteOrder.LITTLE_ENDIAN;
            } else if (prefix[0] == 'M' && prefix[1] == 'M') {
                order = ByteOrder.BIG_ENDIAN;
            } else {
                throw new IOException("not a TIFF: " + url);
            }
            ByteBuffer head = at(0, 16);
            int version = head.getShort(2) & 0xFFFF;
            long ifdOffset;
            if (version == 42) {
                ifdOffset = head.getInt(4) & 0xFFFFFFFFL;
            } else if (version == 43) {
                bigTiff = true;
                ifdOffset = head.getLong(8);
            } else {
                throw new IOException("unknown TIFF version " + version + ": " + url);
            }

            List<Map<Integer, Entry>> ifds = new ArrayList<>();
            while (ifdOffset != 0 && ifds.size() < MAX_IFDS) {
                Map<Integer, Entry> ifd = new HashMap<>();
                ifdOffset = readIfd(ifdOffset, ifd);
                ifds.add(ifd);
            }
            if (ifds.isEmpty()) throw new IOException("TIFF without IFD: " + url);

            Map<Integer, Entry> full = ifds.get(0);
            int fullWidth = intTag(full, IMAGE_WIDTH);
            int fullHeight = intTag(full, IMAGE_LENGTH);
            double[] scale = doubles(required(full, MODEL_PIXEL_SCALE));
            double[] tiepoint = doubles(required(full, MODEL_TIEPOINT));
            double sizeX = scale[0];
            double sizeY = scale[1];
            double originX = tiepoint[3] - tiepoint[0] * sizeX;
            double originY = tiepoint[4] + tiepoint[1] * sizeY;
            int epsg = 0;
            int rasterType = 1;
            long[] keys = longs(required(full, GEO_KEY_DIRECTORY));
            for (int k = 0; k < keys[3] && 4 + 4 * k + 3 < keys.length; k++) {
                long id = keys[4 + 4 * k];
                boolean inlineValue = keys[4 + 4 * k + 1] == 0;
                long value = keys[4 + 4 * k + 3];
                if (id == PROJECTED_CS_TYPE_KEY && inlineValue) epsg = (int) value;
                if (id == GT_RASTER_TYPE_KEY && inlineValue) rasterType = (int) value;
            }
            if (epsg == 0) throw new IOException("COG without ProjectedCSTypeGeoKey: " + url);
            if (rasterType == 2) {
                // PixelIsPoint: the tiepoint is the centre of its pixel
                originX -= sizeX / 2;
                originY += sizeY / 2;
            }
            double nodata = Double.NaN;
            Entry nodataEntry = full.get(GDAL_NODATA);
            if (nodataEntry != null) {
                try {
                    nodata = Double.parseDouble(ascii(nodataEntry));
                } catch (NumberFormatException e) {
                    nodata = Double.NaN; // "nan" or garbage: no integer sample can match it anyway
                }
            }

            List<Level> levels = new ArrayList<>();
            levels.add(level(full, sizeX, sizeY));
            for (int i = 1; i < ifds.size(); i++) {
                Map<Integer, Entry> ifd = ifds.get(i);
                long subfileType = ifd.containsKey(NEW_SUBFILE_TYPE) ? longs(ifd.get(NEW_SUBFILE_TYPE))[0] : 0;
                if ((subfileType & 1) == 0 || (subfileType & 4) != 0) continue; // not an overview, or a mask
                // Overviews inherit the georeferencing: same extent, coarser pixel
                levels.add(level(ifd, sizeX * fullWidth / intTag(ifd, IMAGE_WIDTH),
                        sizeY * fullHeight / intTag(ifd, IMAGE_LENGTH)));
            }
            levels.sort(Comparator.comparingDouble(Level::pixelSizeX));
            return new Cog(epsg, originX, originY, nodata, order, List.copyOf(levels));
        }

        /** Reads one IFD into {@code ifd} and returns the offset of the next one (0 = last). */
        private long readIfd(long offset, Map<Integer, Entry> ifd) throws IOException {
            int countSize = bigTiff ? 8 : 2;
            int entrySize = bigTiff ? 20 : 12;
            int fieldSize = bigTiff ? 8 : 4;
            ByteBuffer countBuffer = at(offset, countSize);
            long count = bigTiff ? countBuffer.getLong(0) : countBuffer.getShort(0) & 0xFFFF;
            if (count <= 0 || count > 4096) throw new IOException("bad IFD at " + offset + ": " + url);
            ByteBuffer entries = at(offset + countSize, (int) count * entrySize + fieldSize);
            for (int e = 0; e < count; e++) {
                int base = e * entrySize;
                int tag = entries.getShort(base) & 0xFFFF;
                int type = entries.getShort(base + 2) & 0xFFFF;
                long n = bigTiff ? entries.getLong(base + 4) : entries.getInt(base + 4) & 0xFFFFFFFFL;
                int fieldAt = base + (bigTiff ? 12 : 8);
                ByteBuffer field = entries.slice(fieldAt, fieldSize).order(order);
                long valueOffset = bigTiff ? entries.getLong(fieldAt) : entries.getInt(fieldAt) & 0xFFFFFFFFL;
                ifd.put(tag, new Entry(type, n, field, valueOffset));
            }
            int nextAt = (int) count * entrySize;
            return bigTiff ? entries.getLong(nextAt) : entries.getInt(nextAt) & 0xFFFFFFFFL;
        }

        /** The level of an IFD, validating what this reader supports. */
        private Level level(Map<Integer, Entry> ifd, double sizeX, double sizeY) throws IOException {
            int width = intTag(ifd, IMAGE_WIDTH);
            int height = intTag(ifd, IMAGE_LENGTH);
            int bits = intTag(ifd, BITS_PER_SAMPLE);
            int compression = (int) optional(ifd, COMPRESSION, 1);
            int predictor = (int) optional(ifd, PREDICTOR, 1);
            int samplesPerPixel = (int) optional(ifd, SAMPLES_PER_PIXEL, 1);
            int sampleFormat = (int) optional(ifd, SAMPLE_FORMAT, 1);
            if (!ifd.containsKey(TILE_WIDTH)) throw new IOException("not a tiled TIFF: " + url);
            if (samplesPerPixel != 1) throw new IOException("only single-band COGs are supported: " + url);
            if ((bits != 8 && bits != 16) || sampleFormat != 1) {
                throw new IOException("only 8/16-bit unsigned samples are supported, got " + bits + " bits, format "
                        + sampleFormat + ": " + url);
            }
            if (compression != 1 && compression != 8 && compression != 32946) {
                throw new IOException("unsupported TIFF compression " + compression + ": " + url);
            }
            if (predictor != 1 && predictor != 2) throw new IOException("unsupported predictor " + predictor + ": " + url);
            int tileWidth = intTag(ifd, TILE_WIDTH);
            int tileHeight = intTag(ifd, TILE_LENGTH);
            long[] offsets = longs(required(ifd, TILE_OFFSETS));
            long[] counts = longs(required(ifd, TILE_BYTE_COUNTS));
            long tiles = (long) ((width + tileWidth - 1) / tileWidth) * ((height + tileHeight - 1) / tileHeight);
            if (offsets.length < tiles || counts.length < tiles) throw new IOException("incomplete tile arrays: " + url);
            return new Level(width, height, tileWidth, tileHeight, offsets, counts, bits, compression, predictor,
                    sizeX, sizeY);
        }

        private int intTag(Map<Integer, Entry> ifd, int tag) throws IOException {
            return (int) longs(required(ifd, tag))[0];
        }

        private Entry required(Map<Integer, Entry> ifd, int tag) throws IOException {
            Entry entry = ifd.get(tag);
            if (entry == null) throw new IOException("TIFF tag " + tag + " missing: " + url);
            return entry;
        }

        private long optional(Map<Integer, Entry> ifd, int tag, long fallback) throws IOException {
            Entry entry = ifd.get(tag);
            return entry == null ? fallback : longs(entry)[0];
        }

        /** The raw bytes of an entry's value, inline or out of line. */
        private ByteBuffer value(Entry entry) throws IOException {
            long size = typeSize(entry.type()) * entry.count();
            if (size > Integer.MAX_VALUE) throw new IOException("TIFF value too large: " + url);
            if (size <= entry.inline().capacity()) return entry.inline();
            return at(entry.valueOffset(), (int) size);
        }

        private long[] longs(Entry entry) throws IOException {
            ByteBuffer buffer = value(entry);
            int n = (int) entry.count();
            long[] out = new long[n];
            for (int i = 0; i < n; i++) {
                out[i] = switch (entry.type()) {
                    case 1, 2, 7 -> buffer.get(i) & 0xFF;
                    case 3 -> buffer.getShort(2 * i) & 0xFFFF;
                    case 4, 13 -> buffer.getInt(4 * i) & 0xFFFFFFFFL;
                    case 9 -> buffer.getInt(4 * i);
                    case 16, 17, 18 -> buffer.getLong(8 * i);
                    default -> throw new IOException("TIFF type " + entry.type() + " is not an integer: " + url);
                };
            }
            return out;
        }

        private double[] doubles(Entry entry) throws IOException {
            if (entry.type() != 11 && entry.type() != 12) {
                return Arrays.stream(longs(entry)).asDoubleStream().toArray();
            }
            ByteBuffer buffer = value(entry);
            double[] out = new double[(int) entry.count()];
            for (int i = 0; i < out.length; i++) {
                out[i] = entry.type() == 12 ? buffer.getDouble(8 * i) : buffer.getFloat(4 * i);
            }
            return out;
        }

        private String ascii(Entry entry) throws IOException {
            ByteBuffer buffer = value(entry);
            byte[] bytes = new byte[(int) entry.count()];
            for (int i = 0; i < bytes.length; i++) bytes[i] = buffer.get(i);
            return new String(bytes, StandardCharsets.US_ASCII).replace("\0", "").trim();
        }

        /** {@code length} bytes at {@code offset}, from the prefix when it holds them. */
        private ByteBuffer at(long offset, int length) throws IOException {
            if (offset + length <= prefix.length) {
                return ByteBuffer.wrap(prefix, (int) offset, length).slice().order(order);
            }
            byte[] bytes = fetcher.fetch(url, offset, length);
            if (bytes.length < length) throw new IOException("TIFF structure past the end of the file: " + url);
            return ByteBuffer.wrap(bytes).order(order);
        }
    }

    private static long typeSize(int type) {
        return switch (type) {
            case 3, 8 -> 2;
            case 4, 9, 11, 13 -> 4;
            case 5, 10, 12, 16, 17, 18 -> 8;
            default -> 1;
        };
    }
}
