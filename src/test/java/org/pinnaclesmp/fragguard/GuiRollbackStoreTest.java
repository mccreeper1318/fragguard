package org.pinnaclesmp.fragguard;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GuiRollbackStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void returnsEveryUndoableJobAndExcludesUndoneOrNoOpJobs() throws Exception {
        Class.forName("org.sqlite.JDBC");
        String jdbcUrl = "jdbc:sqlite:" + temporaryDirectory.resolve("fragguard.db");
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE rollback_jobs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        created_at INTEGER NOT NULL,
                        actor_name TEXT NOT NULL,
                        world TEXT NOT NULL,
                        radius INTEGER NOT NULL,
                        target_timestamp INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        total_blocks INTEGER NOT NULL,
                        applied_blocks INTEGER NOT NULL,
                        conflict_blocks INTEGER NOT NULL,
                        last_error TEXT NOT NULL DEFAULT ''
                    )
                    """);
            statement.execute("""
                    INSERT INTO rollback_jobs
                    (created_at, actor_name, world, radius, target_timestamp, status,
                     total_blocks, applied_blocks, conflict_blocks, last_error)
                    VALUES
                    (1000, 'Alice', 'world', 15, 500, 'COMPLETED', 5, 4, 1, ''),
                    (2000, 'Bob', 'world_nether', 30, 750, 'FAILED', 3, 2, 0, 'partial failure'),
                    (3000, 'Alice', 'world', 15, 900, 'UNDONE', 5, 4, 1, ''),
                    (4000, 'Carol', 'world', 10, 950, 'COMPLETED', 2, 0, 2, '')
                    """);
        }

        GuiRollbackStore store = new GuiRollbackStore(temporaryDirectory.toFile(), 5);
        List<GuiRollbackJob> jobs = store.loadUndoableJobsAsync().join();

        assertEquals(List.of(2L, 1L), jobs.stream().map(GuiRollbackJob::id).toList());
        assertEquals("FAILED", jobs.getFirst().status());
        assertEquals(2, jobs.getFirst().appliedBlocks());
        assertEquals("partial failure", jobs.getFirst().lastError());
    }
}
