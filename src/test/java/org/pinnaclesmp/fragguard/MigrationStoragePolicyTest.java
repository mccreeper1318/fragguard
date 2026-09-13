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
    void sixGigabyteVersionThreeDatabaseCanUseIndexOnlyWorkingSpaceInsteadOfFullDuplicate() {
        long database = 6L * GIB;
        long measuredOldIndex = 1536L * 1024L * 1024L;
        long available = 5L * GIB;

        long indexOnly = MigrationStoragePolicy.requiredFreeBytes(
                MigrationStoragePolicy.estimateIndexWorkingBytes(database, measuredOldIndex), 256L);
        long fullBackup = MigrationStoragePolicy.requiredFreeBytes(
                MigrationStoragePolicy.estimateFullMigrationWorkingBytes(database), 256L);

        assertTrue(indexOnly < available,
                "the storage-aware v3->v4 path should fit without copying the entire history database");
        assertTrue(fullBackup > available,
                "the old full-backup strategy demonstrates why a large hosted database could not migrate");
    }

    @Test
    void missingIndexMeasurementFallsBackToBoundedDatabaseEstimateForRetry() {
        long database = 6L * GIB;
        long estimate = MigrationStoragePolicy.estimateIndexWorkingBytes(database, 0L);
        assertTrue(estimate >= 256L * 1024L * 1024L);
        assertTrue(estimate < database);
    }
}
