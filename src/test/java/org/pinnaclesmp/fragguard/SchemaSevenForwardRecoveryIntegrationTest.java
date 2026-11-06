package org.pinnaclesmp.fragguard;

import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests an actual v7-shaped database, not a v9 schema with a rewritten
 * user_version. Validates the no-v4-backup forward-recovery path.
 */
class SchemaSevenForwardRecoveryIntegrationTest {
    private static final String OVERWORLD = "99999999-2222-3333-4444-555555555555";
    private static final String NETHER = "99999999-2222-3333-4444-666666666666";
    private static final String PLAYER = "11111111-2222-3333-4444-555555555555";
    private static final String ADMIN = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final byte[] SNAPSHOT = {1, 14, 20, 11, 6};
    @TempDir Path directory;
    private Database database;

    @AfterEach
    void shutdown() {
        if (database != null) {
            database.shutdown();
            database = null;
        }
    }

    @Test
    void preservesV7AfterPreflightRefusalThenForwardsToV9WithoutOldBackup() throws Exception {
        seedGenuineV7Schema();
        assertV7Preserved();
        SQLException refused = assertThrows(SQLException.class, () -> start(1),
                "declared 1 MiB remaining account space must refuse the preflight");
        assertTrue(refused.getCause().getMessage().contains("account free"),
                "the reason should be explicit, not silently modifying the schema");
        assertV7Preserved();
        assertNoAutomaticFullBackup();

        database = start(0);
        assertTrue(database.health().healthy());
        assertV9PreservesAllHistory();
        assertEquals(2, database.lookupPlayerAsync("OldBuilder", null, 1, 20, 0, 30)
                .join().totalRows(), "direct and causal history retained");
        assertEquals(1, database.loadRollbackChangesAsync(7, false).join().size());
        assertEquals("RADIUS", database.loadRollbackJobAsync(7).join().scopeType());

        database.shutdown();
        database = null;
        try (Connection raw = open()) {
            assertEquals("ok", string(raw, "PRAGMA integrity_check(1)"));
        }
        database = start(0); // no migrations run on a current schema
        assertV9PreservesAllHistory();
        assertNoAutomaticFullBackup();
    }

    @Test
    void failedV8ToV9TransactionRollsBackColumnsAndSnapshotChangesThenResumes() throws Exception {
        seedGenuineV7Schema();
        // This trigger deliberately fails *inside* the v8->v9 update that
        // backfills world_uuid. It is test-only and never installed by FG.
        try (Connection raw = open(); Statement stmt = raw.createStatement()) {
            stmt.execute("""
                    CREATE TRIGGER test_abort_v9_backfill
                    BEFORE UPDATE ON rollback_job_changes
                    BEGIN SELECT RAISE(ABORT, 'synthetic v8 to v9 failure'); END
                    """);
        }

        SQLException failure = assertThrows(SQLException.class, () -> start(0));
        assertTrue(failure.getCause().getMessage().contains("synthetic v8 to v9 failure"),
                "the primary failure must survive rollback");
        try (Connection raw = open()) {
            assertEquals(8, scalar(raw, "PRAGMA user_version"),
                    "v7->v8 is committed; the failing v8->v9 transaction must roll back");
            assertFalse(columnExists(raw, "rollback_jobs", "scope_type"),
                    "DDL added by failed v8->v9 must roll back atomically");
            assertFalse(columnExists(raw, "rollback_job_changes", "world_uuid"));
            assertEquals(0, scalar(raw, """
                    SELECT COUNT(*) FROM sqlite_master
                    WHERE type='table' AND name='rollback_job_chunks'
                    """));
            assertLegacyRecords(raw);
            assertEquals("ok", string(raw, "PRAGMA integrity_check(1)"));
        }
        assertNoAutomaticFullBackup();

        try (Connection raw = open(); Statement stmt = raw.createStatement()) {
            stmt.execute("DROP TRIGGER test_abort_v9_backfill");
        }
        database = start(0);
        assertV9PreservesAllHistory();
        database.shutdown();
        database = null;
        database = start(0);
        assertV9PreservesAllHistory();
        assertNoAutomaticFullBackup();
    }

    @Test
    void failureOfV7ChunkSafetyGuardKeepsOriginalSnapshotsAndVersionIntact()
            throws Exception {
        seedGenuineV7Schema();
        try (Connection raw = open(); Statement stmt = raw.createStatement()) {
            stmt.execute("UPDATE block_changes SET chunk_x = 999 WHERE id = 11");
        }
        SQLException failed = assertThrows(SQLException.class, () -> start(0));
        assertTrue(failed.getCause().getMessage().contains("inconsistent historical chunk"));
        try (Connection raw = open()) {
            assertEquals(7, scalar(raw, "PRAGMA user_version"));
            assertEquals(3, scalar(raw, "SELECT COUNT(*) FROM block_changes"));
            assertArrayEquals(SNAPSHOT, blob(raw,
                    "SELECT target_entity_data FROM rollback_job_changes WHERE job_id=7"));
            assertEquals(999, scalar(raw, "SELECT chunk_x FROM block_changes WHERE id=11"),
                    "startup must never repair the live history by guessing");
        }
        assertNoAutomaticFullBackup();
        // Correct the known synthetic inconsistency *on the test fixture*.
        // Production repair, if needed, requires an offline snapshot/review.
        try (Connection raw = open(); Statement stmt = raw.createStatement()) {
            stmt.execute("UPDATE block_changes SET chunk_x = x >> 4 WHERE id = 11");
        }
        database = start(0);
        assertV9PreservesAllHistory();
    }

    private void assertV7Preserved() throws Exception {
        try (Connection raw = open()) {
            assertEquals(7, scalar(raw, "PRAGMA user_version"));
            assertFalse(columnExists(raw, "rollback_jobs", "scope_type"));
            assertFalse(columnExists(raw, "rollback_job_changes", "world_uuid"));
            assertEquals(0, scalar(raw, """
                    SELECT COUNT(*) FROM sqlite_master WHERE name = 'idx_fg_coordinate_time'
                    """));
            assertLegacyRecords(raw);
        }
    }

    private void assertV9PreservesAllHistory() throws Exception {
        try (Connection raw = open()) {
            assertEquals(9, scalar(raw, "PRAGMA user_version"));
            assertLegacyRecords(raw);
            assertTrue(columnExists(raw, "rollback_jobs", "scope_type"));
            assertTrue(columnExists(raw, "rollback_job_changes", "world_uuid"));
            assertEquals("RADIUS", string(raw,
                    "SELECT scope_type FROM rollback_jobs WHERE id=7"));
            assertEquals(OVERWORLD, string(raw,
                    "SELECT world_uuid FROM rollback_job_changes WHERE job_id=7"));
            assertEquals(1, scalar(raw, """
                    SELECT COUNT(*) FROM sqlite_master
                    WHERE type='table' AND name='rollback_job_chunks'
                    """));
            assertEquals(0, scalar(raw, """
                    SELECT COUNT(*) FROM sqlite_master
                    WHERE type='index' AND name='idx_fg_coordinate_time'
                    """), "forward recovery must not create the enormous failing index");
            assertEquals("ok", string(raw, "PRAGMA integrity_check(1)"));
        }
    }

    private void assertLegacyRecords(Connection raw) throws Exception {
        assertEquals(3, scalar(raw, "SELECT COUNT(*) FROM block_changes"));
        assertEquals(1, scalar(raw, "SELECT COUNT(*) FROM rollback_jobs"));
        assertEquals(1, scalar(raw, "SELECT COUNT(*) FROM rollback_job_changes"));
        assertEquals(1, scalar(raw, "SELECT COUNT(*) FROM block_changes WHERE actor_name='OldBuilder'"));
        assertEquals(1, scalar(raw, "SELECT COUNT(*) FROM block_changes WHERE actor_name='Explosion: TNT'"));
        assertEquals(1, scalar(raw, "SELECT COUNT(*) FROM block_changes WHERE actor_name='Water Spread'"));
        assertArrayEquals(SNAPSHOT, blob(raw,
                "SELECT before_entity_data FROM block_changes WHERE id=11"));
        assertArrayEquals(SNAPSHOT, blob(raw,
                "SELECT target_entity_data FROM rollback_job_changes WHERE job_id=7"));
        assertEquals("ok", string(raw, "PRAGMA integrity_check(1)"));
    }

    private void assertNoAutomaticFullBackup() {
        assertFalse(Files.exists(directory.resolve("backups")),
                "a v7+ forward migration must not require an unavailable v4 backup or "
                        + "silently duplicate gigabytes on the managed host");
    }

    /**
     * Exact v7 column/index shape: original v4 structures plus v5 actor
     * indexes, v6 causal fields and v7 causal name index. No v8 coordinate
     * index or v9 player-job fields exist in the starting fixture.
     */
    private void seedGenuineV7Schema() throws Exception {
        try (Connection raw = open(); Statement ddl = raw.createStatement()) {
            ddl.execute("PRAGMA journal_mode=WAL");
            ddl.execute("""
                    CREATE TABLE block_changes (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        happened_at INTEGER NOT NULL,
                        actor_uuid TEXT NOT NULL, actor_name TEXT NOT NULL,
                        world TEXT NOT NULL, x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL,
                        action TEXT NOT NULL, before_data TEXT NOT NULL, after_data TEXT NOT NULL,
                        world_uuid TEXT NOT NULL DEFAULT '', chunk_x INTEGER NOT NULL DEFAULT 0,
                        chunk_z INTEGER NOT NULL DEFAULT 0, coalesce_session TEXT, server_tick INTEGER,
                        before_entity_data BLOB, after_entity_data BLOB,
                        rollback_pending INTEGER NOT NULL DEFAULT 0,
                        causal_player_uuid TEXT, causal_player_name TEXT,
                        causal_chain_id TEXT, causal_cause_type TEXT
                    )
                    """);
            ddl.execute("""
                    CREATE TABLE rollback_jobs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
                        actor_uuid TEXT NOT NULL, actor_name TEXT NOT NULL,
                        world_uuid TEXT NOT NULL, world TEXT NOT NULL,
                        center_x INTEGER NOT NULL, center_z INTEGER NOT NULL,
                        radius INTEGER NOT NULL, target_timestamp INTEGER NOT NULL,
                        snapshot_timestamp INTEGER NOT NULL DEFAULT 0, force INTEGER NOT NULL DEFAULT 0,
                        status TEXT NOT NULL, total_blocks INTEGER NOT NULL,
                        processed_blocks INTEGER NOT NULL DEFAULT 0,
                        applied_blocks INTEGER NOT NULL DEFAULT 0,
                        conflict_blocks INTEGER NOT NULL DEFAULT 0,
                        undo_processed_blocks INTEGER NOT NULL DEFAULT 0,
                        last_error TEXT
                    )
                    """);
            ddl.execute("""
                    CREATE TABLE rollback_job_changes (
                        job_id INTEGER NOT NULL, sequence INTEGER NOT NULL, world TEXT NOT NULL,
                        x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL,
                        before_data TEXT, target_data TEXT NOT NULL, expected_data TEXT,
                        processed INTEGER NOT NULL DEFAULT 0, applied INTEGER NOT NULL DEFAULT 0,
                        conflicted INTEGER NOT NULL DEFAULT 0, undone INTEGER NOT NULL DEFAULT 0,
                        before_entity_data BLOB, target_entity_data BLOB, expected_entity_data BLOB,
                        applied_data TEXT, applied_entity_data BLOB, pending_audit_id INTEGER,
                        pending_audit_undo INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(job_id, sequence),
                        FOREIGN KEY(job_id) REFERENCES rollback_jobs(id) ON DELETE CASCADE
                    )
                    """);
            ddl.execute("CREATE INDEX idx_fg_time ON block_changes(happened_at)");
            ddl.execute("""
                    CREATE INDEX idx_fg_spatial_time
                    ON block_changes(world_uuid, chunk_x, chunk_z, happened_at)
                    """);
            ddl.execute("CREATE INDEX idx_fg_active_jobs ON rollback_jobs(world_uuid, status)");
            ddl.execute("CREATE INDEX idx_fg_pending_changes ON rollback_job_changes(job_id, processed, sequence)");
            ddl.execute("CREATE INDEX idx_fg_undo_changes ON rollback_job_changes(job_id, undone, sequence)");
            ddl.execute(HistoricalActorIndexPlan.ACTOR_TIME_SQL);
            ddl.execute(HistoricalActorIndexPlan.ACTOR_WORLD_TIME_SQL);
            ddl.execute(HistoricalActorIndexPlan.ACTOR_NAME_SQL);
            ddl.execute("""
                    CREATE INDEX idx_fg_causal_actor_time
                    ON block_changes(causal_player_uuid, happened_at DESC, id DESC)
                    WHERE causal_player_uuid IS NOT NULL
                    """);
            ddl.execute("""
                    CREATE INDEX idx_fg_causal_actor_world_time
                    ON block_changes(causal_player_uuid, world_uuid, happened_at DESC, id DESC)
                    WHERE causal_player_uuid IS NOT NULL
                    """);
            ddl.execute("""
                    CREATE INDEX idx_fg_causal_name_uuid
                    ON block_changes(causal_player_name COLLATE NOCASE, causal_player_uuid)
                    WHERE causal_player_uuid IS NOT NULL
                    """);
            ddl.execute("PRAGMA user_version=7");
        }

        long now = System.currentTimeMillis();
        try (Connection raw = open()) {
            raw.setAutoCommit(false);
            try (PreparedStatement history = raw.prepareStatement("""
                    INSERT INTO block_changes
                    (id, happened_at, actor_uuid, actor_name, world, world_uuid, x, y, z,
                     chunk_x, chunk_z, action, before_data, after_data,
                     before_entity_data, causal_player_uuid, causal_player_name, causal_chain_id, causal_cause_type)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'block.break',
                            'minecraft:stone', 'minecraft:air', ?, ?, ?, ?, ?)
                    """)) {
                writeHistory(history, 11, now - 5000, PLAYER, "OldBuilder", "world",
                        OVERWORLD, -17, 64, -33, SNAPSHOT, null, null, null, null);
                writeHistory(history, 12, now - 4000, "SYSTEM", "Explosion: TNT",
                        "world_nether", NETHER, 12, 64, 12, null, PLAYER,
                        "Builder", "chain-1", "TNT_ENTITY");
                writeHistory(history, 13, now - 3000, "SYSTEM", "Water Spread",
                        "world", OVERWORLD, 32, 63, 32, null, null, null, null, null);
                history.executeBatch();
            }
            try (PreparedStatement job = raw.prepareStatement("""
                    INSERT INTO rollback_jobs
                    (id, created_at, updated_at, actor_uuid, actor_name, world_uuid, world,
                     center_x, center_z, radius, target_timestamp, snapshot_timestamp,
                     status, total_blocks)
                    VALUES(7, ?, ?, ?, 'Admin', ?, 'world', 0, 0, 10, ?, ?, 'COMPLETED', 1)
                    """)) {
                job.setLong(1, now - 30_000);
                job.setLong(2, now - 20_000);
                job.setString(3, ADMIN);
                job.setString(4, OVERWORLD);
                job.setLong(5, now - 40_000);
                job.setLong(6, now - 25_000);
                job.executeUpdate();
            }
            try (PreparedStatement change = raw.prepareStatement("""
                    INSERT INTO rollback_job_changes
                    (job_id, sequence, world, x, y, z, before_data, target_data, expected_data,
                     before_entity_data, target_entity_data, expected_entity_data)
                    VALUES(7, 0, 'world', -17, 64, -33, 'minecraft:stone', 'minecraft:air',
                           'minecraft:stone', ?, ?, ?)
                    """)) {
                change.setBytes(1, SNAPSHOT);
                change.setBytes(2, SNAPSHOT);
                change.setBytes(3, SNAPSHOT);
                change.executeUpdate();
            }
            raw.commit();
        }
    }

    private static void writeHistory(PreparedStatement stmt, int id, long timestamp,
                                     String actorUuid, String actorName, String worldName,
                                     String worldUuid, int x, int y, int z,
                                     byte[] beforeEntity, String causalUuid, String causalName,
                                     String chainId, String causeType) throws Exception {
        stmt.setInt(1, id);
        stmt.setLong(2, timestamp);
        stmt.setString(3, actorUuid);
        stmt.setString(4, actorName);
        stmt.setString(5, worldName);
        stmt.setString(6, worldUuid);
        stmt.setInt(7, x);
        stmt.setInt(8, y);
        stmt.setInt(9, z);
        stmt.setInt(10, x >> 4);
        stmt.setInt(11, z >> 4);
        stmt.setBytes(12, beforeEntity);
        stmt.setString(13, causalUuid);
        stmt.setString(14, causalName);
        stmt.setString(15, chainId);
        stmt.setString(16, causeType);
        stmt.addBatch();
    }

    private Database start(long knownAccountFreeMib) throws Exception {
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        World main = mock(World.class);
        World nether = mock(World.class);
        YamlConfiguration config = new YamlConfiguration();
        config.set("database-query-timeout-seconds", 30);
        config.set("database-write-queue-capacity", 64);
        config.set("database-write-batch-size", 16);
        if (knownAccountFreeMib > 0) config.set("database-migration-account-free-mib", knownAccountFreeMib);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("SchemaSevenForwardRecoveryIntegrationTest"));
        when(server.getCurrentTick()).thenReturn(200);
        when(server.getWorlds()).thenReturn(List.of(main, nether));
        when(server.getWorld("world")).thenReturn(main);
        when(server.getWorld("world_nether")).thenReturn(nether);
        when(main.getName()).thenReturn("world");
        when(main.getUID()).thenReturn(UUID.fromString(OVERWORLD));
        when(nether.getName()).thenReturn("world_nether");
        when(nether.getUID()).thenReturn(UUID.fromString(NETHER));
        Database candidate = new Database(plugin);
        candidate.init();
        return candidate;
    }

    private Connection open() throws Exception {
        return DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("fragguard.db"));
    }

    private static boolean columnExists(Connection connection, String table, String column)
            throws Exception {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equals(rs.getString("name"))) return true;
            }
        }
        return false;
    }

    private static long scalar(Connection connection, String query) throws Exception {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(query)) {
            assertTrue(rs.next());
            return rs.getLong(1);
        }
    }

    private static String string(Connection connection, String query) throws Exception {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(query)) {
            assertTrue(rs.next());
            return rs.getString(1);
        }
    }

    private static byte[] blob(Connection connection, String query) throws Exception {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(query)) {
            assertTrue(rs.next());
            return rs.getBytes(1);
        }
    }
}
