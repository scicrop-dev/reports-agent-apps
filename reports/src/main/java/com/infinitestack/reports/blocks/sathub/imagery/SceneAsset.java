package com.infinitestack.reports.blocks.sathub.imagery;

/**
 * One band file of a scene: the COG href, the STAC raster scale/offset and the native resolution.
 * Satellite Hub applies the scale (0.0001) and not the offset, and so do we.
 */
public record SceneAsset(String href, double scale, double offset, double resolution) {}
