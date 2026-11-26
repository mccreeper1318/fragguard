package org.pinnaclesmp.fragguard;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * #197: Operator-facing chat presentation ONLY. The underlying read-only
 * reports, permission checks, maintenance transitions and file safety gates
 * remain unchanged. Never hide a warning in favor of attractive formatting.
 */
final class MaintenanceChatOutput {
    private MaintenanceChatOutput() { }

    static void title(Consumer<Component> send, String title) {
        send.accept(Component.text("── " + title + " ──", NamedTextColor.AQUA));
    }

    static void section(Consumer<Component> send, String name) {
        send.accept(Component.text("▸ " + name, NamedTextColor.AQUA));
    }

    static void value(Consumer<Component> send, String label, String content) {
        value(send, label, content, NamedTextColor.WHITE);
    }

    static void value(Consumer<Component> send, String label, String content,
                      NamedTextColor color) {
        send.accept(Component.text("  " + label + ": ", NamedTextColor.GRAY)
                .append(Component.text(content, color)));
    }

    static void notice(Consumer<Component> send, String message) {
        send.accept(Component.text("! " + message, NamedTextColor.YELLOW));
    }

    static void danger(Consumer<Component> send, String message) {
        send.accept(Component.text("! " + message, NamedTextColor.RED));
    }

    static void action(Consumer<Component> send, String text) {
        send.accept(Component.text("→ " + text, NamedTextColor.GREEN));
    }

    static void diagnostics(Consumer<Component> send,
                            DatabaseQuickDiagnostics.Report report) {
        Objects.requireNonNull(report);
        List<String> lines = report.lines();
        title(send, "FragGuard Database Diagnostics");
        section(send, "Database");
        for (int index = 0; index <= 6; index++) labeled(send, lines.get(index));
        section(send, "Space and history");
        for (int index = 7; index <= 11; index++) labeled(send, lines.get(index));
        section(send, "Logging health");
        for (int index = 12; index < lines.size(); index++) labeled(send, lines.get(index));
        if (report.mode() == DatabaseQuickDiagnostics.Mode.OVERVIEW) {
            action(send, "Full checks: /fg diagnostics counts or /fg diagnostics check");
        }
    }

    static void preflightSummary(Consumer<Component> send,
                                 CompactionStoragePreflight.Report report) {
        title(send, "FragGuard Compaction Preflight");
        NamedTextColor decisionColor = switch (report.decision()) {
            case READY, READY_WITH_ACKNOWLEDGEMENT -> NamedTextColor.GREEN;
            case ACKNOWLEDGEMENT_REQUIRED -> NamedTextColor.YELLOW;
            case REFUSED -> NamedTextColor.RED;
        };
        value(send, "Decision", report.decision().name().replace('_', ' '), decisionColor);
        section(send, "Estimated additional free space");
        value(send, "Data / account", MigrationStoragePolicy.formatBytes(
                report.requiredDataFreeBytes()));
        value(send, "SQLite native temp", MigrationStoragePolicy.formatBytes(
                report.requiredNativeTempFreeBytes()));
        section(send, "Capacity evidence");
        value(send, "Filesystem", capacity(report.snapshot().filesystemFreeBytes())
                + " (not proof of account quota)");
        value(send, "Hosting account", capacity(report.evidence().accountFreeBytes()),
                report.evidence().accountFreeBytes() == null
                        ? NamedTextColor.YELLOW : NamedTextColor.WHITE);
        value(send, "SQLite temp", capacity(report.evidence().sqliteNativeTempFreeBytes()),
                report.evidence().sqliteNativeTempFreeBytes() == null
                        ? NamedTextColor.YELLOW : NamedTextColor.WHITE);
        value(send, "Existing candidates / reservations",
                Integer.toString(report.snapshot().existingCandidateFiles()),
                report.snapshot().existingCandidateFiles() > 0
                        ? NamedTextColor.RED : NamedTextColor.WHITE);
        if (!report.reasons().isEmpty()) {
            section(send, "Warnings / reasons");
            for (String reason : report.reasons()) {
                if (report.decision() == CompactionStoragePreflight.Decision.REFUSED) {
                    danger(send, reason);
                } else {
                    notice(send, reason);
                }
            }
        }
        notice(send, "Estimate only; SQLITE_FULL remains possible. Originals and WAL/SHM stay intact.");
        action(send, "Full breakdown: /fg diagnostics preflight details");
    }

    static void preflightDetails(Consumer<Component> send,
                                 CompactionStoragePreflight.Report report) {
        title(send, "Compaction Preflight · Full Details");
        List<String> lines = report.lines();
        for (int i = 0; i < lines.size(); i++) {
            if (i == 2) section(send, "New copy / working space");
            if (i == 6) section(send, "Capacity / quota / temp evidence");
            if (i == 11) section(send, "Warnings and retention safeguards");
            labeled(send, lines.get(i));
        }
    }

    static void deepDiagnostics(Consumer<Component> send,
                                DatabaseDeepDiagnostics.Report report) {
        title(send, "FragGuard Deep Diagnostics");
        List<String> lines = report.lines();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (i == 0) section(send, "Result");
            if (i == 3) section(send, "Scanned records and table fingerprints");
            if (line.startsWith("Finding:")) section(send, "Findings");
            if (line.startsWith("Report:")) section(send, "Saved evidence");
            labeled(send, line);
        }
        if (!report.complete() || !report.passed()) {
            danger(send, "Partial or failed validation is NOT proof of database integrity.");
        }
    }

    static void statusSummary(Consumer<Component> send, MaintenanceSnapshot state) {
        title(send, "FragGuard Database Maintenance");
        NamedTextColor stageColor = switch (state.phase()) {
            case IDLE, CANCELLED -> NamedTextColor.GREEN;
            case FAILED, ACTIVATING -> NamedTextColor.RED;
            case READY_TO_SWAP -> NamedTextColor.YELLOW;
            default -> NamedTextColor.AQUA;
        };
        value(send, "Stage", state.phase().name(), stageColor);
        value(send, "Policy", state.policy().name(),
                state.policy() == MaintenancePolicy.STRICT
                        ? NamedTextColor.GREEN : NamedTextColor.RED);
        if (!state.candidateFile().isBlank()) {
            value(send, "Candidate", state.candidateFile());
        }
        value(send, "Verified manifest", state.manifestFile().isBlank()
                ? "not recorded" : "recorded",
                state.manifestFile().isBlank() ? NamedTextColor.YELLOW : NamedTextColor.GREEN);
        section(send, "Next step");
        switch (state.phase()) {
            case IDLE, CANCELLED -> action(send, "Run /fg maintenance start — automatic STRICT workflow");
            case COUNTDOWN -> notice(send, "Countdown: logging ON. At expiry regular "
                    + "players leave and the SQLite freeze begins.");
            case ACTIVE -> notice(send, state.policy() == MaintenancePolicy.STRICT
                    ? "BLOCK LOGGING PAUSED — automatic fresh preflight and compaction"
                    : "BLOCK LOGGING PAUSED — advanced Snapshot compaction");
            case COMPACTING -> notice(send, "BLOCK LOGGING PAUSED — copying SQLite "
                    + "candidate; no reliable percentage available");
            case VERIFYING -> notice(send, state.policy() == MaintenancePolicy.STRICT
                    ? (state.manifestFile().isBlank()
                        ? "BLOCK LOGGING PAUSED — full integrity/history verification"
                        : "BLOCK LOGGING PAUSED — automatic final STRICT equality check")
                    : "BLOCK LOGGING PAUSED — Snapshot needs separate explicit "
                            + "irreversible-loss approval");
            case READY_TO_SWAP -> {
                notice(send, "FragGuard activates the verified copy automatically "
                        + "when Paper next starts after a clean shutdown.");
                danger(send, "Do not rename or touch SQLite files.");
                action(send, "Guide: /fg maintenance instructions");
            }
            case ACTIVATING, FAILED -> {
                danger(send, "Preserve source, backup, candidate, WAL/SHM and manifests. Do NOT swap files.");
                notice(send, "Investigate failure; review status full before any cancel or recovery.");
            }
        }
        if (state.policy() == MaintenancePolicy.MAINTENANCE_SNAPSHOT
                && state.phase() != MaintenancePhase.IDLE) {
            danger(send, "SNAPSHOT can lose post-copy history/undo; Minecraft worlds never roll back.");
        }
        action(send, "Full status/evidence: /fg maintenance status full");
    }

    static void instructions(Consumer<Component> send,
                             MaintenanceSnapshot state, List<String> full, boolean detailed,
                             boolean strictPending) {
        title(send, detailed ? "Automatic Activation · Full Guidance"
                : "Automatic Activation · Summary");
        if (strictPending) {
            notice(send, "STRICT approval pending: PREVIEW ONLY. Complete the full "
                    + "online comparison before Paper stops; a clean shutdown "
                    + "receipt is mandatory.");
        } else {
            notice(send, "A valid clean shutdown receipt must be written "
                    + "before automatic activation is allowed.");
        }
        danger(send, "Do NOT rename, move, or delete SQLite files.");
        notice(send, "On startup, FragGuard preserves the original with matching WAL/SHM "
                + "and installs the verified candidate before SQLite opens.");
        if (state.policy() == MaintenancePolicy.MAINTENANCE_SNAPSHOT) {
            danger(send, "Snapshot Mode can lose post-copy history/undo. "
                    + "Minecraft worlds are NOT rolled back.");
        }
        if (detailed) {
            section(send, "Hosting-panel activation");
            for (String step : full) {
                send.accept(Component.text(step, NamedTextColor.WHITE));
            }
        } else {
            section(send, "What the server owner does");
            value(send, "After approval", "Paper stops cleanly and writes a verified receipt");
            value(send, "Restart", "If the host does not auto-restart, press Start once");
            value(send, "SQLite backup", "FragGuard preserves original DB and matching sidecars");
            action(send, "Full guidance: /fg maintenance instructions full");
        }
    }

    static void labeled(Consumer<Component> send, String line) {
        int cut = line.indexOf(':');
        if (cut < 1) {
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.contains("never") || lower.contains("do not")
                    || lower.contains("not a") || lower.contains("unknown")) {
                notice(send, line);
            } else {
                send.accept(Component.text("  " + line, NamedTextColor.GRAY));
            }
            return;
        }
        String label = line.substring(0, cut);
        String body = line.substring(cut + 1).stripLeading();
        String low = body.toLowerCase(Locale.ROOT);
        NamedTextColor color = NamedTextColor.WHITE;
        if (label.equals("Finding") || label.equals("Storage error")
                || (label.equals("Preflight") && (low.contains("insufficient")
                    || low.contains("unsafe") || low.contains("existing candidate")))
                || low.startsWith("failed checks") || low.startsWith("partial / untrusted")
                || low.startsWith("refused")) {
            color = NamedTextColor.RED;
        } else if (label.equals("Quick integrity check") && low.equals("ok")
                || label.equals("Storage") && low.startsWith("ok")
                || label.equals("Deep scan") && low.startsWith("pass")) {
            color = NamedTextColor.GREEN;
        } else if (low.startsWith("unknown") || low.startsWith("not run")
                || low.startsWith("not scanned") || low.contains("acknowledgement required")
                || low.contains("unknown")) {
            color = NamedTextColor.YELLOW;
        }
        value(send, label, body, color);
    }

    private static String capacity(Long bytes) {
        return bytes == null ? "UNKNOWN" : MigrationStoragePolicy.formatBytes(bytes);
    }
}
