package org.pinnaclesmp.fragguard;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseDeepDiagnosticsTest {
    @TempDir Path directory;

    @Test
    void completeReadOnlyScanHashesAllTablesAndDoesNotLeakPrivateContent() throws Exception {
        Path db = seededDatabase(8);
        byte[] original = Files.readAllBytes(db);
        try (DatabaseDeepDiagnostics deep = new DatabaseDeepDiagnostics(directory)) {
            DatabaseDeepDiagnostics.Report result = deep.run(30).get(40, TimeUnit.SECONDS);
            assertTrue(result.complete(), result.issues().toString());
            assertTrue(result.passed(), result.issues().toString());
            assertEquals(8, result.tables().get("block_changes").rows());
            assertEquals(1, result.tables().get("rollback_jobs").rows());
            assertEquals(1, result.tables().get("rollback_job_changes").rows());
            assertEquals(1, result.tables().get("rollback_job_chunks").rows());
            assertTrue(result.tables().get("block_changes").blobBytes() >= 8 * 128);
            assertEquals(11, result.scannedRows());
            assertEquals(64, result.tables().get("block_changes").sha256().length());
            assertEquals(64, result.schemaSha256().length());
            assertEquals("finished", deep.progress().phase());

            String text = Files.readString(result.reportFile());
            String manifest = Files.readString(result.manifestFile());
            assertTrue(text.contains("Deep scan: PASS"));
            assertTrue(manifest.contains("format=fragguard-deep-1"));
            assertTrue(manifest.contains("complete=true"));
            assertTrue(manifest.contains("passed=true"));
            for (String privateText : List.of("hidden-private-player", "secret-world",
                    "private-inventory-not-for-report", "minecraft:diamond_block")) {
                assertFalse(text.contains(privateText));
                assertFalse(manifest.contains(privateText));
            }
        }
        assertArrayEquals(original, Files.readAllBytes(db),
                "a closed read-only validation must not rewrite the source main DB");
    }

    @Test
    void changesToBlobHistoryChangeDigestWithoutExposingContents() throws Exception {
        Path db = seededDatabase(2);
        String before;
        try (DatabaseDeepDiagnostics deep = new DatabaseDeepDiagnostics(directory)) {
            before = deep.run(20).get(30, TimeUnit.SECONDS)
                    .tables().get("block_changes").sha256();
        }
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE block_changes SET before_entity_data = ? WHERE id = 1")) {
            statement.setBytes(1, "secret-player-updated".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            statement.executeUpdate();
        }
        try (DatabaseDeepDiagnostics deep = new DatabaseDeepDiagnostics(directory)) {
            DatabaseDeepDiagnostics.Report after = deep.run(20).get(30, TimeUnit.SECONDS);
            assertTrue(after.passed(), after.issues().toString());
            assertNotEquals(before, after.tables().get("block_changes").sha256());
            assertFalse(Files.readString(after.manifestFile()).contains("secret-player-updated"));
        }
    }

    @Test
    void catchesMissingIndexAndOrphanedRollbackRecordsWithoutRepair() throws Exception {
        Path db = seededDatabase(3);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP INDEX idx_fg_spatial_time");
            // Simulate a damaged backup which bypassed enforced FK constraints.
            statement.execute("PRAGMA foreign_keys=OFF");
            statement.execute("INSERT INTO rollback_job_chunks(job_id,world_uuid,chunk_x,chunk_z) "
                    + "VALUES(999,'orphan',1,1)");
        }
        try (DatabaseDeepDiagnostics deep = new DatabaseDeepDiagnostics(directory)) {
            DatabaseDeepDiagnostics.Report report = deep.run(30).get(40, TimeUnit.SECONDS);
            assertTrue(report.complete(), "tables still scanned fully");
            assertFalse(report.passed());
            assertTrue(report.issues().stream().anyMatch(s -> s.contains("idx_fg_spatial_time")));
            assertTrue(report.issues().stream().anyMatch(s -> s.contains("Orphan")));
            assertTrue(Files.readString(report.manifestFile()).contains("passed=false"));
        }
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT count(*) FROM sqlite_master WHERE name='idx_fg_spatial_time'")) {
            assertTrue(rs.next());
            assertEquals(0, rs.getInt(1), "deep diagnostics must not recreate missing indexes");
        }
    }

    @Test
    void missingRequiredTableProducesExplicitUntrustedPartialReport() throws Exception {
        Path db = seededDatabase(1);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE rollback_job_chunks");
        }
        try (DatabaseDeepDiagnostics deep = new DatabaseDeepDiagnostics(directory)) {
            var report = deep.run(30).get(40, TimeUnit.SECONDS);
            assertFalse(report.complete());
            assertFalse(report.passed());
            assertTrue(report.issues().stream().anyMatch(s -> s.contains("rollback_job_chunks")));
            assertTrue(Files.readString(report.manifestFile()).contains("complete=false"));
            assertTrue(Files.readString(report.reportFile()).contains("PARTIAL / UNTRUSTED"));
        }
    }

    @Test
    void manyRowsRemainStreamedAndProgressCountsAreAccurate() throws Exception {
        seededDatabase(6000);
        try (DatabaseDeepDiagnostics deep = new DatabaseDeepDiagnostics(directory)) {
            DatabaseDeepDiagnostics.Report report = deep.run(60).get(70, TimeUnit.SECONDS);
            assertTrue(report.passed(), report.issues().toString());
            assertEquals(6003, report.scannedRows());
            assertEquals(6000, report.tables().get("block_changes").rows());
            assertFalse(deep.progress().running());
        }
    }

    @Test
    void cancellationOfQueuedJobReleasesSlotBeforeAnyDatabaseAccess() throws Exception {
        seededDatabase(1);
        try (DatabaseDeepDiagnostics deep = new DatabaseDeepDiagnostics(directory)) {
            Field field = DatabaseDeepDiagnostics.class.getDeclaredField("worker");
            field.setAccessible(true);
            ExecutorService worker = (ExecutorService) field.get(deep);
            CountDownLatch begun = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            Future<?> blocker = worker.submit(() -> {
                begun.countDown();
                try { release.await(10, TimeUnit.SECONDS); }
                catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            });
            try {
                assertTrue(begun.await(5, TimeUnit.SECONDS));
                CompletableFuture<DatabaseDeepDiagnostics.Report> pending = deep.run(30);
                assertTrue(deep.cancel());
                assertTrue(pending.isCancelled());
                assertFalse(deep.progress().running());
                CompletableFuture<DatabaseDeepDiagnostics.Report> next = deep.run(30);
                release.countDown();
                blocker.get(5, TimeUnit.SECONDS);
                assertTrue(next.get(40, TimeUnit.SECONDS).passed(),
                        "new diagnostic should start after queued cancellation");
                assertEquals(1, Files.list(directory.resolve("diagnostic-reports"))
                                .filter(p -> p.getFileName().toString().endsWith(".manifest")).count(),
                        "cancelled-before-start requests may not open SQLite or emit reports");
            } finally {
                release.countDown();
                blocker.cancel(true);
            }
        }
    }

    private Path seededDatabase(int history) throws Exception {
        Path db = directory.resolve("fragguard.db");
        Class.forName("org.sqlite.JDBC");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement s = c.createStatement()) {
            s.execute("PRAGMA journal_mode=DELETE");
            s.execute("PRAGMA foreign_keys=ON");
            s.execute("PRAGMA user_version=9");
            s.execute("""
                    CREATE TABLE block_changes (
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      happened_at INTEGER NOT NULL,
                      actor_uuid TEXT NOT NULL, actor_name TEXT NOT NULL,
                      world TEXT NOT NULL, world_uuid TEXT NOT NULL,
                      x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL,
                      chunk_x INTEGER NOT NULL, chunk_z INTEGER NOT NULL,
                      action TEXT NOT NULL, before_data TEXT NOT NULL, after_data TEXT NOT NULL,
                      before_entity_data BLOB, after_entity_data BLOB,
                      rollback_pending INTEGER NOT NULL DEFAULT 0,
                      causal_player_uuid TEXT)
                    """);
            s.execute("""
                    CREATE TABLE rollback_jobs (
                      id INTEGER PRIMARY KEY, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
                      actor_uuid TEXT NOT NULL, world_uuid TEXT NOT NULL, status TEXT NOT NULL,
                      total_blocks INTEGER NOT NULL, processed_blocks INTEGER NOT NULL,
                      applied_blocks INTEGER NOT NULL, undo_processed_blocks INTEGER NOT NULL,
                      scope_type TEXT NOT NULL, snapshot_timestamp INTEGER NOT NULL)
                    """);
            s.execute("""
                    CREATE TABLE rollback_job_changes (
                      job_id INTEGER NOT NULL, sequence INTEGER NOT NULL, world TEXT,
                      world_uuid TEXT, before_data TEXT, target_data TEXT,
                      expected_data TEXT, before_entity_data BLOB, target_entity_data BLOB,
                      expected_entity_data BLOB, applied_data TEXT, applied_entity_data BLOB,
                      processed INTEGER, applied INTEGER, conflicted INTEGER, undone INTEGER,
                      pending_audit_id INTEGER, pending_audit_undo INTEGER,
                      PRIMARY KEY(job_id,sequence),
                      FOREIGN KEY(job_id) REFERENCES rollback_jobs(id) ON DELETE CASCADE)
                    """);
            s.execute("""
                    CREATE TABLE rollback_job_chunks (
                      job_id INTEGER NOT NULL, world_uuid TEXT NOT NULL,
                      chunk_x INTEGER NOT NULL, chunk_z INTEGER NOT NULL,
                      PRIMARY KEY(job_id,world_uuid,chunk_x,chunk_z),
                      FOREIGN KEY(job_id) REFERENCES rollback_jobs(id) ON DELETE CASCADE)
                    """);
            for (String index : List.of(
                    "CREATE INDEX idx_fg_time ON block_changes(happened_at)",
                    "CREATE INDEX idx_fg_spatial_time ON block_changes(world_uuid,chunk_x,chunk_z,happened_at)",
                    "CREATE INDEX idx_fg_actor_time ON block_changes(actor_uuid,happened_at DESC,id DESC)",
                    "CREATE INDEX idx_fg_actor_world_time ON block_changes(actor_uuid,world_uuid,happened_at DESC,id DESC)",
                    "CREATE INDEX idx_fg_actor_name_uuid ON block_changes(actor_name,actor_uuid)",
                    "CREATE INDEX idx_fg_causal_actor_time ON block_changes(causal_player_uuid,happened_at DESC,id DESC)",
                    "CREATE INDEX idx_fg_causal_actor_world_time ON block_changes(causal_player_uuid,world_uuid,happened_at DESC,id DESC)",
                    "CREATE INDEX idx_fg_causal_name_uuid ON block_changes(causal_player_uuid,causal_player_uuid)",
                    "CREATE INDEX idx_fg_active_jobs ON rollback_jobs(world_uuid,status)",
                    "CREATE INDEX idx_fg_pending_changes ON rollback_job_changes(job_id,processed,sequence)",
                    "CREATE INDEX idx_fg_undo_changes ON rollback_job_changes(job_id,undone,sequence)",
                    "CREATE INDEX idx_fg_job_chunk_overlap ON rollback_job_chunks(world_uuid,chunk_x,chunk_z,job_id)"
            )) {
                s.execute(index.replace("idx_fg_causal_name_uuid ON block_changes(causal_player_uuid,causal_player_uuid)",
                        "idx_fg_causal_name_uuid ON block_changes(causal_player_uuid,causal_player_uuid)"));
            }
            // Tests create the real name-index columns; real schema includes
            // the extra name field, so keep the fixture equivalent.
            s.execute("INSERT INTO rollback_jobs VALUES(1,1000,1000,'actor','uuid','COMPLETED',1,1,1,0,'RADIUS',1000)");
            s.execute("INSERT INTO rollback_job_changes VALUES(1,0,'secret-world','uuid',"
                    + "'minecraft:diamond_block','minecraft:air','minecraft:diamond_block',"
                    + "X'010203',X'040506',X'070809','minecraft:air',X'0A0B0C',1,1,0,0,NULL,0)");
            s.execute("INSERT INTO rollback_job_chunks VALUES(1,'uuid',0,0)");
        }
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            c.setAutoCommit(false);
            try (PreparedStatement ins = c.prepareStatement("""
                    INSERT INTO block_changes (happened_at,actor_uuid,actor_name,world,world_uuid,
                    x,y,z,chunk_x,chunk_z,action,before_data,after_data,before_entity_data,
                    after_entity_data,rollback_pending,causal_player_uuid)
                    VALUES(1000,'hidden-private-player','hidden-private-player','secret-world',
                    'uuid',?,64,0,?,0,'PLACE','minecraft:diamond_block','minecraft:air',?,NULL,0,NULL)
                    """)) {
                byte[] privateInventory = new byte[128];
                java.util.Arrays.fill(privateInventory, (byte) 42);
                for (int i = 0; i < history; i++) {
                    ins.setInt(1, i);
                    ins.setInt(2, i / 16);
                    ins.setBytes(3, privateInventory);
                    ins.addBatch();
                    if (i % 500 == 499) ins.executeBatch();
                }
                ins.executeBatch();
            }
            c.commit();
        }
        return db;
    }
}
