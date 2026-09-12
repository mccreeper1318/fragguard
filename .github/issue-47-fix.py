from pathlib import Path


def replace_once(path, old, new):
    file_path = Path(path)
    text = file_path.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one replacement target, found {count}")
    file_path.write_text(text.replace(old, new, 1))


replace_once(
    "src/main/java/org/pinnaclesmp/fragguard/Database.java",
    '''    CompletableFuture<Integer> cleanupOldRecordsAsync(int retentionDays) {
        return submit(databaseConnection -> {
            long cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(retentionDays);
            try (PreparedStatement statement = databaseConnection.prepareStatement(
                    "DELETE FROM block_changes WHERE happened_at < ? AND rollback_pending = 0")) {
                statement.setLong(1, cutoff);
                return statement.executeUpdate();
            }
        }, false);
    }
''',
    '''    CompletableFuture<DatabaseCleanupResult> cleanupOldRecordsAsync(
            int retentionDays,
            int rollbackJobRetentionDays
    ) {
        return submit(databaseConnection -> inTransaction(databaseConnection, () -> {
            long now = System.currentTimeMillis();
            long blockCutoff = now - TimeUnit.DAYS.toMillis(Math.max(1, retentionDays));
            long rollbackJobCutoff = now - TimeUnit.DAYS.toMillis(Math.max(1, rollbackJobRetentionDays));

            int deletedBlockRecords;
            try (PreparedStatement statement = databaseConnection.prepareStatement(
                    "DELETE FROM block_changes WHERE happened_at < ? AND rollback_pending = 0")) {
                statement.setLong(1, blockCutoff);
                deletedBlockRecords = statement.executeUpdate();
            }

            String terminalJobCondition = """
                    updated_at < ?
                    AND (
                        status IN ('COMPLETED', 'UNDONE')
                        OR (
                            status = 'FAILED'
                            AND NOT EXISTS (
                                SELECT 1
                                FROM rollback_job_changes
                                WHERE job_id = rollback_jobs.id
                                  AND before_data IS NOT NULL
                                  AND conflicted = 0
                                  AND undone = 0
                            )
                        )
                    )
                    """;

            try (PreparedStatement statement = databaseConnection.prepareStatement("""
                    DELETE FROM block_changes
                    WHERE rollback_pending = 1
                      AND id IN (
                          SELECT pending_audit_id
                          FROM rollback_job_changes
                          WHERE pending_audit_id IS NOT NULL
                            AND job_id IN (
                                SELECT id
                                FROM rollback_jobs
                                WHERE %s
                            )
                      )
                    """.formatted(terminalJobCondition))) {
                statement.setLong(1, rollbackJobCutoff);
                statement.executeUpdate();
            }

            int deletedRollbackJobs;
            try (PreparedStatement statement = databaseConnection.prepareStatement(
                    "DELETE FROM rollback_jobs WHERE " + terminalJobCondition)) {
                statement.setLong(1, rollbackJobCutoff);
                deletedRollbackJobs = statement.executeUpdate();
            }

            return new DatabaseCleanupResult(deletedBlockRecords, deletedRollbackJobs);
        }), false);
    }
'''
)

replace_once(
    "src/main/java/org/pinnaclesmp/fragguard/FragGuardPlugin.java",
    '''    int getRetentionDays() {
        return Math.max(1, getConfig().getInt("retention-days", 30));
    }

    private void scheduleCleanup() {
        int intervalMinutes = Math.max(1, getConfig().getInt("cleanup-interval-minutes", 60));
        long ticks = TimeUnit.MINUTES.toSeconds(intervalMinutes) * 20L;

        Runnable cleanup = () -> database.cleanupOldRecordsAsync(getRetentionDays()).thenAccept(deleted -> {
            if (deleted > 0) {
                getLogger().info("Deleted " + deleted + " old block log records.");
            }
        });

        Bukkit.getScheduler().runTaskTimerAsynchronously(this, cleanup, 20L * 30L, ticks);
    }
''',
    '''    int getRetentionDays() {
        return Math.max(1, getConfig().getInt("retention-days", 30));
    }

    int getRollbackJobRetentionDays() {
        return Math.max(1, getConfig().getInt("rollback-job-retention-days", getRetentionDays()));
    }

    private void scheduleCleanup() {
        int intervalMinutes = Math.max(1, getConfig().getInt("cleanup-interval-minutes", 60));
        long ticks = TimeUnit.MINUTES.toSeconds(intervalMinutes) * 20L;

        Runnable cleanup = () -> database.cleanupOldRecordsAsync(
                getRetentionDays(),
                getRollbackJobRetentionDays()
        ).whenComplete((deleted, throwable) -> {
            if (throwable != null) {
                getLogger().log(Level.WARNING, "FragGuard retention cleanup failed.", throwable);
                return;
            }
            if (deleted.blockRecordsDeleted() > 0) {
                getLogger().info("Deleted " + deleted.blockRecordsDeleted() + " old block log records.");
            }
            if (deleted.rollbackJobsDeleted() > 0) {
                getLogger().info("Deleted " + deleted.rollbackJobsDeleted()
                        + " expired rollback job(s) and their saved snapshots.");
            }
        });

        Bukkit.getScheduler().runTaskTimerAsynchronously(this, cleanup, 20L * 30L, ticks);
    }
'''
)

replace_once(
    "src/main/resources/config.yml",
    '''# How often old records are removed from SQLite.
cleanup-interval-minutes: 60

# Bound memory usage while SQLite writes are grouped into transactions.
''',
    '''# How often old records are removed from SQLite.
cleanup-interval-minutes: 60

# How long completed/undone rollback jobs remain available before their saved snapshots are deleted.
# Recoverable failed jobs are kept so /fg undo can still finish them.
rollback-job-retention-days: 30

# Bound memory usage while SQLite writes are grouped into transactions.
'''
)

replace_once(
    "README.md",
    '''Rollback and undo progress is stored in SQLite. Interrupted jobs automatically resume after a server restart, and overlapping jobs in the same world are rejected. Changes are processed in consecutive same-chunk batches without changing saved sequence order, existing chunks load asynchronously without generating terrain, and temporary chunk tickets prevent an active chunk from unloading during audit persistence. Main-thread work respects both a per-tick time budget and block cap, and pauses automatically while server TPS is below the configured minimum.
''',
    '''Rollback and undo progress is stored in SQLite. Interrupted jobs automatically resume after a server restart, and overlapping jobs in the same world are rejected. Completed rollback jobs remain available for `/fg undo` until `rollback-job-retention-days`; expired completed/undone and permanently failed jobs are deleted with their saved snapshots, while active and recoverable failed jobs are retained. Changes are processed in consecutive same-chunk batches without changing saved sequence order, existing chunks load asynchronously without generating terrain, and temporary chunk tickets prevent an active chunk from unloading during audit persistence. Main-thread work respects both a per-tick time budget and block cap, and pauses automatically while server TPS is below the configured minimum.
'''
)

replace_once(
    "README.md",
    '''retention-days: 30
cleanup-interval-minutes: 60
database-write-queue-capacity: 20000
''',
    '''retention-days: 30
cleanup-interval-minutes: 60
rollback-job-retention-days: 30
database-write-queue-capacity: 20000
'''
)

replace_once(
    "changelog.md",
    '''- Fixed dragon egg teleports being partially logged by recording the source and destination together under a dedicated teleport action.

## 26.2-1.1.2
''',
    '''- Fixed dragon egg teleports being partially logged by recording the source and destination together under a dedicated teleport action.
- Fixed completed rollback jobs and snapshots accumulating indefinitely by expiring terminal jobs after the configured retention while preserving active and recoverable failed jobs.

## 26.2-1.1.2
'''
)

Path("src/main/java/org/pinnaclesmp/fragguard/DatabaseCleanupResult.java").write_text('''package org.pinnaclesmp.fragguard;

record DatabaseCleanupResult(
        int blockRecordsDeleted,
        int rollbackJobsDeleted
) {
}
''')

Path("src/test/java/org/pinnaclesmp/fragguard/RollbackJobRetentionTest.java").write_text('''package org.pinnaclesmp.fragguard;

import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RollbackJobRetentionTest {
    private static final UUID WORLD_UUID = UUID.fromString("21d42c5c-a0eb-447d-ae7e-810149eb424e");
    private static final UUID ACTOR_UUID = UUID.fromString("3068f82f-f736-45ed-b27c-e49f2c08ba14");

    @TempDir
    Path temporaryDirectory;

    private Database database;

    @AfterEach
    void shutDownDatabase() {
        if (database != null) {
            database.shutdown();
        }
    }

    @Test
    void cleanupExpiresOnlyTerminalRollbackJobsAndCascadesTheirSnapshots() throws Exception {
        database = startDatabase();
        long now = System.currentTimeMillis();
        long old = now - TimeUnit.DAYS.toMillis(10);

        database.insertRequiredAsync(List.of(new BlockChange(
                now - TimeUnit.DAYS.toMillis(5),
                ACTOR_UUID.toString(),
                "Builder",
                "world",
                100,
                64,
                100,
                ChangeAction.BREAK,
                "minecraft:stone",
                "minecraft:air"
        ))).join();

        long completedOld;
        long undoneOld;
        long failedPermanentOld;
        long failedRecoverableOld;
        long runningOld;
        long undoingOld;
        long completedRecent;

        try (Connection connection = openDatabase()) {
            connection.createStatement().execute("PRAGMA foreign_keys=ON");
            completedOld = insertJob(connection, "COMPLETED", old, true, false, false, 1);
            undoneOld = insertJob(connection, "UNDONE", old, true, false, true, 2);
            failedPermanentOld = insertJob(connection, "FAILED", old, true, true, false, 3);
            failedRecoverableOld = insertJob(connection, "FAILED", old, true, false, false, 4);
            runningOld = insertJob(connection, "RUNNING", old, false, false, false, 5);
            undoingOld = insertJob(connection, "UNDOING", old, true, false, false, 6);
            completedRecent = insertJob(connection, "COMPLETED", now, true, false, false, 7);
        }

        DatabaseCleanupResult deleted = database.cleanupOldRecordsAsync(1, 7).join();

        assertEquals(1, deleted.blockRecordsDeleted());
        assertEquals(3, deleted.rollbackJobsDeleted());

        try (Connection connection = openDatabase()) {
            Set<Long> remaining = jobIds(connection);

            assertFalse(remaining.contains(completedOld));
            assertFalse(remaining.contains(undoneOld));
            assertFalse(remaining.contains(failedPermanentOld));

            assertTrue(remaining.contains(failedRecoverableOld),
                    "failed jobs with an undoable saved state must remain recoverable");
            assertTrue(remaining.contains(runningOld), "running jobs must never be removed by retention cleanup");
            assertTrue(remaining.contains(undoingOld), "undoing jobs must never be removed by retention cleanup");
            assertTrue(remaining.contains(completedRecent), "terminal jobs newer than the retention cutoff must remain");

            assertEquals(0, changeRows(connection, completedOld));
            assertEquals(0, changeRows(connection, undoneOld));
            assertEquals(0, changeRows(connection, failedPermanentOld));
            assertEquals(1, changeRows(connection, failedRecoverableOld));
            assertEquals(1, changeRows(connection, runningOld));
            assertEquals(1, changeRows(connection, undoingOld));
            assertEquals(1, changeRows(connection, completedRecent));
        }
    }

    private long insertJob(
            Connection connection,
            String status,
            long updatedAt,
            boolean hasBeforeState,
            boolean conflicted,
            boolean undone,
            int x
    ) throws Exception {
        long jobId;
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO rollback_jobs
                (created_at, updated_at, actor_uuid, actor_name, world_uuid, world,
                 center_x, center_z, radius, target_timestamp, snapshot_timestamp, force,
                 status, total_blocks)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, 1)
                """, Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, updatedAt);
            statement.setLong(2, updatedAt);
            statement.setString(3, ACTOR_UUID.toString());
            statement.setString(4, "Builder");
            statement.setString(5, WORLD_UUID.toString());
            statement.setString(6, "world");
            statement.setInt(7, x);
            statement.setInt(8, 0);
            statement.setInt(9, 1);
            statement.setLong(10, updatedAt - 1_000L);
            statement.setLong(11, updatedAt);
            statement.setString(12, status);
            statement.executeUpdate();

            try (ResultSet keys = statement.getGeneratedKeys()) {
                assertTrue(keys.next());
                jobId = keys.getLong(1);
            }
        }

        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO rollback_job_changes
                (job_id, sequence, world, x, y, z, before_data, target_data, expected_data,
                 processed, applied, conflicted, undone, before_entity_data)
                VALUES (?, 0, 'world', ?, 64, 0, ?, 'minecraft:dirt', 'minecraft:stone',
                        1, 1, ?, ?, ?)
                """)) {
            statement.setLong(1, jobId);
            statement.setInt(2, x);
            statement.setString(3, hasBeforeState ? "minecraft:stone" : null);
            statement.setInt(4, conflicted ? 1 : 0);
            statement.setInt(5, undone ? 1 : 0);
            statement.setBytes(6, new byte[]{1, 2, 3, (byte) x});
            statement.executeUpdate();
        }
        return jobId;
    }

    private Set<Long> jobIds(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT id FROM rollback_jobs")) {
            Set<Long> ids = new HashSet<>();
            while (rows.next()) {
                ids.add(rows.getLong(1));
            }
            return ids;
        }
    }

    private int changeRows(Connection connection, long jobId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM rollback_job_changes WHERE job_id = ?")) {
            statement.setLong(1, jobId);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getInt(1);
            }
        }
    }

    private Database startDatabase() throws Exception {
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        World world = mock(World.class);
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("database-write-queue-capacity", 64);
        configuration.set("database-write-batch-size", 16);
        configuration.set("database-query-timeout-seconds", 5);
        when(plugin.getDataFolder()).thenReturn(temporaryDirectory.toFile());
        when(plugin.getConfig()).thenReturn(configuration);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("RollbackJobRetentionTest"));
        when(server.getCurrentTick()).thenReturn(900);
        when(server.getWorld("world")).thenReturn(world);
        when(server.getWorlds()).thenReturn(List.of(world));
        when(world.getUID()).thenReturn(WORLD_UUID);
        when(world.getName()).thenReturn("world");

        Database instance = new Database(plugin);
        instance.init();
        return instance;
    }

    private Connection openDatabase() throws Exception {
        Class.forName("org.sqlite.JDBC");
        return DriverManager.getConnection("jdbc:sqlite:" + temporaryDirectory.resolve("fragguard.db"));
    }
}
''')
