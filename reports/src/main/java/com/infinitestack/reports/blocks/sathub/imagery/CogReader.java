package com.infinitestack.reports.blocks.sathub.imagery;

import java.io.IOException;

/**
 * Reads a window of a Cloud Optimized GeoTIFF (single band, tiled, DEFLATE with predictor 2,
 * uint16, as Sentinel-2 L2A on AWS) fetching only the tiles it needs. It picks the coarsest
 * overview whose pixel is not larger than the grid's pixel, then resamples onto the grid.
 * The grid must be in the COG's CRS (same EPSG).
 */
public interface CogReader {

    Raster read(String url, RasterGrid grid, Resampling resampling) throws IOException;
}
