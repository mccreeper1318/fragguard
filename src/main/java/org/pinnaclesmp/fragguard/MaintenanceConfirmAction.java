package org.pinnaclesmp.fragguard;

/** Fixed trusted GUI actions: no user-supplied command fragments or bypass tokens. */
enum MaintenanceConfirmAction {
    START_STRICT("start strict", "Schedule STRICT maintenance",
            "STRICT checks full source equality online before shutdown, then verifies no later writes and a clean SQLite checkpoint during shutdown. Wait for precheck PASS before stopping Paper."),
    START_SNAPSHOT("start snapshot acknowledge", "Opt in to Snapshot maintenance",
            "DATA LOSS: History, undo/rollback and retention updates after the real SQLite snapshot may be lost. Worlds are NOT rolled back. Approval later requires a second confirmation."),
    COMPACT("compact", "Start SQLite compaction",
            "Run an approved storage preflight first. The original database and WAL/SHM stay intact."),
    APPROVE_STRICT("approve", "Prepare STRICT replacement",
            "Final source-to-copy comparison is required AFTER database shutdown. A stale copy cannot be activated."),
    APPROVE_SNAPSHOT("approve snapshot-acknowledge", "Accept irreversible history loss",
            "FINAL LOSS CONSENT: All database history, rollback/undo records and retention updates after the actual copy may be lost. The Minecraft world NEVER rolls back. STOP Paper before any file rename."),
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
