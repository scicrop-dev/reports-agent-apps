package com.infinitestack.reports.blocks.sathub.imagery;

import java.time.Instant;
import java.util.Map;

/**
 * A Sentinel-2 L2A STAC item. {@code assets} are keyed by the STAC asset name (red, green, blue,
 * nir, nir08, rededge1..3, swir16, swir22, scl, ...), the names the Satellite Hub formulas use.
 * {@code footprint} is the item geometry as GeoJSON (lon/lat).
 */
public record SceneItem(String id, Instant datetime, double cloudCover, int epsg, String platform,
                        String mgrsTile, Map<String, Object> footprint, Map<String, SceneAsset> assets,
                        String selfHref) {}
