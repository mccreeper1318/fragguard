package org.pinnaclesmp.fragguard;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

class MigrationStoragePolicyTest {
    private static final long GIB = 1024L * 1024L * 1024L;
    private static final long MIB = 1024L * 1024L;

    @Test
    void onlyDataChangingLegacyMigrationsRequireFullDatabaseBackup() {
        assertTrue(MigrationStoragePolicy.requiresFullBackup(0));
        assertTrue(MigrationStoragePolicy.requiresFullBackup(1));
        assertTrue(MigrationStoragePolicy.requiresFullBackup(2));
        assertFalse(MigrationStoragePolicy.requiresFullBackup(3));
    }

    @Test
    void fullBackupMigrationEstimateIncludesDatabaseCopyAndLaterIndexWork() {
        long database = 6L * GIB;
        long estimated = MigrationStoragePolicy.estimateFullMigrationWorkingBytes(database);
        long required = MigrationStoragePolicy.requiredFreeBytes(estimated, 256L);
        assertTrue(required > database + 2 * database,
                "backup preflight must also reserve for later large index migrations");
    }

    @Test
    void logicalDatabasePagesExcludeReusableFreelistPages() {
        long active = MigrationStoragePolicy.logicalDatabaseBytes(4096, 22_000_000, 17_700_804);
        assertEquals((22_000_000L - 17_700_804L) * 4096L, active);
        assertEquals(0, MigrationStoragePolicy.logicalDatabaseBytes(4096, 10, 50));
        assertEquals(Long.MAX_VALUE,
                MigrationStoragePolicy.logicalDatabaseBytes(Long.MAX_VALUE, 2, 0));
    }

    @Test
    void everyIndexStageGetsAWorkingSpaceEstimateButMetadataIsSmaller() {
        long logical = 17L * GIB;
        for (int version = 4; version <= 6; version++) {
            assertTrue(MigrationStoragePolicy.estimateIndexMigrationWorkingBytes(logical, version)
                    >= 34L * GIB, "index-heavy schema v" + version + " needs WAL and sort headroom");
        }
        assertEquals(128L * MIB, MigrationStoragePolicy.estimateIndexMigrationWorkingBytes(logical, 7),
                "v7→v8 validates an existing index and needs no new 57M-row index");
        assertTrue(MigrationStoragePolicy.estimateIndexMigrationWorkingBytes(logical, 3) > 0L);
        assertTrue(MigrationStoragePolicy.estimateIndexMigrationWorkingBytes(logical, 8) > 0L);
        assertTrue(MigrationStoragePolicy.estimateIndexMigrationWorkingBytes(logical, 8)
                > MigrationStoragePolicy.estimateIndexMigrationWorkingBytes(logical, 7));
        assertEquals(512L * MIB, MigrationStoragePolicy.estimateIndexMigrationWorkingBytes(1, 4));
    }

    @Test
    void rejectsWhenHostFilesystemFreeIsLessThanRequired() {
        SQLException failure = assertThrows(SQLException.class,
                () -> MigrationStoragePolicy.checkCapacity(2L * GIB, GIB, 0, 0));
        assertTrue(failure.getMessage().contains("filesystem-reported"));
    }

    @Test
    void rejectsUnknownOrLowOperatorQuotaEvenWhenHostReportsTerabytesFree() {
        SQLException failure = assertThrows(SQLException.class,
                () -> MigrationStoragePolicy.checkCapacity(2L * GIB, 3_000L * GIB, GIB, 0));
        assertTrue(failure.getMessage().contains("account free"));
        // Unknown quota is not incorrectly considered proof of an available quota:
        // this check can pass but the operator must still be warned in the logs.
        assertDoesNotThrow(() -> MigrationStoragePolicy.checkCapacity(2L * GIB, 3_000L * GIB, 0, 0));
    }

    @Test
    void rejectsConstrainedNativeTempBudgetAndDistinguishesUnknownHostFree() {
        SQLException temp = assertThrows(SQLException.class,
                () -> MigrationStoragePolicy.checkCapacity(2L * GIB, 3_000L * GIB, 0, GIB));
        assertTrue(temp.getMessage().contains("SQLite native temp"));
        SQLException unknown = assertThrows(SQLException.class,
                () -> MigrationStoragePolicy.checkCapacity(2L * GIB, 0, 0, 0));
        assertTrue(unknown.getMessage().contains("no known writable free space")
                || unknown.getMessage().contains("free space is known"));
        assertDoesNotThrow(() -> MigrationStoragePolicy.checkCapacity(2L * GIB, 0, 3L * GIB, 0));
    }

    @Test
    void workingSpaceAndSafetyMarginNeverOverflow() {
        assertEquals(Long.MAX_VALUE,
                MigrationStoragePolicy.estimateIndexMigrationWorkingBytes(Long.MAX_VALUE, 4));
        assertEquals(Long.MAX_VALUE,
                MigrationStoragePolicy.estimateFullMigrationWorkingBytes(Long.MAX_VALUE));
        assertEquals(Long.MAX_VALUE,
                MigrationStoragePolicy.requiredFreeBytes(Long.MAX_VALUE, Long.MAX_VALUE));
        assertEquals(0, MigrationStoragePolicy.mibToBytes(-500));
    }

    @Test
    void identifiesNestedSqliteFullWithoutMislabelingOtherSqlErrors() {
        SQLException full = new SQLException("[SQLITE_FULL] database or disk is full", null, 13);
        SQLException wrapper = new SQLException("index migration", full);
        assertTrue(MigrationStoragePolicy.isSqliteFull(full));
        assertTrue(MigrationStoragePolicy.isSqliteFull(wrapper));
        assertFalse(MigrationStoragePolicy.isSqliteFull(new SQLException("database is locked", null, 5)));
    }
}
