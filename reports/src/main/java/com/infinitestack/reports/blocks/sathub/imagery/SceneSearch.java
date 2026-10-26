package com.infinitestack.reports.blocks.sathub.imagery;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;

/** Finds Sentinel-2 L2A scenes over an area, newest first. */
public interface SceneSearch {

    /**
     * @param bboxLonLat {minLon, minLat, maxLon, maxLat}
     * @param since      only scenes acquired on or after this date
     * @param maxCloud   scene cloud cover, percent (eo:cloud_cover)
     */
    List<SceneItem> search(double[] bboxLonLat, LocalDate since, double maxCloud, int limit) throws IOException;
}
