package com.infinitestack.reports.repository;

import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The schema every table of this app lives in.
 *
 * <h3>Why a schema of its own</h3>
 * An Agent App writes into the customer's destination database — the same one holding their
 * business tables. Landing its tables in {@code public} puts them in the middle of everything the
 * customer owns: hard to tell apart, easy to trip over on a dump, and impossible to grant or revoke
 * as a unit. A dedicated schema makes "everything the apps created" a single, addressable thing.
 *
 * <h3>Why the SQL is qualified instead of relying on search_path</h3>
 * Setting {@code search_path} on the connection would look tidier, but it is not deterministic
 * here: Postgres accepts a {@code search_path} naming a schema that does not exist yet and silently
 * falls through to the next one. Between the pool opening its first connection and the schema being
 * created, a {@code CREATE TABLE} would land in {@code public} — and nothing would report it.
 * Qualifying every statement removes the ordering question entirely.
 */
@Component
public class AppSchema {

    /** Guards against a configured name being pasted into DDL — it is config, but it is still SQL. */
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]*");

    private static final String DEFAULT = "apps";

    @Value("${infinitestack.app.schema:apps}")
    private String configured;

    public String name() {
        String candidate = (configured == null || configured.isBlank()) ? DEFAULT : configured.trim();
        if (!SAFE_IDENTIFIER.matcher(candidate).matches()) {
            throw new IllegalStateException("Nome de schema inválido em infinitestack.app.schema: '"
                    + candidate + "'. Use apenas letras, dígitos e underscore.");
        }
        return candidate;
    }

    /** Fully qualified name of a table owned by this app. */
    public String table(String bareName) {
        return name() + "." + bareName;
    }

    public String createSchemaStatement() {
        return "CREATE SCHEMA IF NOT EXISTS " + name();
    }
}
