package com.infinitestack.reports.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.infinitestack.reports.engine.flow.FlowDefinition;
import com.infinitestack.reports.report.InputDefinition;
import com.infinitestack.reports.report.PageSettings;
import com.infinitestack.reports.report.Report;
import com.infinitestack.reports.report.ReportVersion;
import com.infinitestack.reports.report.VersionStatus;

/** Reports and their versions. Every statement is schema-qualified (see {@link AppSchema}). */
@Repository
public class ReportRepository {

    private static final TypeReference<List<InputDefinition>> INPUTS = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final AppSchema schema;
    private final SchemaInitializer initializer;
    private final Json json;

    public ReportRepository(JdbcTemplate jdbc, AppSchema schema, SchemaInitializer initializer, Json json) {
        this.jdbc = jdbc;
        this.schema = schema;
        this.initializer = initializer;
        this.json = json;
    }

    private String report() { return schema.table(SchemaInitializer.T_REPORT); }
    private String version() { return schema.table(SchemaInitializer.T_REPORT_VERSION); }

    public List<Report> findAll() {
        initializer.requireReady();
        return jdbc.query("SELECT * FROM " + report() + " WHERE deleted_at IS NULL ORDER BY updated_at DESC",
                (rs, n) -> mapReport(rs));
    }

    public Optional<Report> find(long id) {
        initializer.requireReady();
        return jdbc.query("SELECT * FROM " + report() + " WHERE id = ? AND deleted_at IS NULL",
                (rs, n) -> mapReport(rs), id).stream().findFirst();
    }

    public long insert(String name, String description, String ownerId, String ownerName) {
        return insert(name, description, ownerId, ownerName, null, null);
    }

    /** @param readyMadeId null unless the report is a copy of a ready-made report */
    public long insert(String name, String description, String ownerId, String ownerName,
                       String readyMadeId, String readyMadeVersion) {
        initializer.requireReady();
        Timestamp now = Timestamp.from(Instant.now());
        return jdbc.queryForObject("INSERT INTO " + report()
                + " (name, description, owner_id, owner_name, created_at, updated_at, ready_made_id, ready_made_version)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id",
                Long.class, name, description, ownerId, ownerName, now, now, readyMadeId, readyMadeVersion);
    }

    public void update(long id, String name, String description) {
        initializer.requireReady();
        jdbc.update("UPDATE " + report() + " SET name = ?, description = ?, updated_at = ? WHERE id = ?",
                name, description, Timestamp.from(Instant.now()), id);
    }

    public void markDeleted(long id) {
        initializer.requireReady();
        jdbc.update("UPDATE " + report() + " SET deleted_at = ? WHERE id = ?", Timestamp.from(Instant.now()), id);
    }

    public void setPublishedVersion(long id, int versionNumber) {
        jdbc.update("UPDATE " + report() + " SET published_version = ?, updated_at = ? WHERE id = ?",
                versionNumber, Timestamp.from(Instant.now()), id);
    }

    public List<ReportVersion> findVersions(long reportId) {
        initializer.requireReady();
        return jdbc.query("SELECT * FROM " + version() + " WHERE report_id = ? ORDER BY version DESC",
                (rs, n) -> mapVersion(rs), reportId);
    }

    public Optional<ReportVersion> findVersion(long reportId, int versionNumber) {
        initializer.requireReady();
        return jdbc.query("SELECT * FROM " + version() + " WHERE report_id = ? AND version = ?",
                (rs, n) -> mapVersion(rs), reportId, versionNumber).stream().findFirst();
    }

    public void insertVersion(ReportVersion v) {
        initializer.requireReady();
        jdbc.update("INSERT INTO " + version()
                + " (report_id, version, status, inputs_schema, flow, template_html, template_css, page_settings, created_by, created_at, updated_at)"
                + " VALUES (?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?::jsonb, ?, ?, ?)",
                v.reportId(), v.version(), v.status().name(), json.write(v.inputs()), json.write(v.flow()),
                v.templateHtml(), v.templateCss(), json.write(v.page()), v.createdBy(), Timestamp.from(v.createdAt()), Timestamp.from(v.updatedAt()));
        touch(v.reportId());
    }

    /** Only drafts change; the WHERE clause makes that true even under a race with a publish. */
    public boolean updateDraft(long reportId, int versionNumber, List<InputDefinition> inputs, FlowDefinition flow,
                               String templateHtml, String templateCss, PageSettings page) {
        initializer.requireReady();
        int rows = jdbc.update("UPDATE " + version()
                + " SET inputs_schema = ?::jsonb, flow = ?::jsonb, template_html = ?, template_css = ?, page_settings = ?::jsonb, updated_at = ?"
                + " WHERE report_id = ? AND version = ? AND status = 'DRAFT'",
                json.write(inputs), json.write(flow), templateHtml, templateCss, json.write(page), Timestamp.from(Instant.now()),
                reportId, versionNumber);
        if (rows > 0) touch(reportId);
        return rows > 0;
    }

    public boolean publish(long reportId, int versionNumber) {
        initializer.requireReady();
        Timestamp now = Timestamp.from(Instant.now());
        int rows = jdbc.update("UPDATE " + version() + " SET status = 'PUBLISHED', published_at = ?, updated_at = ?"
                + " WHERE report_id = ? AND version = ? AND status = 'DRAFT'", now, now, reportId, versionNumber);
        if (rows > 0) setPublishedVersion(reportId, versionNumber);
        return rows > 0;
    }

    private void touch(long reportId) {
        jdbc.update("UPDATE " + report() + " SET updated_at = ? WHERE id = ?", Timestamp.from(Instant.now()), reportId);
    }

    private static Report mapReport(ResultSet rs) throws SQLException {
        Integer published = rs.getObject("published_version", Integer.class);
        return new Report(rs.getLong("id"), rs.getString("name"), rs.getString("description"),
                rs.getString("owner_id"), rs.getString("owner_name"), published,
                instant(rs, "created_at"), instant(rs, "updated_at"),
                rs.getString("ready_made_id"), rs.getString("ready_made_version"));
    }

    private ReportVersion mapVersion(ResultSet rs) throws SQLException {
        return new ReportVersion(rs.getLong("report_id"), rs.getInt("version"),
                VersionStatus.valueOf(rs.getString("status")),
                json.read(rs.getString("inputs_schema"), INPUTS),
                json.read(rs.getString("flow"), FlowDefinition.class),
                rs.getString("template_html"), rs.getString("template_css"),
                PageSettings.orDefaults(json.read(rs.getString("page_settings"), PageSettings.class)), rs.getString("created_by"),
                instant(rs, "created_at"), instant(rs, "updated_at"), instant(rs, "published_at"));
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }
}
