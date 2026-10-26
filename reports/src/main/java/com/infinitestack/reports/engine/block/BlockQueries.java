package com.infinitestack.reports.engine.block;

import java.time.Duration;
import java.util.function.Function;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * How Blocks read data: through the read-only pool, inside a read-only transaction, with a
 * statement timeout so a heavy spatial query cannot hold a connection past the Block's own limit.
 * Blocks never get the app's main (writable) datasource.
 */
@Component
public class BlockQueries {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate readOnly;

    public BlockQueries(@Qualifier("blockDataSource") DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.readOnly = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        this.readOnly.setReadOnly(true);
    }

    /** Runs {@code work} in a read-only transaction whose statements are cut after {@code timeout}. */
    public <T> T read(Duration timeout, Function<JdbcTemplate, T> work) {
        return readOnly.execute(status -> {
            jdbc.execute("SET LOCAL statement_timeout = " + Math.max(1, timeout.toMillis()));
            return work.apply(jdbc);
        });
    }

    /** The plain template, for code that manages its own transaction (catalog fallback). */
    public JdbcTemplate jdbc() {
        return jdbc;
    }
}
