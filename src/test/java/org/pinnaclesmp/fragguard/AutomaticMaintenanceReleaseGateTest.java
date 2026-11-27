package org.pinnaclesmp.fragguard;

import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * #208: executable, host-independent DEFAULT automatic STRICT release path.
 *
 * Unlike #172's manual worker sequence, this calls the real #207 controller
 * once and never explicitly calls its compact/verify/approve workers.
 * The actual SQLite Database write admission and freeze worker are used.
 *
 * It still cannot simulate Paper's command dispatcher, player-world events,
 * a hosting panel's 40-second stop grace, real quota, TPS or hardware failure.
 * Those are separate, UNCHECKED owner release gates. All data is synthetic
 * and stored only under JUnit's disposable @TempDir.
 */
@Tag("maintenance-release")
class AutomaticMaintenanceReleaseGateTest {
    private static final UUID OPERATOR =
            UUID.fromString("d68b7540-3ba6-4778-86df-13b1039df2ec");
    private static final UUID WORLD_UUID =
            UUID.fromString("1aed5320-f817-4dcf-a777-e5032b887b42");
    private static final UUID ACTOR_UUID =
            UUID.fromString("c13a1393-52fc-461e-a313-c3e58d8ab694");
    @TempDir Path root;

    @Test
    void oneCommandFrozenSqliteStrictFullVerificationAutomaticRestartAndNewLogging()
            throws Exception {
        long started = System.nanoTime();
        final int historyRows = 120;
        Path original = DatabaseDeepDiagnosticsTest.seededDatabase(root, historyRows);
        Database writer = startWriter();
        String frozenOperationId;
        Path backup;
        long initialRows;
        long frozenRows;
        byte[] originalEntityBlob;
        try {
            initialRows = count(original, "block_changes");
            assertEquals(historyRows, initialRows);
            try (Connection source = sql(original);
                 Statement st = source.createStatement();
                 ResultSet blob = st.executeQuery(
                         "SELECT before_entity_data FROM block_changes WHERE id=1")) {
                assertTrue(blob.next());
                originalEntityBlob = blob.getBytes(1);
                assertEquals(128, originalEntityBlob.length);
            }

            try (MaintenanceCoordinator state = MaintenanceCoordinator.open(root);
                 CompactionStoragePreflight storage = new CompactionStoragePreflight(root);
                 OnlineDatabaseCompaction vacuum = new OnlineDatabaseCompaction(root, state, storage);
                 CompactionCandidateVerifier verifier = new CompactionCandidateVerifier(root, state);
                 CompactionReplacementPolicy policy = new CompactionReplacementPolicy(root, state)) {
                CompletableFuture<String> gracefulStopRequested = new CompletableFuture<>();
                try (AutomaticMaintenanceWorkflow workflow = new AutomaticMaintenanceWorkflow(
                        root, state, storage, vacuum, verifier, policy,
                        () -> new CompactionStoragePreflight.Evidence(null, null, 256, false),
                        () -> 30,
                        () -> writer.maintenanceFullyFrozen()
                                ? false : !writer.maintenanceWritesPaused(),
                        Runnable::run,
                        (id, mode) -> gracefulStopRequested.complete(id + "/" + mode),
                        ignored -> { })) {

                    var risk = workflow.start(OPERATOR, ignored -> { }, false)
                            .get(30, TimeUnit.SECONDS);
                    assertEquals(AutomaticMaintenanceWorkflow.ResultType.NEEDS_CAPACITY_ACK,
                            risk.type());
                    assertEquals(MaintenancePhase.IDLE, state.snapshot().phase(),
                            "Consent must be collected BEFORE any countdown");
                    // Explicitly acknowledge UNKNOWN capacity; never fake known host quota.
                    var countdown = workflow.start(OPERATOR, ignored -> { }, true)
                            .get(30, TimeUnit.SECONDS);
                    assertEquals(AutomaticMaintenanceWorkflow.ResultType.COUNTDOWN_STARTED,
                            countdown.type());
                    assertEquals(MaintenancePhase.COUNTDOWN, state.snapshot().phase());
                    frozenOperationId = countdown.operationId();

                    // The countdown must still accept live block-history writes.
                    long now = System.currentTimeMillis();
                    writer.insertRequiredAsync(List.of(change(now, 220, 900)))
                            .get(30, TimeUnit.SECONDS);
                    assertEquals(initialRows + 1, count(original, "block_changes"));

                    // The SAME real SQLite worker provides the drained/query_only seal.
                    writer.pauseBackgroundMutationsForMaintenanceAsync()
                            .get(30, TimeUnit.SECONDS);
                    assertFalse(writer.maintenanceWritesPaused(),
                            "Countdown/background drain must not suppress block logging");
                    writer.insertRequiredAsync(List.of(change(now + 1, 221, 901)))
                            .get(30, TimeUnit.SECONDS);
                    writer.freezeForMaintenanceAsync().get(30, TimeUnit.SECONDS);
                    assertTrue(writer.maintenanceFullyFrozen());
                    assertEquals(0L, writer.health().droppedWrites());
                    assertEquals(0L, writer.health().queuedWrites());
                    assertEquals(0L, writer.inFlightWrites());
                    assertTrue(writer.loggingPausedAtMillis() > 0);
                    frozenRows = count(original, "block_changes");
                    assertEquals(initialRows + 2, frozenRows);
                    assertTrue(writer.cleanupOldRecordsAsync(30, 30).get(30, TimeUnit.SECONDS)
                            .pausedForMaintenance());
                    writer.insertAsync(change(now + 2, 222, 902));
                    assertEquals(1L, writer.intentionallyUnloggedDuringMaintenance());
                    assertEquals(0L, writer.health().droppedWrites(),
                            "Intentional paused logging is NOT a lost-storage-write warning");
                    assertThrows(Exception.class, () -> writer
                            .insertRequiredAsync(List.of(change(now + 3, 223, 903))).join());
                    assertEquals(frozenRows, count(original, "block_changes"),
                            "No plugin SQL change may pass the verified SQLite freeze");

                    FrozenMaintenanceProof.persist(root, state.snapshot(), writer);
                    MaintenanceSnapshot active = state.activate().get(30, TimeUnit.SECONDS);
                    assertEquals(MaintenancePhase.ACTIVE, active.phase());
                    workflow.onActivated(active); // Reuses real asynchronous workers.
                    assertEquals(frozenOperationId + "/STRICT",
                            gracefulStopRequested.get(180, TimeUnit.SECONDS),
                            "Full compaction, scan, and STRICT online precheck must finish "
                                    + "before the workflow requests any Paper stop");
                    assertEquals(MaintenancePhase.VERIFYING, state.snapshot().phase());
                    assertTrue(Files.isRegularFile(root.resolve("compaction-candidates")
                            .resolve(state.snapshot().candidateFile())));
                    assertFalse(Files.exists(root.resolve("compaction-candidates")
                            .resolve(state.snapshot().candidateFile()
                                    .replace(".db", ".shutdown.properties"))),
                            "No shutdown receipt is authorized merely by passing verification");
                    assertEquals(frozenRows, count(original, "block_changes"));
                    assertTrue(policy.validateBeforeStoppingWriter()
                            .get(30, TimeUnit.SECONDS));

                    // The synthetic writer stops cleanly; production Paper
                    // shutdown/host kill is NOT simulated by this assertion.
                    writer.shutdown();
                    assertTrue(writer.workerStopped());
                    assertTrue(writer.walCheckpointCompleted());
                    var approved = policy.finalizeStrictAfterShutdown(true, true, true)
                            .get(30, TimeUnit.SECONDS);
                    assertTrue(approved.approved());
                    assertEquals(MaintenancePhase.READY_TO_SWAP, state.snapshot().phase());
                    var receipt = CompactionActivation.recordCleanShutdown(
                            root, state.snapshot(), true, policy.finalizedStrictProof());
                    assertTrue(Files.isRegularFile(receipt.path()));
                    backup = receipt.backup();
                    assertFalse(Files.exists(backup),
                            "Original backup must be installed only at next safe startup");
                }
            }
            try (MaintenanceCoordinator restarted = MaintenanceCoordinator.open(root)) {
                MaintenanceSnapshot ready = restarted.snapshot();
                assertEquals(MaintenancePhase.READY_TO_SWAP, ready.phase());
                assertEquals(frozenOperationId, ready.operationId());
                AutomaticCompactionActivation.activateBeforeDatabaseOpen(root, ready);
                assertEquals(frozenRows,
                        CompactionActivation.verifyInstalled(root, ready).verifiedRows() - 3,
                        "Complete rollback, undo and chunk rows must survive startup");
                AutomaticCompactionActivation.recordVerifiedBeforeDatabaseOpen(root, ready);
                restarted.beginActivation().get(30, TimeUnit.SECONDS);
                assertEquals(MaintenancePhase.ACTIVATING, restarted.snapshot().phase());

                // Isolated logical SQL validation. A real verified Paper
                // Database.init() and join-gate clearance require owner-host testing.
                try (Connection check = sql(original);
                     Statement st = check.createStatement()) {
                    try (ResultSet integrity = st.executeQuery("PRAGMA integrity_check(1)")) {
                        assertTrue(integrity.next());
                        assertEquals("ok", integrity.getString(1));
                    }
                    assertEquals(frozenRows, scalar(st,
                            "SELECT COUNT(*) FROM block_changes"));
                    assertEquals(1L, scalar(st,
                            "SELECT COUNT(*) FROM rollback_job_changes"));
                    assertEquals(1L, scalar(st,
                            "SELECT COUNT(*) FROM rollback_jobs"));
                    assertEquals(1L, scalar(st,
                            "SELECT COUNT(*) FROM rollback_job_chunks"));
                    try (ResultSet entity = st.executeQuery(
                            "SELECT before_entity_data FROM block_changes WHERE id=1")) {
                        assertTrue(entity.next());
                        assertArrayEquals(originalEntityBlob, entity.getBytes(1));
                    }
                }
                AutomaticCompactionActivation.recordSuccessfulWriterStartup(
                        root, restarted.snapshot());
                restarted.finishVerifiedActivation().get(30, TimeUnit.SECONDS);
                assertEquals(MaintenancePhase.IDLE, restarted.snapshot().phase());
                AutomaticCompactionActivation.archiveCompletedEvidenceIfPresent(root);
                assertTrue(Files.isRegularFile(backup), "Original must remain recoverable");
                assertFalse(Files.exists(root.resolve("compaction-candidates")),
                        "Completed metadata must not block the next maintenance attempt");
            }

            // Reopen the actual SQLite worker and prove logging is still
            // functional after the verified activation/copy and archive.
            Database restored = startWriter();
            try {
                restored.insertRequiredAsync(List.of(
                        change(System.currentTimeMillis(), 310, 904)))
                        .get(30, TimeUnit.SECONDS);
                assertFalse(restored.maintenanceWritesPaused());
                assertEquals(frozenRows + 1, count(original, "block_changes"));
                assertEquals(0L, restored.health().droppedWrites());
            } finally {
                restored.shutdown();
            }
            assertEquals(frozenRows + 1, count(original, "block_changes"));
            assertEquals(1L, count(original, "rollback_job_changes"));
            System.out.println("FG #208 isolated AUTO STRICT validation PASS:"
                    + " baselineHistory=" + initialRows
                    + ", freezeHistory=" + frozenRows
                    + ", restoredHistory=" + (frozenRows + 1)
                    + ", preservedBackupBytes=" + Files.size(backup)
                    + ", elapsedMs=" + TimeUnit.NANOSECONDS.toMillis(
                            System.nanoTime() - started)
                    + ", realPaperHost=NOT_TESTED"
                    + ", ownerLiveApproval=NOT_GRANTED");
        } finally {
            if (!writer.workerStopped()) writer.shutdown();
        }
    }

    private Database startWriter() throws Exception {
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        World world = mock(World.class);
        YamlConfiguration config = new YamlConfiguration();
        config.set("database-write-queue-capacity", 256);
        config.set("database-write-batch-size", 32);
        config.set("database-operation-queue-capacity", 256);
        config.set("database-query-timeout-seconds", 10);
        when(plugin.getDataFolder()).thenReturn(root.toFile());
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(
                Logger.getLogger("FG208-Isolated-Release-Gate"));
        when(server.getWorld("world")).thenReturn(world);
        when(server.getWorlds()).thenReturn(List.of(world));
        when(server.getCurrentTick()).thenReturn(225);
        when(world.getUID()).thenReturn(WORLD_UUID);
        when(world.getName()).thenReturn("world");
        Database database = new Database(plugin);
        database.init();
        return database;
    }

    private static BlockChange change(long now, long tick, int x) {
        return new BlockChange(now, tick, ACTOR_UUID.toString(),
                "ReleaseTest", "world", x, 64, 0, ChangeAction.BREAK,
                "minecraft:stone", "minecraft:air");
    }

    private static Connection sql(Path db) throws Exception {
        Class.forName("org.sqlite.JDBC");
        return DriverManager.getConnection("jdbc:sqlite:" + db);
    }

    private static long count(Path db, String table) throws Exception {
        try (Connection c = sql(db); Statement st = c.createStatement()) {
            return scalar(st, "SELECT COUNT(*) FROM " + table);
        }
    }

    private static long scalar(Statement st, String query) throws Exception {
        try (ResultSet rows = st.executeQuery(query)) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }
}
