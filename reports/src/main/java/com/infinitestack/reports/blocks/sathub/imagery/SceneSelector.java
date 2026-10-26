package com.infinitestack.reports.blocks.sathub.imagery;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Picks the scene to use for an area among search results (newest first): the newest scene whose
 * footprint covers the whole area; when none covers it all, the newest with the largest covered
 * share. A Sentinel-2 tile boundary can cut a property, and the same date comes as two items.
 *
 * <p>OWNER: agent 2 (STAC + formulas). Stub until implemented.
 */
public final class SceneSelector {

    /** The chosen scene and the share of the area (0-100) inside its footprint. */
    public record Choice(SceneItem scene, double coveragePercent) {}

    private SceneSelector() {}

    /** @param areaLonLat GeoJSON Polygon or MultiPolygon in lon/lat */
    public static Optional<Choice> choose(List<SceneItem> candidates, Map<String, Object> areaLonLat) {
        throw new UnsupportedOperationException("SceneSelector not implemented yet");
    }
}
