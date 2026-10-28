package com.infinitestack.reports.blocks.isgeosam;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.infinitestack.reports.engine.block.BlockQueries;
import com.infinitestack.reports.engine.block.DataRequirement;

/**
 * {@link SceneResults} over the gold tables is-geo-sam writes ({@code gold.is_geo_sam_*}, created and
 * registered by the script itself), through the Blocks' read-only pool.
 */
public class GoldSceneResults implements SceneResults {

    static final String SCHEMA = "gold";
    static final String SCENE = "is_geo_sam_scene";
    static final String RUN = "is_geo_sam_run";
    static final String CANDIDATE = "is_geo_sam_candidate";

    static final Duration QUERY_TIMEOUT = Duration.ofSeconds(10);

    /** Finished runs first (what the report shows), then a running one (what it waits for). */
    static final String LATEST_RUN_SQL = "SELECT r.run_id::text AS run_id, r.status, r.error, r.candidate_count, r.model_name,"
            + " r.trigger_id, to_char(r.finished_at AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"') AS finished_at,"
            + " extract(epoch FROM now() - r.started_at) AS age_seconds"
            + " FROM " + SCHEMA + "." + RUN + " r JOIN " + SCHEMA + "." + SCENE + " s ON s.scene_id = r.scene_id"
            + " WHERE s.sha256 = ?"
            + " ORDER BY CASE WHEN r.status IN ('COMPLETED_WITH_REVIEW_REQUIRED', 'COMPLETED_NO_CANDIDATES', 'REJECTED') THEN 0"
            + " WHEN r.status = 'RUNNING' THEN 1 ELSE 2 END, r.started_at DESC LIMIT 1";

    static final String CANDIDATES_SQL = "SELECT object_id, area_ha, model_score, stability_score, status,"
            + " diagnostics -> 'review_flags' AS review_flags, ST_AsGeoJSON(geom, 7) AS geojson"
            + " FROM " + SCHEMA + "." + CANDIDATE + " WHERE run_id = CAST(? AS uuid)"
            + " ORDER BY area_ha DESC NULLS LAST, object_id LIMIT ?";

    /** Every table and column the SQL above reads (BLOCKS.md section 1). */
    static final List<DataRequirement> REQUIREMENTS = List.of(
            DataRequirement.table(SCHEMA, SCENE, "scene_id", "sha256"),
            DataRequirement.table(SCHEMA, RUN, "run_id", "scene_id", "status", "error", "candidate_count", "model_name",
                    "trigger_id", "started_at", "finished_at"),
            DataRequirement.table(SCHEMA, CANDIDATE, "run_id", "object_id", "geom", "area_ha", "model_score",
                    "stability_score", "status", "diagnostics"));

    private final BlockQueries queries;
    private final ObjectMapper json = new ObjectMapper();

    public GoldSceneResults(BlockQueries queries) {
        this.queries = queries;
    }

    @Override
    public Optional<SceneRun> latestRun(String sceneSha256) {
        List<SceneRun> rows = queries.read(QUERY_TIMEOUT, jdbc -> jdbc.query(LATEST_RUN_SQL, (rs, n) -> new SceneRun(
                rs.getString("run_id"), rs.getString("status"), rs.getString("error"),
                (Integer) rs.getObject("candidate_count"), rs.getString("model_name"), rs.getString("trigger_id"),
                rs.getString("finished_at"), rs.getDouble("age_seconds")), sceneSha256));
        return rows.stream().findFirst();
    }

    @Override
    public List<Candidate> candidates(String runId, int limit) {
        return queries.read(QUERY_TIMEOUT, jdbc -> jdbc.query(CANDIDATES_SQL, (rs, n) -> new Candidate(
                rs.getInt("object_id"), number(rs.getObject("area_ha")), number(rs.getObject("model_score")),
                number(rs.getObject("stability_score")), rs.getString("status"), flags(rs.getString("review_flags")),
                rs.getString("geojson")), runId, limit));
    }

    private static Double number(Object value) {
        return value instanceof Number n ? n.doubleValue() : null;
    }

    private List<String> flags(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        try {
            for (JsonNode flag : json.readTree(text)) out.add(flag.asText());
        } catch (Exception ignored) {
            // diagnostics written by another version of the script: no flags rather than no report
        }
        return out;
    }
}
