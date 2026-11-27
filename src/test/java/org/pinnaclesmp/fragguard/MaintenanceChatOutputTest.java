package org.pinnaclesmp.fragguard;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** #197: formatting cannot hide host-quota warnings, STRICT policy or backup steps. */
class MaintenanceChatOutputTest {
    private static final long MIB = 1024L * 1024L;
    private static final PlainTextComponentSerializer PLAIN =
            PlainTextComponentSerializer.plainText();

    @TempDir Path directory;

    @Test
    void quickDiagnosticsHighlightsVerifiedResultAndPreservesAdminOnlyTechnicalDetails() {
        DatabaseHealth health = new DatabaseHealth(0, 20000, 0, 256, 0, 0, true, "");
        var report = new DatabaseQuickDiagnostics.Report(
                DatabaseQuickDiagnostics.Mode.QUICK_CHECK, Instant.now(),
                directory.resolve("fragguard.db"), 17L * 1024 * 1024 * 1024,
                128L * 1024, 32_768L, 40L * 1024 * 1024 * 1024,
                4_000_000, 4096, 1_000_000, 4_096_000_000L, 9,
                null, null, "ok", health, 30, 30);
        List<Component> out = new ArrayList<>();
        MaintenanceChatOutput.diagnostics(out::add, report);
        String plain = plain(out);
        assertTrue(plain.contains("▸ Database"));
        assertTrue(plain.contains("▸ Space and history"));
        assertTrue(plain.contains("▸ Logging health"));
        assertTrue(plain.contains(directory.resolve("fragguard.db").toAbsolutePath().toString()));
        assertTrue(plain.contains("Quick integrity check: ok"));
        assertTrue(plain.contains("Storage: OK"));
        assertTrue(out.stream().anyMatch(c -> PLAIN.serialize(c).contains("Quick integrity check: ok")
                && containsColor(c, NamedTextColor.GREEN)));
        assertTrue(out.stream().anyMatch(c -> PLAIN.serialize(c).contains("History rows:")
                && containsColor(c, NamedTextColor.YELLOW)));
    }

    @Test
    void preflightSummaryPrioritizesUnknownCapacityAndKeepsFullBreakdownAccessible() {
        var report = report(null, null, false);
        assertEquals(CompactionStoragePreflight.Decision.ACKNOWLEDGEMENT_REQUIRED,
                report.decision());
        List<Component> summary = new ArrayList<>();
        MaintenanceChatOutput.preflightSummary(summary::add, report);
        String text = plain(summary);
        assertTrue(text.contains("ACKNOWLEDGEMENT REQUIRED"));
        assertTrue(text.contains("Data / account"));
        assertTrue(text.contains("SQLite native temp"));
        assertTrue(text.contains("Hosting account: UNKNOWN"));
        assertTrue(text.contains("SQLite temp: UNKNOWN"));
        assertTrue(text.contains("account/container quota is unknown"));
        assertTrue(text.contains("native temp filesystem free space is unknown"));
        assertTrue(text.contains("/fg diagnostics preflight details"));
        assertFalse(text.contains("Java temp filesystem usable:"),
                "Long explanations should move to explicit detailed output");
        assertTrue(summary.stream().anyMatch(c -> PLAIN.serialize(c).contains("UNKNOWN")
                && containsColor(c, NamedTextColor.YELLOW)));

        List<Component> full = new ArrayList<>();
        MaintenanceChatOutput.preflightDetails(full::add, report);
        for (String line : report.lines()) {
            assertTrue(plain(full).contains(line),
                    "Detailed mode must preserve every original preflight line: " + line);
        }
        assertTrue(plain(full).contains("ADVISORY ONLY"));
        assertTrue(plain(full).contains("known shortages are never overridden"));
    }

    @Test
    void refusedPreflightIsRedAndNeverPresentedAsApproachable() {
        var report = report(0L, 0L, true);
        assertEquals(CompactionStoragePreflight.Decision.REFUSED, report.decision());
        List<Component> lines = new ArrayList<>();
        MaintenanceChatOutput.preflightSummary(lines::add, report);
        assertTrue(plain(lines).contains("REFUSED"));
        assertTrue(plain(lines).contains("insufficient"));
        assertTrue(lines.stream().anyMatch(c -> PLAIN.serialize(c).contains("REFUSED")
                && containsColor(c, NamedTextColor.RED)));
    }

    @Test
    void failedStrictMaintenanceLeadsWithPreservationAndRequiresReview() {
        long now = System.currentTimeMillis();
        var state = MaintenanceSnapshot.scheduled(UUID.randomUUID(), now, now + 60_000,
                        MaintenancePolicy.STRICT)
                .withPhase(MaintenancePhase.ACTIVE, "active")
                .withPhase(MaintenancePhase.FAILED, "stop during verification");
        List<Component> lines = new ArrayList<>();
        MaintenanceChatOutput.statusSummary(lines::add, state);
        String text = plain(lines);
        assertTrue(text.contains("Stage: FAILED"));
        assertTrue(text.contains("Policy: STRICT"));
        assertTrue(text.contains("Do NOT swap files"));
        assertTrue(text.contains("WAL/SHM"));
        assertTrue(text.contains("/fg maintenance status full"));
        assertTrue(lines.stream().anyMatch(c -> PLAIN.serialize(c).contains("Stage: FAILED")
                && containsColor(c, NamedTextColor.RED)));
    }

    @Test
    void activationPreviewNeverLooksLikeApprovalAndFullInstructionsStayExact() {
        long now = System.currentTimeMillis();
        var state = MaintenanceSnapshot.scheduled(UUID.randomUUID(), now, now + 60_000,
                        MaintenancePolicy.STRICT)
                .withPhase(MaintenancePhase.ACTIVE, "active")
                .withCandidate("fragguard-compact-test.db", "candidate")
                .withCutoff(now + 1, "copy")
                .withPhase(MaintenancePhase.VERIFYING, "checking")
                .withManifest("fragguard-compact-test.manifest", "verified");
        List<String> full = AutomaticCompactionActivation.operatorInstructions(state);
        List<Component> summary = new ArrayList<>();
        MaintenanceChatOutput.instructions(summary::add, state, full, false, true);
        assertTrue(plain(summary).contains("PREVIEW ONLY"));
        assertTrue(plain(summary).contains("shutdown receipt"));
        assertTrue(plain(summary).contains("Do NOT rename, move, or delete"));
        assertTrue(plain(summary).contains("/fg maintenance instructions full"));

        List<Component> detail = new ArrayList<>();
        MaintenanceChatOutput.instructions(detail::add, state, full, true, true);
        String displayed = plain(detail);
        for (String step : full) {
            assertTrue(displayed.contains(step), "Do not omit recovery/host guidance: " + step);
        }
    }

    @Test
    void snapshotModeAlwaysShowsHistoryLossWarning() {
        long now = System.currentTimeMillis();
        var snapshot = MaintenanceSnapshot.scheduled(UUID.randomUUID(), now, now + 60_000,
                        MaintenancePolicy.MAINTENANCE_SNAPSHOT)
                .withPhase(MaintenancePhase.ACTIVE, "active");
        List<Component> lines = new ArrayList<>();
        MaintenanceChatOutput.statusSummary(lines::add, snapshot);
        assertTrue(plain(lines).contains("SNAPSHOT can lose post-copy history/undo"));
        assertTrue(plain(lines).contains("Minecraft worlds never roll back"));
        assertTrue(lines.stream().anyMatch(c -> PLAIN.serialize(c).contains("SNAPSHOT can lose")
                && containsColor(c, NamedTextColor.RED)));
    }

    private CompactionStoragePreflight.Report report(Long account, Long nativeTemp,
                                                     boolean acknowledge) {
        var measured = new CompactionStoragePreflight.Measurement(
                directory.resolve("fragguard.db"),
                2_000L * MIB, 5L * MIB, 32_768L,
                4096, 512_000L, 100_000L,
                50_000L * MIB, 1_000L * MIB, 0, true, Instant.now());
        return CompactionStoragePreflight.evaluate(measured,
                new CompactionStoragePreflight.Evidence(
                        account, nativeTemp, 256, acknowledge));
    }

    private static String plain(List<Component> components) {
        return components.stream().map(PLAIN::serialize)
                .collect(Collectors.joining("\n"));
    }

    private static boolean containsColor(Component component, NamedTextColor color) {
        if (color.equals(component.color())) return true;
        return component.children().stream().anyMatch(c -> containsColor(c, color));
    }
}
