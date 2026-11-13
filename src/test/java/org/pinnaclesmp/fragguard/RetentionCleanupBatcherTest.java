package org.pinnaclesmp.fragguard;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

class RetentionCleanupBatcherTest {
    @TempDir Path temporaryDirectory;

    @Test
    void deletesLargeBacklogInBoundedCommittedTransactionsPreservingPendingAndNewHistory() throws Exception {
        try (Connection db = database()) {
            try (PreparedStatement insert = db.prepareStatement(
                    "INSERT INTO block_changes(id,happened_at,rollback_pending) VALUES(?,?,?)")) {
                for (int i = 1; i <= 121; i++) {
                    insert.setInt(1, i);
                    insert.setLong(2, i > 110 ? 2000 : 100);
                    insert.setInt(3, i > 100 && i <= 110 ? 1 : 0);
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            int total = 0, commits = 0;
            while (true) {
                db.setAutoCommit(false);
                var step = RetentionCleanupBatcher.deleteExpiredHistory(db, 1000, 13);
                db.commit();
                db.setAutoCommit(true);
                commits++;
                assertTrue(step.blocksDeleted() <= 13, "one transaction must stay bounded");
                total += step.blocksDeleted();
                if (step.blocksDeleted() == 0) break;
                if (commits == 1) {
                    // Changes committed while cleanup is in progress must be
                    // ordered and preserved according to their timestamp.
                    try (Statement insert = db.createStatement()) {
                        insert.executeUpdate("INSERT INTO block_changes VALUES(122,2000,0)");
                    }
                }
                assertTrue(commits <= 12);
            }
            assertEquals(100, total);
            assertTrue(commits >= 9);
            assertEquals(22, scalar(db, "SELECT COUNT(*) FROM block_changes"));
            assertEquals(10, scalar(db,
                    "SELECT COUNT(*) FROM block_changes WHERE rollback_pending = 1"));
            assertEquals(12, scalar(db,
                    "SELECT COUNT(*) FROM block_changes WHERE happened_at = 2000"));
        }
    }

    @Test
    void terminalJobCleanupKeepsAuditLinksAlignedAndProtectsRecoverableUndoJobs() throws Exception {
        try (Connection db = database()) {
            try (Statement s = db.createStatement()) {
                s.execute("INSERT INTO rollback_jobs VALUES(1,100,'COMPLETED')");
                s.execute("INSERT INTO rollback_jobs VALUES(2,100,'FAILED')");
                s.execute("INSERT INTO rollback_jobs VALUES(3,100,'RUNNING')");
                s.execute("INSERT INTO rollback_jobs VALUES(4,2000,'COMPLETED')");
            }
            try (PreparedStatement saved = db.prepareStatement("""
                    INSERT INTO rollback_job_changes
                    (job_id,sequence,before_data,conflicted,undone,pending_audit_id)
                    VALUES(?,?,?,?,?,?)
                    """);
                 PreparedStatement audits = db.prepareStatement(
                         "INSERT INTO block_changes VALUES(?,?,?)");
                 PreparedStatement chunks = db.prepareStatement(
                         "INSERT INTO rollback_job_chunks VALUES(?,?)")) {
                for (int i = 1; i <= 51; i++) {
                    saved.setInt(1, 1);
                    saved.setInt(2, i);
                    saved.setBytes(3, new byte[]{1, 2, 3});
                    saved.setInt(4, 0);
                    saved.setInt(5, 0);
                    if (i % 2 == 0) {
                        saved.setInt(6, i);
                        audits.setInt(1, i);
                        audits.setLong(2, 100);
                        audits.setInt(3, 1);
                        audits.addBatch();
                    } else saved.setNull(6, java.sql.Types.INTEGER);
                    saved.addBatch();
                    chunks.setInt(1, 1);
                    chunks.setInt(2, i);
                    chunks.addBatch();
                }
                for (int job = 2; job <= 4; job++) {
                    saved.setInt(1, job);
                    saved.setInt(2, 1);
                    saved.setBytes(3, new byte[]{9});
                    saved.setInt(4, 0);
                    saved.setInt(5, 0);
                    saved.setNull(6, java.sql.Types.INTEGER);
                    saved.addBatch();
                }
                saved.executeBatch();
                audits.executeBatch();
                chunks.executeBatch();
            }
            int batches = 0;
            int deletedJobs = 0;
            int deletedAudits = 0;
            while (true) {
                db.setAutoCommit(false);
                var step = RetentionCleanupBatcher.deleteExpiredJobStep(db, 1000, 7);
                db.commit();
                db.setAutoCommit(true);
                if (!step.selectedWork()) break;
                batches++;
                deletedJobs += step.jobsDeleted();
                deletedAudits += step.blocksDeleted();
                assertTrue(step.blocksDeleted() <= 7);
                assertTrue(step.jobsDeleted() <= 1);
                if (batches == 1) {
                    assertEquals(1, scalar(db,
                            "SELECT COUNT(*) FROM rollback_jobs "
                            + "WHERE id=1 AND status='PURGING'"),
                            "first destructive batch must durably revoke undo eligibility");
                }
                assertEquals(0, scalar(db, """
                        SELECT COUNT(*) FROM block_changes bc
                        WHERE bc.rollback_pending = 1 AND NOT EXISTS (
                            SELECT 1 FROM rollback_job_changes j
                            WHERE j.pending_audit_id = bc.id
                        )
                        """), "a batch must not orphan live pending audit rows");
                assertTrue(batches < 30);
            }
            assertTrue(batches >= 8);
            assertEquals(1, deletedJobs, "only the expired terminal job is eligible");
            assertEquals(25, deletedAudits);
            assertEquals(0, scalar(db, "SELECT COUNT(*) FROM rollback_job_changes WHERE job_id=1"));
            assertEquals(0, scalar(db, "SELECT COUNT(*) FROM rollback_job_chunks WHERE job_id=1"));
            assertEquals(0, scalar(db, "SELECT COUNT(*) FROM block_changes WHERE rollback_pending=1"));
            assertEquals(3, scalar(db, "SELECT COUNT(*) FROM rollback_jobs"));
            assertEquals(3, scalar(db, "SELECT COUNT(*) FROM rollback_job_changes"),
                    "recoverable failed, active and newer jobs retain their undo snapshots");
        }
    }

    @Test
    void aLaterFailedBatchRollsBackOnlyItAndKeepsEarlierCommittedDeletes() throws Exception {
        try (Connection db = database()) {
            try (Statement s = db.createStatement()) {
                for (int i = 1; i <= 12; i++) {
                    s.execute("INSERT INTO block_changes VALUES(" + i + ",100,0)");
                }
                s.execute("""
                        CREATE TRIGGER reject_batch
                        BEFORE DELETE ON block_changes WHEN OLD.id = 7
                        BEGIN SELECT RAISE(ABORT, 'fail this batch'); END
                        """);
            }
            db.setAutoCommit(false);
            var first = RetentionCleanupBatcher.deleteExpiredHistory(db, 1000, 5);
            db.commit();
            db.setAutoCommit(true);
            assertEquals(5, first.blocksDeleted());
            db.setAutoCommit(false);
            assertThrows(SQLException.class,
                    () -> RetentionCleanupBatcher.deleteExpiredHistory(db, 1000, 5));
            db.rollback();
            db.setAutoCommit(true);
            assertEquals(7, scalar(db, "SELECT COUNT(*) FROM block_changes"),
                    "the first committed transaction survives the later failure");
            assertEquals(1, scalar(db, "SELECT COUNT(*) FROM block_changes WHERE id=6"),
                    "a failed transaction must not partly remove its slice");
        }
    }

    @Test
    void rejectsUnsafeBatchSizes() throws Exception {
        try (Connection db = database()) {
            assertThrows(IllegalArgumentException.class,
                    () -> RetentionCleanupBatcher.deleteExpiredHistory(db, 1000, 0));
            assertThrows(IllegalArgumentException.class,
                    () -> RetentionCleanupBatcher.deleteExpiredJobStep(db, 1000, 5001));
        }
    }

    private Connection database() throws Exception {
        Connection db = DriverManager.getConnection(
                "jdbc:sqlite:" + temporaryDirectory.resolve("fragguard.db"));
        try (Statement s = db.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("PRAGMA foreign_keys=ON");
            s.execute("""
                    CREATE TABLE block_changes (
                        id INTEGER PRIMARY KEY, happened_at INTEGER NOT NULL,
                        rollback_pending INTEGER NOT NULL DEFAULT 0
                    )
                    """);
            s.execute("CREATE INDEX idx_fg_time ON block_changes(happened_at)");
            s.execute("""
                    CREATE TABLE rollback_jobs (
                        id INTEGER PRIMARY KEY, updated_at INTEGER NOT NULL, status TEXT NOT NULL
                    )
                    """);
            s.execute("""
                    CREATE TABLE rollback_job_changes (
                        job_id INTEGER NOT NULL, sequence INTEGER NOT NULL,
                        before_data BLOB, conflicted INTEGER, undone INTEGER,
                        pending_audit_id INTEGER,
                        PRIMARY KEY(job_id,sequence),
                        FOREIGN KEY(job_id) REFERENCES rollback_jobs(id) ON DELETE CASCADE
                    )
                    """);
            s.execute("""
                    CREATE TABLE rollback_job_chunks (
                        job_id INTEGER NOT NULL, chunk_x INTEGER NOT NULL,
                        PRIMARY KEY(job_id, chunk_x),
                        FOREIGN KEY(job_id) REFERENCES rollback_jobs(id) ON DELETE CASCADE
                    )
                    """);
        }
        return db;
    }

    private static long scalar(Connection db, String sql) throws SQLException {
        try (Statement s = db.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getLong(1);
        }
    }
}
