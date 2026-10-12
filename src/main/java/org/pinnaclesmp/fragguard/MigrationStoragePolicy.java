package org.pinnaclesmp.fragguard;

import java.util.Locale;

final class MigrationStoragePolicy {
    private static final long MIB = 1024L * 1024L;
    private static final long MIN_FULL_MIGRATION_WORK_BYTES = 256L * MIB;

    private MigrationStoragePolicy() {
    }

    static boolean requiresFullBackup(int startingSchemaVersion) {
        return startingSchemaVersion < 3;
    }

    static long estimateFullMigrationWorkingBytes(long databaseBytes) {
        long migrationWork = Math.max(databaseBytes / 2L, MIN_FULL_MIGRATION_WORK_BYTES);
        return saturatingAdd(databaseBytes, migrationWork);
    }

    static long requiredFreeBytes(long estimatedWorkingBytes, long safetyMiB) {
        long safetyBytes = saturatingMultiply(Math.max(0L, safetyMiB), MIB);
        return saturatingAdd(Math.max(0L, estimatedWorkingBytes), safetyBytes);
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
