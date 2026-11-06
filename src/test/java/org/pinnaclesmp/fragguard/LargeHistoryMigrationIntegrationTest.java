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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Migrates a real v4-shaped SQLite database, rather than changing the
 * user_version of a database whose columns are already at v9.
 *
 * The normal CI fixture is bounded. Override -Dfg.validation.rows=200000
 * (or more) in a deliberately provisioned, NON-PRODUCTION test environment
 * to measure migration performance, main-DB and visible WAL peak size on larger fixtures.
 */
class LargeHistoryMigrationIntegrationTest {
    private static final String WORLD_UUID = "99999999-2222-3333-4444-555555555555";
    private static final String NETHER_UUID = "99999999-2222-3333-4444-666666666666";
    private static final String PLAYER = "11111111-2222-3333-4444-555555555555";
    private static final String ADMIN = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final int DEFAULT_ROWS = 30_000;
    private static final byte[] SNAPSHOT = {5, 10, 15, 20};
    @TempDir Path directory;
    private Database db;

    @AfterEach
    void shutdown() {
        if (db != null) {
            db.shutdown();
            db = null;
        }
    }

    @Test
    void realV4HistoryMigratesThroughV9AndRetainsPlayersWorldsAndRollbackSnapshots() throws Exception {
        String requested = System.getenv("FG_VALIDATION_ROWS");
        int rows = requested == null || requested.isBlank()
                ? DEFAULT_ROWS : Integer.parseInt(requested);
        assertTrue(rows >= 100 && rows <= 2_000_000,
                "Choose 100..2000000 synthetic history rows only in a provisioned test environment");
        long started = System.nanoTime();
        seedRealV4(rows);

        long baselineRows;
        long baselineBytes = Files.size(databasePath());
        try (Connection sql = raw()) {
            baselineRows = scalar(sql, "SELECT COUNT(*) FROM block_changes");
            assertEquals(rows + 2L, baselineRows);
            assertEquals(4L, scalar(sql, "PRAGMA user_version"));
            assertFalse(hasColumn(sql, "block_changes", "causal_player_uuid"),
                    "genuine v4 has no causal player fields before v5+ migration");
            assertFalse(hasColumn(sql, "rollback_jobs", "scope_type"),
                    "genuine v4 has no player-scope job metadata");
        }

        long migrationStarted = System.nanoTime();
        long peakDb;
        long peakWal;
        long peakJournal;
        try (FilePeakProbe probe = new FilePeakProbe(databasePath())) {
            db = start(0);
            assertTrue(db.health().healthy());
            probe.sample();
            peakDb = probe.peakDb.get();
            peakWal = probe.peakWal.get();
            peakJournal = probe.peakJournal.get();
        }
        long migratedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - migrationStarted);

        try (Connection sql = raw()) {
            assertEquals(9L, scalar(sql, "PRAGMA user_version"));
            assertEquals(baselineRows, scalar(sql, "SELECT COUNT(*) FROM block_changes"));
            assertEquals(1L, scalar(sql, "SELECT COUNT(*) FROM rollback_jobs"));
            assertEquals(1L, scalar(sql, "SELECT COUNT(*) FROM rollback_job_changes"));
            assertEquals(1L, scalar(sql, "SELECT COUNT(*) FROM block_changes WHERE actor_name='OldBuilder'"));
            assertEquals(1L, scalar(sql, "SELECT COUNT(*) FROM block_changes WHERE actor_name='Builder'"));
            assertEquals(1L, scalar(sql, "SELECT COUNT(*) FROM block_changes WHERE world_uuid='"
                    + NETHER_UUID + "' AND actor_name='OldBuilder'"));
            assertEquals("RADIUS", singleString(sql, "SELECT scope_type FROM rollback_jobs WHERE id=7"));
            assertEquals(WORLD_UUID, singleString(sql,
                    "SELECT world_uuid FROM rollback_job_changes WHERE job_id=7"));
            assertArrayEquals(SNAPSHOT, singleBlob(sql,
                    "SELECT before_entity_data FROM block_changes WHERE actor_name='OldBuilder'"));
            assertArrayEquals(SNAPSHOT, singleBlob(sql,
                    "SELECT target_entity_data FROM rollback_job_changes WHERE job_id=7"));
            assertEquals("ok", singleString(sql, "PRAGMA integrity_check(1)"));
            for (String name : List.of("idx_fg_actor_time", "idx_fg_actor_world_time",
                    "idx_fg_actor_name_uuid", "idx_fg_causal_actor_time", "idx_fg_causal_actor_world_time",
                    "idx_fg_causal_name_uuid", "idx_fg_spatial_time", "idx_fg_job_chunk_overlap")) {
                assertEquals(1L, scalar(sql, "SELECT COUNT(*) FROM sqlite_master"
                        + " WHERE type='index' AND name='" + name + "'"), name);
            }
            assertEquals(0L, scalar(sql, """
                    SELECT COUNT(*) FROM sqlite_master
                    WHERE type='index' AND name='idx_fg_coordinate_time'
                    """), "v4→v9 should not create a global coordinate index on 30K+ events");
            assertIndexPlan(sql, "idx_fg_spatial_time", """
                    SELECT id FROM block_changes
                    WHERE world_uuid='99999999-2222-3333-4444-555555555555'
                      AND chunk_x=0 AND chunk_z=0
                      AND x=3 AND y=64 AND z=3
                      AND rollback_pending=0
                      AND happened_at > 0
                    ORDER BY happened_at DESC, id DESC LIMIT 8193
                    """);
            assertIndexPlan(sql, "idx_fg_actor_time",
                    "SELECT id FROM block_changes WHERE actor_uuid='" + PLAYER
                            + "' AND length(actor_uuid) IN (32, 36)"
                            + " ORDER BY happened_at DESC, id DESC LIMIT 1");
            assertIndexPlan(sql, "idx_fg_actor_world_time",
                    "SELECT id FROM block_changes WHERE actor_uuid='" + PLAYER
                            + "' AND length(actor_uuid) IN (32, 36)"
                            + " AND world_uuid='" + WORLD_UUID + "'"
                            + " ORDER BY happened_at DESC, id DESC LIMIT 1");
            assertIndexPlan(sql, "idx_fg_actor_name_uuid",
                    "SELECT actor_uuid FROM block_changes WHERE actor_name='OldBuilder' COLLATE NOCASE"
                            + " AND length(actor_uuid) IN (32, 36)");
        }

        assertEquals(UUID.fromString(PLAYER), db.resolveHistoricalPlayerAsync("OldBuilder").join().uuid());
        assertEquals("Builder", db.resolveHistoricalPlayerAsync("OldBuilder").join().latestName());
        assertEquals(2, db.lookupPlayerAsync("OldBuilder", null, 1, 10, 0, 30).join().totalRows());
        assertEquals(1, db.lookupPlayerAsync("OldBuilder", "world_nether", 1, 10, 0, 30)
                .join().totalRows());
        assertEquals(1, db.loadRollbackChangesAsync(7, false).join().size());
        assertEquals("RADIUS", db.loadRollbackJobAsync(7).join().scopeType());

        long now = System.currentTimeMillis();
        db.insertRequiredAsync(List.of(
                new BlockChange(now - 1500, "SYSTEM", "Explosion: TNT", "world",
                        4, 64, 4, ChangeAction.EXPLOSION, "minecraft:stone", "minecraft:air")
                        .withCausalAttribution(new CausalAttribution(PLAYER, "Builder",
                                "validation-chain", "TNT_ENTITY")))).join();

        assertEquals(3, db.lookupPlayerAsync("OldBuilder", null, 1, 20, 0, 30).join().totalRows());
        PlayerRollbackPlan plan = db.planPlayerRollbackAsync("OldBuilder", null, now - 5_000, now,
                20, 1024 * 1024, 30, false).join();
        assertEquals(1, plan.targets().size(),
                "new causal event must be visible as a valid player-owned rollback candidate");
        assertEquals(4, plan.targets().getFirst().x());
        assertEquals("Explosion: TNT", plan.targets().getFirst().physicalActorName());

        db.shutdown();
        db = null;

        long finalBytes = Files.size(databasePath());
        try (Connection sql = raw()) {
            assertEquals(9L, scalar(sql, "PRAGMA user_version"));
            assertEquals(baselineRows + 1L, scalar(sql, "SELECT COUNT(*) FROM block_changes"));
            assertEquals("ok", singleString(sql, "PRAGMA integrity_check(1)"));
            assertTrue(scalar(sql, "PRAGMA page_count") >= 1L);
            System.out.println("FG synthetic v4->v9 migration: fixtureRows=" + rows
                    + ", baselineDbBytes=" + baselineBytes
                    + ", finalDbBytes=" + finalBytes
                    + ", migrationElapsedMillis=" + migratedMillis
                    + ", peakVisibleDbBytes=" + peakDb
                    + ", peakVisibleWalBytes=" + peakWal
                    + ", peakVisibleJournalBytes=" + peakJournal
                    + ", walAfterShutdownBytes=" + sidecarBytes("-wal")
                    + " (native temp and hosting quota cannot be measured here)");
        }
        // Reopen upgraded v9 without running any further migrations.
        db = start(0);
        assertEquals(3, db.lookupPlayerAsync(PLAYER, null, 1, 20, 0, 30).join().totalRows());
    }

    @Test
    void insufficientQuotaRefusesGenuineV4AndRetryPreservesAllRows() throws Exception {
        seedRealV4(1000);
        SQLException refused = assertThrows(SQLException.class, () -> start(1),
                "known 1 MiB account free space cannot satisfy index migration headroom");
        assertNotNull(refused.getCause());
        assertTrue(refused.getCause().getMessage().contains("account free"));
        try (Connection sql = raw()) {
            assertEquals(4L, scalar(sql, "PRAGMA user_version"));
            assertEquals(1002L, scalar(sql, "SELECT COUNT(*) FROM block_changes"));
            assertEquals(1L, scalar(sql, "SELECT COUNT(*) FROM rollback_jobs"));
            assertEquals(1L, scalar(sql, "SELECT COUNT(*) FROM rollback_job_changes"));
            assertEquals("ok", singleString(sql, "PRAGMA integrity_check(1)"));
        }
        assertFalse(Files.exists(directory.resolve("backups")),
                "index-only failure must not make an unnecessary full database backup");

        db = start(0);
        assertTrue(db.health().healthy());
        try (Connection sql = raw()) {
            assertEquals(9L, scalar(sql, "PRAGMA user_version"));
            assertEquals(1002L, scalar(sql, "SELECT COUNT(*) FROM block_changes"));
            assertArrayEquals(SNAPSHOT, singleBlob(sql,
                    "SELECT target_entity_data FROM rollback_job_changes WHERE job_id=7"));
            assertEquals("ok", singleString(sql, "PRAGMA integrity_check(1)"));
        }
    }

    /** Exact v4-shaped table columns and core indexes from FG 26.3-1.2.0. */
    private void seedRealV4(int systemRows) throws Exception {
        try (Connection sql = raw(); Statement ddl = sql.createStatement()) {
            ddl.execute("PRAGMA journal_mode=WAL");
            ddl.execute("""
                    CREATE TABLE block_changes (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        happened_at INTEGER NOT NULL, actor_uuid TEXT NOT NULL, actor_name TEXT NOT NULL,
                        world TEXT NOT NULL, x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL,
                        action TEXT NOT NULL, before_data TEXT NOT NULL, after_data TEXT NOT NULL,
                        world_uuid TEXT NOT NULL DEFAULT '', chunk_x INTEGER NOT NULL DEFAULT 0,
                        chunk_z INTEGER NOT NULL DEFAULT 0, coalesce_session TEXT, server_tick INTEGER,
                        before_entity_data BLOB, after_entity_data BLOB,
                        rollback_pending INTEGER NOT NULL DEFAULT 0
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
                        undo_processed_blocks INTEGER NOT NULL DEFAULT 0, last_error TEXT
                    )
                    """);
            ddl.execute("""
                    CREATE TABLE rollback_job_changes (
                        job_id INTEGER NOT NULL, sequence INTEGER NOT NULL, world TEXT NOT NULL,
                        x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL,
                        before_data TEXT, target_data TEXT NOT NULL, expected_data TEXT,
                        processed INTEGER NOT NULL DEFAULT 0,
                        applied INTEGER NOT NULL DEFAULT 0,
                        conflicted INTEGER NOT NULL DEFAULT 0,
                        undone INTEGER NOT NULL DEFAULT 0,
                        before_entity_data BLOB, target_entity_data BLOB,
                        expected_entity_data BLOB, applied_data TEXT, applied_entity_data BLOB,
                        pending_audit_id INTEGER, pending_audit_undo INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(job_id, sequence),
                        FOREIGN KEY(job_id) REFERENCES rollback_jobs(id) ON DELETE CASCADE
                    )
                    """);
            ddl.execute("CREATE INDEX idx_fg_time ON block_changes(happened_at)");
            ddl.execute("CREATE INDEX idx_fg_spatial_time ON block_changes(world_uuid, chunk_x, chunk_z, happened_at)");
            ddl.execute("CREATE INDEX idx_fg_active_jobs ON rollback_jobs(world_uuid, status)");
            ddl.execute("CREATE INDEX idx_fg_pending_changes ON rollback_job_changes(job_id, processed, sequence)");
            ddl.execute("CREATE INDEX idx_fg_undo_changes ON rollback_job_changes(job_id, undone, sequence)");
            ddl.execute("PRAGMA user_version=4");
        }

        long now = System.currentTimeMillis();
        try (Connection sql = raw()) {
            sql.setAutoCommit(false);
            try (PreparedStatement insert = sql.prepareStatement("""
                    INSERT INTO block_changes (happened_at, actor_uuid, actor_name, world,
                        world_uuid, x, y, z, chunk_x, chunk_z, action,
                        before_data, after_data, before_entity_data, after_entity_data)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                // Two real-player events, including a historical username change,
                // in two distinct worlds. Include a legacy block entity snapshot.
                insertChange(insert, now - 40_000, PLAYER, "OldBuilder", "world_nether",
                        NETHER_UUID, 2, 64, 2, SNAPSHOT);
                insertChange(insert, now - 30_000, PLAYER, "Builder", "world",
                        WORLD_UUID, 3, 64, 3, null);
                insert.executeBatch();

                for (int i = 0; i < systemRows; i++) {
                    String world = i % 2 == 0 ? "world" : "world_nether";
                    String worldUuid = i % 2 == 0 ? WORLD_UUID : NETHER_UUID;
                    int x = 100 + i % 400;
                    int z = 100 + i / 400;
                    insertChange(insert, now - 20_000 + i % 12_000, "SYSTEM", "System",
                            world, worldUuid, x, 65, z, null);
                    if (i % 1000 == 999) {
                        insert.executeBatch();
                    }
                }
                insert.executeBatch();
            }
            try (PreparedStatement job = sql.prepareStatement("""
                    INSERT INTO rollback_jobs
                    (id, created_at, updated_at, actor_uuid, actor_name, world_uuid, world,
                     center_x, center_z, radius, target_timestamp, snapshot_timestamp, status, total_blocks)
                    VALUES (7, ?, ?, ?, 'Admin', ?, 'world', 0, 0, 10, ?, ?, 'COMPLETED', 1)
                    """)) {
                job.setLong(1, now - 500_000);
                job.setLong(2, now - 400_000);
                job.setString(3, ADMIN);
                job.setString(4, WORLD_UUID);
                job.setLong(5, now - 600_000);
                job.setLong(6, now - 450_000);
                job.executeUpdate();
            }
            try (PreparedStatement change = sql.prepareStatement("""
                    INSERT INTO rollback_job_changes
                    (job_id, sequence, world, x, y, z, before_data, target_data, expected_data,
                     before_entity_data, target_entity_data, expected_entity_data)
                    VALUES (7, 0, 'world', 9, 64, 9, 'minecraft:stone',
                            'minecraft:air', 'minecraft:stone', ?, ?, ?)
                    """)) {
                change.setBytes(1, SNAPSHOT);
                change.setBytes(2, SNAPSHOT);
                change.setBytes(3, SNAPSHOT);
                change.executeUpdate();
            }
            sql.commit();
        }
    }

    /**
     * Best-effort visible-file high-water marks. SQLite's native temporary
     * sort files may be unlinked while open, so these cannot prove peak total
     * storage or a managed hosting quota.
     */
    private static final class FilePeakProbe implements AutoCloseable {
        private final Path database;
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final AtomicLong peakDb = new AtomicLong();
        private final AtomicLong peakWal = new AtomicLong();
        private final AtomicLong peakJournal = new AtomicLong();
        private final Thread sampler;

        private FilePeakProbe(Path database) {
            this.database = database;
            sample();
            sampler = new Thread(() -> {
                while (running.get()) {
                    sample();
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException ignored) {
                        break;
                    }
                }
            }, "FG-Migration-Fixture-Sampler");
            sampler.setDaemon(true);
            sampler.start();
        }

        private void sample() {
            peakDb.accumulateAndGet(size(database), Math::max);
            peakWal.accumulateAndGet(size(Path.of(database.toString() + "-wal")), Math::max);
            peakJournal.accumulateAndGet(size(Path.of(database.toString() + "-journal")), Math::max);
        }

        private static long size(Path path) {
            try {
                return Files.exists(path) ? Files.size(path) : 0L;
            } catch (Exception ignored) {
                return 0L;
            }
        }

        @Override
        public void close() throws InterruptedException {
            running.set(false);
            sampler.interrupt();
            sampler.join(2_000L);
            sample();
        }
    }

    private void insertChange(PreparedStatement stmt, long at, String uuid, String name,
                              String world, String worldUuid, int x, int y, int z,
                              byte[] snapshot) throws SQLException {
        stmt.setLong(1, at);
        stmt.setString(2, uuid);
        stmt.setString(3, name);
        stmt.setString(4, world);
        stmt.setString(5, worldUuid);
        stmt.setInt(6, x);
        stmt.setInt(7, y);
        stmt.setInt(8, z);
        stmt.setInt(9, x >> 4);
        stmt.setInt(10, z >> 4);
        stmt.setString(11, "block.break");
        stmt.setString(12, "minecraft:stone");
        stmt.setString(13, "minecraft:air");
        stmt.setBytes(14, snapshot);
        stmt.setBytes(15, null);
        stmt.addBatch();
    }

    private Database start(long accountFreeMiB) throws Exception {
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        World overworld = mock(World.class);
        World nether = mock(World.class);
        YamlConfiguration config = new YamlConfiguration();
        config.set("database-query-timeout-seconds", 30);
        config.set("database-write-queue-capacity", 10000);
        config.set("database-write-batch-size", 500);
        if (accountFreeMiB > 0) config.set("database-migration-account-free-mib", accountFreeMiB);

        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("LargeHistoryMigrationIntegrationTest"));
        when(server.getCurrentTick()).thenReturn(100);
        when(server.getWorlds()).thenReturn(List.of(overworld, nether));
        when(server.getWorld("world")).thenReturn(overworld);
        when(server.getWorld("world_nether")).thenReturn(nether);
        when(overworld.getName()).thenReturn("world");
        when(overworld.getUID()).thenReturn(UUID.fromString(WORLD_UUID));
        when(nether.getName()).thenReturn("world_nether");
        when(nether.getUID()).thenReturn(UUID.fromString(NETHER_UUID));

        Database candidate = new Database(plugin);
        candidate.init();
        return candidate;
    }

    private Path databasePath() {
        return directory.resolve("fragguard.db");
    }

    private Connection raw() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + databasePath());
    }

    private long sidecarBytes(String suffix) {
        Path file = Path.of(databasePath().toString() + suffix);
        try { return Files.exists(file) ? Files.size(file) : 0; }
        catch (Exception ignored) { return -1; }
    }

    private static long scalar(Connection c, String sql) throws SQLException {
        try (Statement stmt = c.createStatement();
             ResultSet result = stmt.executeQuery(sql)) {
            assertTrue(result.next(), sql);
            return result.getLong(1);
        }
    }

    private static String singleString(Connection c, String sql) throws SQLException {
        try (Statement stmt = c.createStatement();
             ResultSet result = stmt.executeQuery(sql)) {
            assertTrue(result.next(), sql);
            return result.getString(1);
        }
    }

    private static byte[] singleBlob(Connection c, String sql) throws SQLException {
        try (Statement stmt = c.createStatement();
             ResultSet result = stmt.executeQuery(sql)) {
            assertTrue(result.next(), sql);
            return result.getBytes(1);
        }
    }

    private static boolean hasColumn(Connection c, String table, String column) throws SQLException {
        try (Statement stmt = c.createStatement();
             ResultSet info = stmt.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (info.next()) if (column.equals(info.getString("name"))) return true;
            return false;
        }
    }

    private static void assertIndexPlan(Connection c, String index, String sql) throws SQLException {
        try (Statement stmt = c.createStatement();
             ResultSet explanation = stmt.executeQuery("EXPLAIN QUERY PLAN " + sql)) {
            boolean found = false;
            while (explanation.next()) {
                found |= explanation.getString("detail").contains(index);
            }
            assertTrue(found, "expected query planner to use " + index + " for: " + sql);
        }
    }
}
