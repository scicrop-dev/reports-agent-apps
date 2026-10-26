package com.infinitestack.reports.repository;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Creates the app's tables in the destination database the host injected.
 *
 * <p>Prepared in the background and never on the boot path: the host finishes the installation
 * when {@code /api/runtime-health} answers, so a slow customer database must not fail a good
 * package. {@link #ensureReady()} is retried by every repository call, so a database that comes
 * back later does not require reinstalling the plugin.
 */
@Component
public class SchemaInitializer {

    private static final Logger log = LoggerFactory.getLogger(SchemaInitializer.class);

    /** Bare names; the plugin_id prefix is the naming rule, the schema comes from config. */
    public static final String T_REPORT = "reports_report";
    public static final String T_REPORT_VERSION = "reports_report_version";
    public static final String T_EXECUTION = "reports_execution";
    public static final String T_EXECUTION_BLOCK = "reports_execution_block";
    public static final String T_BLOCK_RELEASE = "reports_block_release";

    /** Every statement takes the schema name as %1$s. */
    private static final List<String> DDL = List.of(
        // Reports are soft-deleted: their executions are an audit trail and must keep pointing somewhere.
        """
        CREATE TABLE IF NOT EXISTS %1$s.reports_report (
            id                BIGSERIAL    PRIMARY KEY,
            name              VARCHAR(200) NOT NULL,
            description       TEXT,
            owner_id          VARCHAR(120) NOT NULL,
            owner_name        VARCHAR(200),
            published_version INT,
            created_at        TIMESTAMPTZ  NOT NULL,
            updated_at        TIMESTAMPTZ  NOT NULL,
            deleted_at        TIMESTAMPTZ
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS %1$s.reports_report_version (
            report_id     BIGINT       NOT NULL REFERENCES %1$s.reports_report (id),
            version       INT          NOT NULL,
            status        VARCHAR(20)  NOT NULL,
            inputs_schema JSONB        NOT NULL,
            flow          JSONB        NOT NULL,
            template_html TEXT         NOT NULL,
            template_css  TEXT         NOT NULL,
            created_by    VARCHAR(120),
            created_at    TIMESTAMPTZ  NOT NULL,
            updated_at    TIMESTAMPTZ  NOT NULL,
            published_at  TIMESTAMPTZ,
            PRIMARY KEY (report_id, version)
        )
        """,
        // The context is kept so the result can be rendered again without running the Blocks again.
        """
        CREATE TABLE IF NOT EXISTS %1$s.reports_execution (
            id             BIGSERIAL    PRIMARY KEY,
            report_id      BIGINT       NOT NULL REFERENCES %1$s.reports_report (id),
            report_version INT          NOT NULL,
            status         VARCHAR(30)  NOT NULL,
            inputs         JSONB        NOT NULL,
            context        JSONB,
            block_versions JSONB,
            error          JSONB,
            started_by     VARCHAR(120),
            created_at     TIMESTAMPTZ  NOT NULL,
            finished_at    TIMESTAMPTZ,
            duration_ms    BIGINT
        )
        """,
        """
        CREATE INDEX IF NOT EXISTS idx_reports_execution_report_created
            ON %1$s.reports_execution (report_id, created_at DESC)
        """,
        """
        CREATE TABLE IF NOT EXISTS %1$s.reports_execution_block (
            execution_id  BIGINT       NOT NULL REFERENCES %1$s.reports_execution (id) ON DELETE CASCADE,
            position      INT          NOT NULL,
            node_id       VARCHAR(100) NOT NULL,
            block_id      VARCHAR(100) NOT NULL,
            block_version VARCHAR(40)  NOT NULL,
            status        VARCHAR(20)  NOT NULL,
            inputs        JSONB,
            outputs       JSONB,
            error         TEXT,
            started_at    TIMESTAMPTZ  NOT NULL,
            finished_at   TIMESTAMPTZ  NOT NULL,
            duration_ms   BIGINT       NOT NULL,
            PRIMARY KEY (execution_id, position)
        )
        """,
        // Phase 5: paper settings of the PDF. ALTER so an existing install gains the column.
        """
        ALTER TABLE %1$s.reports_report_version ADD COLUMN IF NOT EXISTS page_settings JSONB
        """,
        // Phase 7: a re-run points to the execution it reproduces.
        """
        ALTER TABLE %1$s.reports_execution ADD COLUMN IF NOT EXISTS rerun_of BIGINT
        """,
        // One row per released Block. availability is the catalog diagnosis at the moment of release.
        """
        CREATE TABLE IF NOT EXISTS %1$s.reports_block_release (
            block_id         VARCHAR(100) PRIMARY KEY,
            block_version    VARCHAR(40)  NOT NULL,
            released_by      VARCHAR(120) NOT NULL,
            released_by_name VARCHAR(200),
            released_at      TIMESTAMPTZ  NOT NULL,
            availability     JSONB
        )
        """
    );

    private final JdbcTemplate jdbc;
    private final AppSchema appSchema;
    private volatile boolean ready = false;
    private volatile String lastError = null;

    public SchemaInitializer(JdbcTemplate jdbc, AppSchema appSchema) {
        this.jdbc = jdbc;
        this.appSchema = appSchema;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void prepareInBackground() {
        Thread thread = new Thread(this::ensureReady, "reports-schema-init");
        thread.setDaemon(true);
        thread.start();
    }

    /** @return true when the tables exist. Never throws: the reason stays in {@link #lastError()}. */
    public boolean ensureReady() {
        if (ready) return true;
        synchronized (this) {
            if (ready) return true;
            try {
                // The schema first: every following statement is qualified, so it has to exist.
                jdbc.execute(appSchema.createSchemaStatement());
                for (String ddl : DDL) {
                    jdbc.execute(ddl.formatted(appSchema.name()));
                }
                ready = true;
                lastError = null;
                log.info("[reports] schema ready ({}: {}, {}, {}, {}, {})", appSchema.name(),
                        T_REPORT, T_REPORT_VERSION, T_EXECUTION, T_EXECUTION_BLOCK, T_BLOCK_RELEASE);
                return true;
            } catch (Exception e) {
                lastError = e.getMessage();
                log.warn("[reports] could not prepare the schema: {}", e.getMessage());
                return false;
            }
        }
    }

    /** Called by repositories before touching the tables. */
    public void requireReady() {
        if (!ensureReady()) {
            throw new IllegalStateException("The database is not ready: " + lastError);
        }
    }

    public String lastError() {
        return lastError;
    }
}
