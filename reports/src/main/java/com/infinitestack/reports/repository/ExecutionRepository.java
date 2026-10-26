package com.infinitestack.reports.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.type.TypeReference;
import com.infinitestack.reports.engine.flow.BlockRun;
import com.infinitestack.reports.execution.Execution;
import com.infinitestack.reports.execution.ExecutionError;
import com.infinitestack.reports.execution.ExecutionStatus;

/** Executions and the record of each Block they ran. */
@Repository
public class ExecutionRepository {

    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {};
    private static final TypeReference<Map<String, Map<String, Object>>> VALUES = new TypeReference<>() {};
    private static final TypeReference<Map<String, String>> VERSIONS = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final AppSchema schema;
    private final SchemaInitializer initializer;
    private final Json json;

    public ExecutionRepository(JdbcTemplate jdbc, AppSchema schema, SchemaInitializer initializer, Json json) {
        this.jdbc = jdbc;
        this.schema = schema;
        this.initializer = initializer;
        this.json = json;
    }

    private String execution() { return schema.table(SchemaInitializer.T_EXECUTION); }
    private String block() { return schema.table(SchemaInitializer.T_EXECUTION_BLOCK); }

    /** Registers the execution before anything runs, so a crash still leaves a trace. */
    public long start(long reportId, int reportVersion, Map<String, Object> inputs, String startedBy, Long rerunOf) {
        initializer.requireReady();
        return jdbc.queryForObject("INSERT INTO " + execution()
                + " (report_id, report_version, status, inputs, started_by, created_at, rerun_of) VALUES (?, ?, ?, ?::jsonb, ?, ?, ?) RETURNING id",
                Long.class, reportId, reportVersion, ExecutionStatus.VALIDATING.name(), json.write(inputs), startedBy,
                Timestamp.from(Instant.now()), rerunOf);
    }

    @Transactional
    public void finish(long id, ExecutionStatus status, Map<String, Map<String, Object>> values,
                       Map<String, String> blockVersions, ExecutionError error, Instant finishedAt, long durationMs,
                       List<BlockRun> blocks) {
        jdbc.update("UPDATE " + execution() + " SET status = ?, context = ?::jsonb, block_versions = ?::jsonb,"
                + " error = ?::jsonb, finished_at = ?, duration_ms = ? WHERE id = ?",
                status.name(), json.write(values), json.write(blockVersions), json.write(error),
                Timestamp.from(finishedAt), durationMs, id);
        for (BlockRun run : blocks) {
            jdbc.update("INSERT INTO " + block() + " (execution_id, position, node_id, block_id, block_version, status,"
                    + " inputs, outputs, error, started_at, finished_at, duration_ms) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?)",
                    id, run.position(), run.nodeId(), run.blockId(), run.blockVersion(), run.status().name(),
                    json.write(run.inputs()), json.write(run.outputs()), run.error(),
                    Timestamp.from(run.startedAt()), Timestamp.from(run.finishedAt()), run.durationMs());
        }
    }

    public Optional<Execution> find(long id) {
        initializer.requireReady();
        Optional<Execution> found = jdbc.query("SELECT * FROM " + execution() + " WHERE id = ?",
                (rs, n) -> map(rs, List.of()), id).stream().findFirst();
        return found.map(e -> withBlocks(e, blocks(id)));
    }

    /** Most recent first, without the per-Block detail. */
    public List<Execution> findByReport(long reportId, int limit) {
        initializer.requireReady();
        return jdbc.query("SELECT * FROM " + execution() + " WHERE report_id = ? ORDER BY created_at DESC LIMIT ?",
                (rs, n) -> map(rs, List.of()), reportId, limit);
    }

    private List<BlockRun> blocks(long executionId) {
        return jdbc.query("SELECT * FROM " + block() + " WHERE execution_id = ? ORDER BY position",
                (rs, n) -> new BlockRun(rs.getInt("position"), rs.getString("node_id"), rs.getString("block_id"),
                        rs.getString("block_version"), BlockRun.Status.valueOf(rs.getString("status")),
                        json.read(rs.getString("inputs"), OBJECT), json.read(rs.getString("outputs"), OBJECT),
                        rs.getString("error"), ReportRepository.instant(rs, "started_at"),
                        ReportRepository.instant(rs, "finished_at"), rs.getLong("duration_ms")),
                executionId);
    }

    private Execution map(ResultSet rs, List<BlockRun> blocks) throws SQLException {
        Long duration = rs.getObject("duration_ms", Long.class);
        Long rerunOf = rs.getObject("rerun_of", Long.class);
        return new Execution(rs.getLong("id"), rs.getLong("report_id"), rs.getInt("report_version"),
                ExecutionStatus.valueOf(rs.getString("status")),
                json.read(rs.getString("inputs"), OBJECT), json.read(rs.getString("context"), VALUES),
                json.read(rs.getString("block_versions"), VERSIONS),
                json.read(rs.getString("error"), ExecutionError.class), rs.getString("started_by"),
                ReportRepository.instant(rs, "created_at"), ReportRepository.instant(rs, "finished_at"), duration, rerunOf, blocks);
    }

    private static Execution withBlocks(Execution e, List<BlockRun> blocks) {
        return new Execution(e.id(), e.reportId(), e.reportVersion(), e.status(), e.inputs(), e.values(),
                e.blockVersions(), e.error(), e.startedBy(), e.createdAt(), e.finishedAt(), e.durationMs(), e.rerunOf(), blocks);
    }
}
