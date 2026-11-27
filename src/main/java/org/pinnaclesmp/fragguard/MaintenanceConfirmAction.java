package org.pinnaclesmp.fragguard;

/** Fixed trusted GUI actions: no user-supplied command fragments or bypass tokens. */
enum MaintenanceConfirmAction {
    START_STRICT("start strict", "Start automatic STRICT maintenance",
            "ONE action starts automatic storage preflight, player countdown, frozen logging, compaction, complete verification, safe shutdown and restart activation. Unknown account/temp quota may require ONE separate acknowledgement. Never move SQLite files."),
    START_SNAPSHOT("start snapshot acknowledge", "Opt in to Snapshot maintenance",
            "DATA LOSS: History, undo/rollback and retention updates after the real SQLite snapshot may be lost. Worlds are NOT rolled back. Approval later requires a second confirmation."),
    COMPACT("compact", "Start SQLite compaction",
            "Run an approved storage preflight first. The original database and WAL/SHM stay intact."),
    APPROVE_STRICT("approve", "Prepare STRICT replacement",
            "A full asynchronous source-to-copy comparison runs BEFORE shutdown. Do not stop Paper until the scan passes. Final approval still needs an unchanged source, clean stop and checkpoint receipt."),
    APPROVE_SNAPSHOT("approve snapshot-acknowledge", "Accept irreversible history loss",
            "FINAL LOSS CONSENT: Post-copy history/undo/retention may be lost and The Minecraft world NEVER rolls back. Full file fingerprints are computed ONLINE during approval. Wait for completion, avoid further DB writes, then stop Paper cleanly. NEVER move files without a receipt."),
    CANCEL("cancel", "Cancel maintenance stage",
            "Existing DB and incomplete copies are preserved. READY/ACTIVATING cannot be cancelled here.");

    private final String command;
    private final String heading;
    private final String warning;

    MaintenanceConfirmAction(String command, String heading, String warning) {
        this.command = command;
        this.heading = heading;
        this.warning = warning;
    }
    String command() { return "fg maintenance " + command; }
    String heading() { return heading; }
    String warning() { return warning; }

    boolean available(MaintenanceSnapshot state) {
        return switch (this) {
            case START_STRICT, START_SNAPSHOT -> MaintenanceUiActions.canSchedule(state);
            case COMPACT -> MaintenanceUiActions.canCompact(state);
            case APPROVE_STRICT -> MaintenanceUiActions.canApprove(state)
                    && state.policy() == MaintenancePolicy.STRICT;
            case APPROVE_SNAPSHOT -> MaintenanceUiActions.canApprove(state)
                    && state.policy() == MaintenancePolicy.MAINTENANCE_SNAPSHOT;
            case CANCEL -> MaintenanceUiActions.canCancel(state);
        };
    }
}
