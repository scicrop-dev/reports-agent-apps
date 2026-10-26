package com.infinitestack.reports.blocks.sathub.imagery;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;

/**
 * {@link SceneSearch} on the Earth Search STAC API (Element 84, the catalog Satellite Hub uses):
 * POST {stacUrl}/search with collections [sentinel-2-l2a], bbox, datetime range, eo:cloud_cover
 * filter and sortby datetime desc. Reads proj:epsg (or proj:code), s2:mgrs_tile / grid:code,
 * platform and, per asset, href and raster:bands[0].scale/offset/spatial_resolution.
 *
 * <p>OWNER: agent 2 (STAC + formulas). Stub until implemented.
 */
public class EarthSearchClient implements SceneSearch {

    private final String stacUrl;

    public EarthSearchClient(String stacUrl) {
        this.stacUrl = stacUrl;
    }

    @Override
    public List<SceneItem> search(double[] bboxLonLat, LocalDate since, double maxCloud, int limit) throws IOException {
        throw new UnsupportedOperationException("EarthSearchClient not implemented yet");
    }
}
