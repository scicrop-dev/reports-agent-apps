package com.infinitestack.reports.repository;

import java.sql.Timestamp;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.infinitestack.reports.catalog.BlockRelease;

/** Which Blocks an admin has released for use. */
@Repository
public class BlockReleaseRepository {

    private final JdbcTemplate jdbc;
    private final AppSchema schema;
    private final SchemaInitializer initializer;
    private final Json json;

    public BlockReleaseRepository(JdbcTemplate jdbc, AppSchema schema, SchemaInitializer initializer, Json json) {
        this.jdbc = jdbc;
        this.schema = schema;
        this.initializer = initializer;
        this.json = json;
    }

    private String table() { return schema.table(SchemaInitializer.T_BLOCK_RELEASE); }

    public Map<String, BlockRelease> findAll() {
        initializer.requireReady();
        return jdbc.query("SELECT * FROM " + table(), (rs, n) -> new BlockRelease(rs.getString("block_id"),
                        rs.getString("block_version"), rs.getString("released_by"), rs.getString("released_by_name"),
                        ReportRepository.instant(rs, "released_at"), json.read(rs.getString("availability"), JsonNode.class)))
                .stream().collect(Collectors.toMap(BlockRelease::blockId, Function.identity()));
    }

    public Optional<BlockRelease> find(String blockId) {
        return Optional.ofNullable(findAll().get(blockId));
    }

    public void save(BlockRelease release) {
        initializer.requireReady();
        jdbc.update("INSERT INTO " + table() + " (block_id, block_version, released_by, released_by_name, released_at, availability)"
                + " VALUES (?, ?, ?, ?, ?, ?::jsonb) ON CONFLICT (block_id) DO UPDATE SET block_version = EXCLUDED.block_version,"
                + " released_by = EXCLUDED.released_by, released_by_name = EXCLUDED.released_by_name,"
                + " released_at = EXCLUDED.released_at, availability = EXCLUDED.availability",
                release.blockId(), release.blockVersion(), release.releasedBy(), release.releasedByName(),
                Timestamp.from(release.releasedAt()), json.write(release.availability()));
    }

    public boolean delete(String blockId) {
        initializer.requireReady();
        return jdbc.update("DELETE FROM " + table() + " WHERE block_id = ?", blockId) > 0;
    }
}
