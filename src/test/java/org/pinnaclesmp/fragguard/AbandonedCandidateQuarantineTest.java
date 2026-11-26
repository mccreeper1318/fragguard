package org.pinnaclesmp.fragguard;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Quarantines evidence whole; never interprets it as an approved candidate. */
class AbandonedCandidateQuarantineTest {
    @TempDir Path root;
    private static final UUID ADMIN =
            UUID.fromString("beb719ce-70d3-4c36-90de-ac3798c3d1d9");

    @Test
    void preservesStaleCandidateAndReservationWithoutBlockingFreshSlot()
            throws Exception {
        Path candidates = Files.createDirectory(root.resolve("compaction-candidates"));
        Files.writeString(candidates.resolve("fragguard-compact-orphan.db"),
                "abandoned-unverified-data");
        Files.writeString(candidates.resolve(".fg-compaction-reservation"),
                "fragguard-compact-orphan.db\n");
        Path archive = AbandonedCandidateQuarantine.archiveIfAbandoned(
                root, MaintenanceSnapshot.idle(), ADMIN);
        assertNotNull(archive);
        assertTrue(archive.getFileName().toString()
                .startsWith("abandoned-" + ADMIN + "-"));
        assertEquals("abandoned-unverified-data", Files.readString(
                archive.resolve("fragguard-compact-orphan.db")));
        assertEquals("fragguard-compact-orphan.db\n", Files.readString(
                archive.resolve(".fg-compaction-reservation")));
        assertFalse(Files.exists(candidates));
        Files.createDirectory(candidates);
        assertEquals(null, AbandonedCandidateQuarantine.archiveIfAbandoned(
                root, MaintenanceSnapshot.idle(), ADMIN));
        assertTrue(Files.isRegularFile(archive.resolve("fragguard-compact-orphan.db")));
    }

    @Test
    void activeMaintenanceCannotQuarantineRecoveryEvidence() throws Exception {
        Path candidates = Files.createDirectory(root.resolve("compaction-candidates"));
        Files.writeString(candidates.resolve("fragguard-compact-orphan.db"), "source");
        long now = System.currentTimeMillis();
        var active = MaintenanceSnapshot.scheduled(ADMIN, now, now + 60_000,
                MaintenancePolicy.STRICT).withPhase(MaintenancePhase.ACTIVE, "frozen");
        assertThrows(IOException.class, () -> AbandonedCandidateQuarantine.archiveIfAbandoned(
                root, active, ADMIN));
        assertTrue(Files.isRegularFile(candidates.resolve("fragguard-compact-orphan.db")));
    }

    @Test
    void refusesUnknownFoldersInsteadOfMovingOrDeletingThem() throws Exception {
        Path candidates = Files.createDirectory(root.resolve("compaction-candidates"));
        Files.createDirectory(candidates.resolve("unexpected-folder"));
        assertThrows(IOException.class, () -> AbandonedCandidateQuarantine.archiveIfAbandoned(
                root, MaintenanceSnapshot.idle(), ADMIN));
        assertTrue(Files.isDirectory(candidates.resolve("unexpected-folder")));
    }

    @Test
    void refusesUnboundedArchiveGrowthButNeverDeletesOldData() throws Exception {
        Path archives = Files.createDirectory(root.resolve("compaction-candidates-old"));
        for (int i = 0; i < 16; i++) {
            Files.createDirectory(archives.resolve("old-" + i));
        }
        Path candidates = Files.createDirectory(root.resolve("compaction-candidates"));
        Files.writeString(candidates.resolve("fragguard-compact-orphan.db"), "preserve");
        assertThrows(IOException.class, () -> AbandonedCandidateQuarantine.archiveIfAbandoned(
                root, MaintenanceSnapshot.idle(), ADMIN));
        assertEquals("preserve", Files.readString(
                candidates.resolve("fragguard-compact-orphan.db")));
        try (var listing = Files.list(archives)) {
            assertEquals(16L, listing.count());
        }
    }
}
