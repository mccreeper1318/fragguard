package org.pinnaclesmp.fragguard;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Bounded retention SQL, run on FragGuard's normal ordered database worker.
 * The caller MUST wrap each invocation in its own transaction and commit
 * before scheduling the next batch; no source database copies or VACUUM.
 */
final class RetentionCleanupBatcher {
    record Step(int blocksDeleted, int jobsDeleted, boolean selectedWork) { }

    private static final String ELIGIBLE_JOB = """
            status = 'PURGING'
            OR (updated_at < ?
                AND (status IN ('COMPLETED', 'UNDONE')
                     OR (status = 'FAILED' AND NOT EXISTS (
                         SELECT 1 FROM rollback_job_changes rc
                         WHERE rc.job_id = rollback_jobs.id
                           AND rc.before_data IS NOT NULL
                           AND rc.conflicted = 0 AND rc.undone = 0
                     ))))
            """;

    private RetentionCleanupBatcher() { }

    /**
     * Always use the existing happened_at index; never mutate pending
     * rollback audits, even when their timestamp has expired.
     */
    static Step deleteExpiredHistory(Connection db, long cutoff, int limit) throws SQLException {
        requireLimit(limit);
        try (PreparedStatement sql = db.prepareStatement("""
                DELETE FROM block_changes
                WHERE id IN (
                    SELECT id FROM block_changes
                    WHERE happened_at < ? AND rollback_pending = 0
                    ORDER BY happened_at, id LIMIT ?
                )
                """)) {
            sql.setLong(1, cutoff);
            sql.setInt(2, limit);
            int removed = sql.executeUpdate();
            return new Step(removed, 0, removed > 0);
        }
    }

    /**
     * Work on only ONE eligible terminal job per transaction, removing at
     * most `limit` audit rows, child snapshot rows, and chunk reservation
     * rows. Delete its parent only after all children are gone to prevent an
     * unbounded ON DELETE CASCADE transaction. The job's eligibility is
     * evaluated before any child snapshots are removed.
     */
    static Step deleteExpiredJobStep(Connection db, long cutoff, int limit) throws SQLException {
        requireLimit(limit);
        Long jobId = null;
        try (PreparedStatement select = db.prepareStatement(
                "SELECT id FROM rollback_jobs WHERE " + ELIGIBLE_JOB
                + " ORDER BY CASE WHEN status = 'PURGING' THEN 0 ELSE 1 END, "
                + "updated_at, id LIMIT 1")) {
            select.setLong(1, cutoff);
            try (ResultSet rows = select.executeQuery()) {
                if (rows.next()) jobId = rows.getLong(1);
            }
        }
        if (jobId == null) return new Step(0, 0, false);

        // P1 safety gate: permanently remove undo eligibility BEFORE the
        // first destructive slice commits. BEGIN UNDO only accepts COMPLETED
        // or FAILED; a PURGING job can never run with missing undo snapshots.
        // The status is persisted in the SAME transaction as the deletion,
        // and survives a batch-budget stop, SQLite error or server restart.
        // Do not update updated_at: ongoing PURGING jobs must be resumed even
        // after a process restart; they are always selected before new jobs.
        try (PreparedStatement marker = db.prepareStatement("""
                UPDATE rollback_jobs SET status = 'PURGING'
                WHERE id = ? AND (""" + ELIGIBLE_JOB + ")")) {
            marker.setLong(1, jobId);
            marker.setLong(2, cutoff);
            if (marker.executeUpdate() != 1) {
                throw new SQLException("Rollback job stopped being eligible for safe retention purging");
            }
        }

        // Delete audit rows linked to EXACTLY the same bounded sequence slice
        // we will remove from the job below. Never delete audit links from
        // later slices, or earlier audits could be orphaned.
        int deletedAudits;
        try (PreparedStatement audits = db.prepareStatement("""
                DELETE FROM block_changes
                WHERE rollback_pending = 1
                  AND id IN (
                      SELECT pending_audit_id FROM rollback_job_changes
                      WHERE job_id = ? AND pending_audit_id IS NOT NULL
                        AND sequence IN (
                            SELECT sequence FROM rollback_job_changes
                            WHERE job_id = ? ORDER BY sequence LIMIT ?
                        )
                  )
                """)) {
            audits.setLong(1, jobId);
            audits.setLong(2, jobId);
            audits.setInt(3, limit);
            deletedAudits = audits.executeUpdate();
        }
        try (PreparedStatement saved = db.prepareStatement("""
                DELETE FROM rollback_job_changes
                WHERE job_id = ? AND sequence IN (
                    SELECT sequence FROM rollback_job_changes
                    WHERE job_id = ? ORDER BY sequence LIMIT ?
                )
                """)) {
            saved.setLong(1, jobId);
            saved.setLong(2, jobId);
            saved.setInt(3, limit);
            saved.executeUpdate();
        }
        try (PreparedStatement chunks = db.prepareStatement("""
                DELETE FROM rollback_job_chunks
                WHERE rowid IN (
                    SELECT rowid FROM rollback_job_chunks
                    WHERE job_id = ? ORDER BY rowid LIMIT ?
                )
                """)) {
            chunks.setLong(1, jobId);
            chunks.setInt(2, limit);
            chunks.executeUpdate();
        }
        int removedJob;
        try (PreparedStatement parent = db.prepareStatement("""
                DELETE FROM rollback_jobs
                WHERE id = ?
                  AND NOT EXISTS (SELECT 1 FROM rollback_job_changes WHERE job_id = ?)
                  AND NOT EXISTS (SELECT 1 FROM rollback_job_chunks WHERE job_id = ?)
                """)) {
            parent.setLong(1, jobId);
            parent.setLong(2, jobId);
            parent.setLong(3, jobId);
            removedJob = parent.executeUpdate();
        }
        return new Step(deletedAudits, removedJob, true);
    }

    private static void requireLimit(int limit) {
        if (limit < 1 || limit > 5000) {
            throw new IllegalArgumentException("Retention cleanup limit must be 1..5000");
        }
    }
}
