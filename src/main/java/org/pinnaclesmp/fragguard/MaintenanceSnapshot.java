package org.pinnaclesmp.fragguard;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable, database-independent maintenance status. The active SQLite file,
 * its WAL/SHM and rollback data are never changed by these state transitions.
 */
record MaintenanceSnapshot(
        MaintenancePhase phase,
        String operationId,
        String operatorUuid,
        long requestedAtMillis,
        long countdownEndsAtMillis,
        MaintenancePolicy policy,
        long snapshotCutoffMillis,
        String candidateFile,
        String manifestFile,
        String detail,
        long revision) {

    MaintenanceSnapshot {
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(operatorUuid, "operatorUuid");
        Objects.requireNonNull(candidateFile, "candidateFile");
        Objects.requireNonNull(manifestFile, "manifestFile");
        Objects.requireNonNull(detail, "detail");
        if (revision < 0 || detail.length() > 512) {
            throw new IllegalArgumentException("Invalid maintenance revision or detail");
        }
        if (phase == MaintenancePhase.IDLE) {
            if (!operationId.isEmpty() || !operatorUuid.isEmpty()
                    || requestedAtMillis != 0 || countdownEndsAtMillis != 0
                    || snapshotCutoffMillis != 0 || !candidateFile.isEmpty() || !manifestFile.isEmpty()) {
                throw new IllegalArgumentException("Idle maintenance state must have no active operation");
            }
        } else {
            if (!validUuid(operationId) || !validUuid(operatorUuid)
                    || requestedAtMillis <= 0 || countdownEndsAtMillis <= requestedAtMillis
                    || snapshotCutoffMillis < 0) {
                throw new IllegalArgumentException("Invalid persisted maintenance identity or schedule");
            }
        }
        if (!validFileName(candidateFile, ".db") || !validFileName(manifestFile, ".manifest")) {
            throw new IllegalArgumentException("Invalid maintenance candidate/manifest filename");
        }
        if (phase == MaintenancePhase.READY_TO_SWAP
                && (snapshotCutoffMillis <= 0 || candidateFile.isEmpty() || manifestFile.isEmpty())) {
            throw new IllegalArgumentException("Ready state requires a verified snapshot and manifest");
        }
    }

    static MaintenanceSnapshot idle() {
        return new MaintenanceSnapshot(MaintenancePhase.IDLE, "", "", 0, 0,
                MaintenancePolicy.STRICT, 0, "", "", "", 0);
    }

    static MaintenanceSnapshot scheduled(UUID operator, long now, long deadline, MaintenancePolicy policy) {
        if (operator == null || now <= 0 || deadline <= now) {
            throw new IllegalArgumentException("Maintenance requires a valid operator and future deadline");
        }
        return new MaintenanceSnapshot(MaintenancePhase.COUNTDOWN, UUID.randomUUID().toString(),
                operator.toString(), now, deadline, Objects.requireNonNull(policy),
                0, "", "", "Maintenance countdown scheduled", 1);
    }

    MaintenanceSnapshot withPhase(MaintenancePhase next, String message) {
        if (!phase.canTransitionTo(next)) {
            throw new IllegalStateException("Invalid maintenance transition: " + phase + " -> " + next);
        }
        return updated(next, snapshotCutoffMillis, candidateFile, manifestFile, message);
    }

    MaintenanceSnapshot withCandidate(String file, String message) {
        if (phase != MaintenancePhase.ACTIVE || !validFileName(file, ".db") || file.isEmpty()) {
            throw new IllegalStateException("Only active maintenance may begin a safe compacted copy");
        }
        return updated(MaintenancePhase.COMPACTING, 0, file, "", message);
    }

    MaintenanceSnapshot withCutoff(long cutoff, String message) {
        if (phase != MaintenancePhase.COMPACTING || cutoff <= 0 || cutoff < requestedAtMillis) {
            throw new IllegalStateException("A valid SQLite snapshot cutoff is required during compaction");
        }
        return updated(phase, cutoff, candidateFile, manifestFile, message);
    }

    MaintenanceSnapshot withManifest(String file, String message) {
        if (phase != MaintenancePhase.VERIFYING || snapshotCutoffMillis <= 0
                || !validFileName(file, ".manifest") || file.isEmpty()) {
            throw new IllegalStateException("Verification requires a snapshot and manifest");
        }
        return updated(phase, snapshotCutoffMillis, candidateFile, file, message);
    }

    MaintenanceSnapshot reset() {
        if (phase != MaintenancePhase.CANCELLED && phase != MaintenancePhase.READY_TO_SWAP) {
            throw new IllegalStateException("Cannot reset maintenance before cancellation/verification");
        }
        return new MaintenanceSnapshot(MaintenancePhase.IDLE, "", "", 0, 0,
                MaintenancePolicy.STRICT, 0, "", "", "", revision + 1);
    }

    boolean blocksNewJobs() {
        return phase.blocksNewJobs();
    }

    private MaintenanceSnapshot updated(MaintenancePhase next, long cutoff, String candidate,
                                        String manifest, String message) {
        return new MaintenanceSnapshot(next, operationId, operatorUuid,
                requestedAtMillis, countdownEndsAtMillis, policy, cutoff,
                candidate, manifest, Objects.requireNonNull(message, "message"), revision + 1);
    }

    private static boolean validUuid(String uuid) {
        try {
            return UUID.fromString(uuid).toString().equals(uuid);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static boolean validFileName(String name, String suffix) {
        return name.isEmpty() || (name.length() <= 120
                && name.matches("fragguard-compact-[a-zA-Z0-9_-]+" + java.util.regex.Pattern.quote(suffix)));
    }
}

enum MaintenancePolicy {
    STRICT,
    MAINTENANCE_SNAPSHOT
}

enum MaintenancePhase {
    IDLE,
    COUNTDOWN,
    ACTIVE,
    COMPACTING,
    VERIFYING,
    READY_TO_SWAP,
    FAILED,
    CANCELLED;

    boolean canTransitionTo(MaintenancePhase next) {
        return switch (this) {
            case IDLE -> next == COUNTDOWN;
            case COUNTDOWN -> next == ACTIVE || next == CANCELLED || next == FAILED;
            case ACTIVE -> next == COMPACTING || next == CANCELLED || next == FAILED;
            case COMPACTING -> next == VERIFYING || next == CANCELLED || next == FAILED;
            case VERIFYING -> next == READY_TO_SWAP || next == CANCELLED || next == FAILED;
            case READY_TO_SWAP -> next == IDLE || next == CANCELLED || next == FAILED;
            case FAILED -> next == CANCELLED;
            case CANCELLED -> next == IDLE || next == COUNTDOWN;
        };
    }

    boolean blocksNewJobs() {
        return this != IDLE && this != COUNTDOWN && this != CANCELLED;
    }
}
