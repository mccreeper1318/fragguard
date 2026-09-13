package org.pinnaclesmp.fragguard;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MigrationStoragePolicyTest {
    private static final long GIB = 1024L * 1024L * 1024L;

    @Test
    void onlyDataChangingLegacyMigrationsRequireFullDatabaseBackup() {
        assertTrue(MigrationStoragePolicy.requiresFullBackup(0));
        assertTrue(MigrationStoragePolicy.requiresFullBackup(1));
        assertTrue(MigrationStoragePolicy.requiresFullBackup(2));
        assertFalse(MigrationStoragePolicy.requiresFullBackup(3));
    }

    @Test
    void fullBackupMigrationEstimateStillIncludesDatabaseCopyAndWorkingSpace() {
        long database = 6L * GIB;
        long required = MigrationStoragePolicy.requiredFreeBytes(
                MigrationStoragePolicy.estimateFullMigrationWorkingBytes(database), 256L);
        assertTrue(required > database,
                "data-changing migrations must still reserve more than one database copy of free space");
    }
}
