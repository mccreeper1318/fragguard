package org.pinnaclesmp.fragguard;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class CompactionStoragePreflightTest {
    private static final long MIB = 1024L * 1024L;
    @TempDir Path directory;

    @Test
    void knownCapacityRequiresBothAccountAndNativeTempEvidence() {
        var measured = measured(2_000L * MIB, 8_000L * MIB, 0);
        var approved = CompactionStoragePreflight.evaluate(measured,
                new CompactionStoragePreflight.Evidence(
                        8_000L * MIB, 8_000L * MIB, 256, false));
        assertEquals(CompactionStoragePreflight.Decision.READY, approved.decision());
        assertTrue(approved.mayReserve());
        assertTrue(approved.requiredDataFreeBytes() >= approved.estimatedOutputBytes()
                + approved.nativeWorkingBytes() + approved.safetyBytes());
        assertTrue(approved.requiredNativeTempFreeBytes() >= approved.nativeWorkingBytes()
                + approved.safetyBytes());
        assertTrue(approved.lines().stream().anyMatch(s -> s.contains("all preserved")));
        assertTrue(approved.lines().stream().anyMatch(s -> s.contains("NOT a disk guarantee")));
    }

    @Test
    void unknownQuotaAndSqliteNativeTempNeedExplicitAcknowledgement() {
        var measured = measured(2_000L * MIB, 8_000L * MIB, 0);
        var evidence = new CompactionStoragePreflight.Evidence(null, null, 256, false);
        var first = CompactionStoragePreflight.evaluate(measured, evidence);
        assertEquals(CompactionStoragePreflight.Decision.ACKNOWLEDGEMENT_REQUIRED,
                first.decision());
        assertFalse(first.mayReserve());
        assertTrue(first.reasons().stream().anyMatch(s -> s.contains("account/container")));
        assertTrue(first.reasons().stream().anyMatch(s -> s.contains("native temp")));
        assertTrue(first.lines().stream().anyMatch(s -> s.contains("filesystem ONLY")));
        assertTrue(first.lines().stream().anyMatch(s -> s.contains("ADVISORY ONLY")));

        var acknowledged = CompactionStoragePreflight.evaluate(measured,
                new CompactionStoragePreflight.Evidence(null, null, 256, true));
        assertEquals(CompactionStoragePreflight.Decision.READY_WITH_ACKNOWLEDGEMENT,
                acknowledged.decision());
        assertTrue(acknowledged.mayReserve());
        assertTrue(acknowledged.reasons().stream().anyMatch(s -> s.contains("explicitly")));
    }

    @Test
    void knownShortageIsNeverOverriddenByUnknownCapacityAcknowledgement() {
        var measured = measured(2_000L * MIB, 8_000L * MIB, 0);
        var baseline = CompactionStoragePreflight.evaluate(measured,
                new CompactionStoragePreflight.Evidence(null, null, 256, false));
        long dataRequirement = baseline.requiredDataFreeBytes();
        long nativeRequirement = baseline.requiredNativeTempFreeBytes();
        List<CompactionStoragePreflight.Report> cases = List.of(
                CompactionStoragePreflight.evaluate(measured(2_000L * MIB, dataRequirement - 1, 0),
                        new CompactionStoragePreflight.Evidence(null, null, 256, true)),
                CompactionStoragePreflight.evaluate(measured,
                        new CompactionStoragePreflight.Evidence(dataRequirement - 1, null, 256, true)),
                CompactionStoragePreflight.evaluate(measured,
                        new CompactionStoragePreflight.Evidence(null, nativeRequirement - 1, 256, true)),
                CompactionStoragePreflight.evaluate(measured,
                        new CompactionStoragePreflight.Evidence(0L, 0L, 256, true)));
        for (var report : cases) {
            assertEquals(CompactionStoragePreflight.Decision.REFUSED, report.decision());
            assertFalse(report.mayReserve());
        }
    }

    @Test
    void nonexistentOrUnsafePathsAndExistingCandidatesAlwaysRefuse() {
        var basic = measured(2_000L * MIB, 8_000L * MIB, 0);
        var full = new CompactionStoragePreflight.Evidence(8_000L * MIB, 8_000L * MIB, 256, true);
        var existing = withCandidates(basic, 1, true);
        var unsafe = withCandidates(basic, 0, false);
        assertEquals(CompactionStoragePreflight.Decision.REFUSED,
                CompactionStoragePreflight.evaluate(existing, full).decision());
        assertEquals(CompactionStoragePreflight.Decision.REFUSED,
                CompactionStoragePreflight.evaluate(unsafe, full).decision());
    }

    @Test
    void measuredSqlitePagesAndSidecarsAreReadOnly() throws Exception {
        Path db = directory.resolve("fragguard.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=DELETE");
            statement.execute("CREATE TABLE history(id INTEGER PRIMARY KEY)");
            statement.execute("INSERT INTO history VALUES(1)");
        }
        byte[] original = Files.readAllBytes(db);
        try (CompactionStoragePreflight preflight = new CompactionStoragePreflight(directory)) {
            var report = preflight.run(new CompactionStoragePreflight.Evidence(null, null, 256, false))
                    .get(10, TimeUnit.SECONDS);
            assertTrue(report.snapshot().pageCount() > 0);
            assertTrue(report.snapshot().pageSize() > 0);
            assertEquals(Files.size(db), report.snapshot().mainBytes());
            assertEquals(0L, report.snapshot().walBytes());
            assertEquals(0L, report.snapshot().shmBytes());
            assertEquals(CompactionStoragePreflight.Decision.ACKNOWLEDGEMENT_REQUIRED,
                    report.decision());
            assertFalse(Files.exists(directory.resolve("compaction-candidates")),
                    "inspection must not create any output directory");
        }
        assertArrayEquals(original, Files.readAllBytes(db));
    }

    @Test
    void reservationIsAtomicAndNeverOverwritesAnExistingCandidate() throws Exception {
        Files.writeString(directory.resolve("fragguard.db"), "source-do-not-change");
        try (CompactionStoragePreflight preflight = new CompactionStoragePreflight(directory)) {
            var report = CompactionStoragePreflight.evaluate(
                    measured(100 * MIB, 8_000L * MIB, 0),
                    new CompactionStoragePreflight.Evidence(null, null, 256, true));
            try (var reservation = preflight.reserve(report)) {
                assertTrue(reservation.candidate().startsWith(directory.resolve("compaction-candidates")));
                assertFalse(Files.exists(reservation.candidate()));
                assertThrows(IOException.class, () -> preflight.reserve(report),
                        "fixed CREATE_NEW reservation prevents concurrent compactions");
                Files.writeString(reservation.candidate(), "partial-unverified-candidate");
            }
            // A partial output and its marker are left intact for manual review.
            Path candidate = directory.resolve("compaction-candidates");
            try (var entries = Files.list(candidate)) {
                assertEquals(2L, entries.count());
            }
            assertThrows(IOException.class, () -> preflight.reserve(report));
            assertEquals("source-do-not-change", Files.readString(directory.resolve("fragguard.db")));
        }
    }

    @Test
    void unusedReservationReleasesOnlyOwnMarkerAndLeavesExistingFilesUntouched()
            throws Exception {
        Files.writeString(directory.resolve("fragguard.db"), "active-original");
        try (CompactionStoragePreflight preflight = new CompactionStoragePreflight(directory)) {
            var report = CompactionStoragePreflight.evaluate(
                    measured(100 * MIB, 8_000L * MIB, 0),
                    new CompactionStoragePreflight.Evidence(null, null, 256, true));
            try (var reservation = preflight.reserve(report)) {
                assertFalse(Files.exists(reservation.candidate()));
            }
            try (var entries = Files.list(directory.resolve("compaction-candidates"))) {
                assertEquals(0L, entries.count());
            }
            assertEquals("active-original", Files.readString(directory.resolve("fragguard.db")));
        }
    }

    @Test
    void staleMeasurementOrInsufficientPreflightNeverCreatesReservation() throws Exception {
        Files.writeString(directory.resolve("fragguard.db"), "active");
        try (CompactionStoragePreflight preflight = new CompactionStoragePreflight(directory)) {
            var old = measured(100 * MIB, 8_000L * MIB, 0);
            old = new CompactionStoragePreflight.Measurement(
                    old.database(), old.mainBytes(), old.walBytes(), old.shmBytes(),
                    old.pageSize(), old.pageCount(), old.freeListPages(),
                    old.filesystemFreeBytes(), old.sqliteTempFilesystemFreeBytes(),
                    old.existingCandidateFiles(), old.safePaths(),
                    Instant.now().minusSeconds(60));
            var approved = CompactionStoragePreflight.evaluate(old,
                    new CompactionStoragePreflight.Evidence(null, null, 256, true));
            assertThrows(IOException.class, () -> preflight.reserve(approved));
            var refused = CompactionStoragePreflight.evaluate(
                    measured(100 * MIB, 0L, 0),
                    new CompactionStoragePreflight.Evidence(null, null, 256, true));
            assertThrows(IOException.class, () -> preflight.reserve(refused));
            assertFalse(Files.exists(directory.resolve("compaction-candidates")));
        }
    }

    @Test
    void unsafeCandidateDirectorySymlinkNeverReservesOrRewritesOutsideFiles()
            throws Exception {
        // Inspection correctly reads SQLite page statistics even when the
        // candidate directory is unsafe; use an actual SQLite database here.
        Path source = directory.resolve("fragguard.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + source);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE history(id INTEGER PRIMARY KEY)");
        }
        byte[] original = Files.readAllBytes(source);
        Path outside = Files.createDirectory(directory.resolve("outside"));
        Files.writeString(outside.resolve("keep.bak"), "keep");
        Path candidates = directory.resolve("compaction-candidates");
        try {
            Files.createSymbolicLink(candidates, outside);
        } catch (UnsupportedOperationException | IOException ex) {
            // Not all filesystems allow creating symlinks in test environments.
            return;
        }
        try (CompactionStoragePreflight preflight = new CompactionStoragePreflight(directory)) {
            var m = preflight.measure();
            assertFalse(m.safePaths());
            var report = CompactionStoragePreflight.evaluate(m,
                    new CompactionStoragePreflight.Evidence(null, null, 256, true));
            assertEquals(CompactionStoragePreflight.Decision.REFUSED, report.decision());
            assertThrows(IOException.class, () -> preflight.reserve(report));
        }
        assertEquals("keep", Files.readString(outside.resolve("keep.bak")));
        assertArrayEquals(original, Files.readAllBytes(source),
                "unsafe candidate paths must not modify the active SQLite file");
    }

    @Test
    void sqliteFullResponseExplainsWhyPreflightCannotGuaranteeCapacity() {
        SQLException full = new SQLException("database or disk is full", "SQLITE_FULL", 13);
        String warning = CompactionStoragePreflight.sqliteFullMessage(full);
        assertTrue(warning.contains("SQLITE_FULL"));
        assertTrue(warning.contains("preserved"));
        assertFalse(CompactionStoragePreflight.sqliteFullMessage(new SQLException("busy"))
                .contains("reported SQLITE_FULL"));
    }

    @Test
    void saturatedArithmeticNeverWrapsNegativeOrApprovesLowCapacity() {
        var m = measured(Long.MAX_VALUE / 2, Long.MAX_VALUE - 100, 0);
        var result = CompactionStoragePreflight.evaluate(m,
                new CompactionStoragePreflight.Evidence(1_000L * MIB,
                        1_000L * MIB, Long.MAX_VALUE, true));
        assertEquals(Long.MAX_VALUE, result.requiredDataFreeBytes());
        assertEquals(CompactionStoragePreflight.Decision.REFUSED, result.decision());
    }

    private CompactionStoragePreflight.Measurement measured(long databaseBytes, Long free, int existing) {
        return new CompactionStoragePreflight.Measurement(
                directory.resolve("fragguard.db"), databaseBytes, 10L * MIB, 32_768L,
                4_096L, databaseBytes / 4096L, 100L, free, 10_000L * MIB,
                existing, true, Instant.now());
    }

    private CompactionStoragePreflight.Measurement withCandidates(
            CompactionStoragePreflight.Measurement m, int files, boolean safe) {
        return new CompactionStoragePreflight.Measurement(
                m.database(), m.mainBytes(), m.walBytes(), m.shmBytes(),
                m.pageSize(), m.pageCount(), m.freeListPages(), m.filesystemFreeBytes(),
                m.sqliteTempFilesystemFreeBytes(), files, safe, Instant.now());
    }
}
