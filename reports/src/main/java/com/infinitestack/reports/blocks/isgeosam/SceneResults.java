package com.infinitestack.reports.blocks.isgeosam;

import java.util.List;
import java.util.Optional;

/**
 * What is-geo-sam wrote to its gold tables about a scene. An interface so the Block can be tested
 * without a database ({@link GoldSceneResults} is the real one).
 */
public interface SceneResults {

    /**
     * The run of the scene whose JSON had this sha256 (the scene identity of is-geo-sam): the latest
     * finished one (COMPLETED_* or REJECTED) if any, else the latest running one, else the latest of any
     * status. Empty when the scene was never segmented.
     */
    Optional<SceneRun> latestRun(String sceneSha256);

    /** The candidates of a run, largest first, at most {@code limit}. */
    List<Candidate> candidates(String runId, int limit);

    /**
     * @param ageSeconds seconds since the run started (a RUNNING run much older than the wait is stale)
     */
    record SceneRun(String runId, String status, String error, Integer candidateCount, String model,
                    String triggerId, String finishedAt, double ageSeconds) {}

    /**
     * @param geometry    GeoJSON MultiPolygon, EPSG:4326
     * @param reviewFlags what the reviewer should check (touches_image_edge, multipart...)
     */
    record Candidate(int objectId, Double areaHa, Double modelScore, Double stabilityScore, String status,
                     List<String> reviewFlags, String geometry) {}
}
