package org.pinnaclesmp.fragguard;

import java.sql.SQLException;
import java.util.Locale;

/**
 * Conservative planning estimates for SQLite migrations. These are not quotas
 * or guaranteed upper bounds on SQLite VFS temporary/WAL space consumption.
 */
final class MigrationStoragePolicy {
    private static final long MIB = 1024L * 1024L;
    private static final long MIN_FULL_MIGRATION_WORK_BYTES = 256L * MIB;
    private static final long MIN_INDEX_WORK_BYTES = 512L * MIB;
    private static final long MIN_METADATA_WORK_BYTES = 128L * MIB;

    private MigrationStoragePolicy() {
    }

    static boolean requiresFullBackup(int startingSchemaVersion) {
        return startingSchemaVersion < 3;
    }

    /**
     * Reserve for the backup copy, legacy table rewrite, and one index-heavy
     * migration step. The index estimate is per step because #139 checkpoints
     * v4 indexes between statements. Actual working-space requirements vary.
     */
    static long estimateFullMigrationWorkingBytes(long databaseBytes) {
        long copyBytes = Math.max(0L, databaseBytes);
        long migrationWork = Math.max(copyBytes / 2L, MIN_FULL_MIGRATION_WORK_BYTES);
        return saturatingAdd(saturatingAdd(copyBytes, migrationWork),
                estimateIndexMigrationWorkingBytes(copyBytes, 4));
    }

    /**
     * Estimate one v3+ migration stage, not the sum of every future stage.
     * SQLite index creation can use WAL growth plus sort temporary space, so
     * budget twice the logical data volume for index-heavy stages.
     *
     * The storage estimate is advisory and cannot prove quota availability.
     */
    static long estimateIndexMigrationWorkingBytes(long logicalDatabaseBytes, int previousVersion) {
        long bytes = Math.max(0L, logicalDatabaseBytes);
        if (previousVersion >= 4 && previousVersion <= 7) {
            return Math.max(MIN_INDEX_WORK_BYTES, saturatingMultiply(bytes, 2L));
        }
        // v3 index removal and v8 metadata/job-scope updates are lighter, but
        // WAL/journal and metadata growth still need headroom.
        return Math.max(MIN_METADATA_WORK_BYTES, bytes / 8L);
    }

    /** Measure live SQLite pages rather than mistaking freelist pages for data. */
    static long logicalDatabaseBytes(long pageSize, long pageCount, long freelistCount) {
        long allocated = Math.max(0L, pageCount);
        long free = Math.min(allocated, Math.max(0L, freelistCount));
        return saturatingMultiply(allocated - free, Math.max(0L, pageSize));
    }

    static long requiredFreeBytes(long estimatedWorkingBytes, long safetyMiB) {
        long safetyBytes = saturatingMultiply(Math.max(0L, safetyMiB), MIB);
        return saturatingAdd(Math.max(0L, estimatedWorkingBytes), safetyBytes);
    }

    /**
     * Only an explicitly known *free* quota is a hard limit. A zero reported
     * value means unknown, not unlimited; Java's host filesystem value cannot
     * establish a container/account quota or SQLite's native temp mount.
     */
    static void checkCapacity(long required, long hostFilesystemFree,
                              long operatorAccountFree, long operatorSqliteTempFree)
            throws SQLException {
        if (hostFilesystemFree > 0 && hostFilesystemFree < required) {
            throw new SQLException("filesystem-reported free space is below the estimated migration requirement");
        }
        if (operatorAccountFree > 0 && operatorAccountFree < required) {
            throw new SQLException("operator-reported account free space is below the estimated migration requirement");
        }
        if (operatorSqliteTempFree > 0 && operatorSqliteTempFree < required) {
            throw new SQLException("operator-reported SQLite native temp free space is below the "
                    + "estimated migration requirement");
        }
        if (hostFilesystemFree <= 0 && operatorAccountFree <= 0) {
            throw new SQLException("neither filesystem-reported nor operator-reported writable free space is known");
        }
    }

    static String formatBytes(long bytes) {
        if (bytes < MIB) {
            return bytes + " B";
        }
        double mib = bytes / (double) MIB;
        if (mib < 1024.0) {
            return String.format(Locale.ROOT, "%.1f MiB", mib);
        }
        return String.format(Locale.ROOT, "%.2f GiB", mib / 1024.0);
    }

    static long mibToBytes(long mib) {
        return saturatingMultiply(Math.max(0L, mib), MIB);
    }

    private static long saturatingAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private static long saturatingMultiply(long left, long right) {
        if (left == 0L || right == 0L) {
            return 0L;
        }
        if (left > Long.MAX_VALUE / right) {
            return Long.MAX_VALUE;
        }
        return left * right;
    }
}
