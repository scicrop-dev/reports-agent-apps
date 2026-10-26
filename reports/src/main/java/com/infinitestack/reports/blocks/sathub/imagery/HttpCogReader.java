package com.infinitestack.reports.blocks.sathub.imagery;

import java.io.IOException;

/**
 * {@link CogReader} over HTTP Range requests. Parses the TIFF IFDs (full resolution and
 * overviews), the GeoTIFF tags (ModelPixelScale, ModelTiepoint, ProjectedCSType), and decodes
 * DEFLATE tiles with horizontal predictor (2) for 16-bit samples. Header and tiles are cached for
 * the life of the reader, so the bands of one scene share nothing but each band is read once.
 *
 * <p>OWNER: agent 1 (COG reader + UTM). Stub until implemented.
 */
public class HttpCogReader implements CogReader {

    private final RangeFetcher fetcher;

    public HttpCogReader(RangeFetcher fetcher) {
        this.fetcher = fetcher;
    }

    @Override
    public Raster read(String url, RasterGrid grid, Resampling resampling) throws IOException {
        throw new UnsupportedOperationException("HttpCogReader not implemented yet");
    }
}
