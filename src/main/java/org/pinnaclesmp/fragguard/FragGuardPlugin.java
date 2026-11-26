package org.pinnaclesmp.fragguard;

import org.bukkit.Bukkit;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

public final class FragGuardPlugin extends JavaPlugin {
    static final int DEFAULT_GUI_LOOKUP_FETCH_SIZE = 1000;

    private MaintenanceCoordinator maintenance;
    private MaintenanceNoticeDisplay maintenanceNoticeDisplay;
    private MaintenanceAdmissionGate maintenanceAdmissionGate;
    private Database database;
    private DatabaseQuickDiagnostics quickDiagnostics;
    private ReclaimableSpaceMonitor reclaimableSpaceMonitor;
    private DatabaseDeepDiagnostics deepDiagnostics;
    private CompactionStoragePreflight compactionPreflight;
    private OnlineDatabaseCompaction onlineCompaction;
    private CompactionCandidateVerifier candidateVerifier;
    private CompactionReplacementPolicy replacementPolicy;
    private FragGuardCommand commandExecutor;
    private FragGuardGui gui;
    private BlockChangeListener changeListener;
    private boolean storageWarningActive;
    private boolean incrementalIncompatibleNotified;
    private final AtomicBoolean retentionCleanupDispatchActive = new AtomicBoolean();
    private final AtomicBoolean retentionCleanupPauseNotified = new AtomicBoolean();
    private long lastStorageWarningAt;
    private int guiLookupFetchSize = DEFAULT_GUI_LOOKUP_FETCH_SIZE;
    // Countdown expiry must refuse NEW rollback/undo jobs while already
    // accepted world mutations and their persisted audit callbacks finish.
    // Do not set the SQLite write freeze before the jobs are quiescent.
    private volatile boolean rollbackJobsQuiescing;
    // A restricted startup cannot resume saved RUNNING/UNDOING jobs until
    // maintenance has been safely cancelled and the SQLite writer is writable.
    // Keep this request across failed/aborted cancellation attempts.
    private boolean deferredRollbackRecovery;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        try {
            maintenance = MaintenanceCoordinator.open(getDataFolder().toPath(), getLogger()::info);
        } catch (IOException exception) {
            getLogger().log(Level.SEVERE,
                    "Could not read safe maintenance metadata; do not edit or delete the file blindly. "
                    + "FragGuard will not open the database until this is resolved.", exception);
            // Unknown maintenance metadata can represent a locked server.
            // Fail closed rather than silently allowing ordinary players.
            Bukkit.shutdown();
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        if (maintenance.snapshot().phase() != MaintenancePhase.IDLE) {
            getLogger().warning("FragGuard maintenance state: " + maintenance.snapshot().phase()
                    + " — " + maintenance.snapshot().detail()
                    + ". The database has not been automatically replaced or reset.");
        }
        boolean activationVerified = false;
        if (CompactionReplacementPolicy.blocksWriterStartup(maintenance.snapshot())) {
            try {
                // The first startup requires the ORIGINAL candidate SHA and
                // zero active sidecars. A crashed prior startup is only allowed
                // through a persisted ACTIVATING stage and complete logical
                // re-verification of the installed WAL-capable candidate.
                boolean resuming = maintenance.snapshot().phase() == MaintenancePhase.ACTIVATING;
                var installed = resuming
                        ? CompactionActivation.verifyRecovering(
                                getDataFolder().toPath(), maintenance.snapshot())
                        : CompactionActivation.verifyInstalled(
                                getDataFolder().toPath(), maintenance.snapshot());
                if (!resuming) {
                    // Persist this stage before DB.init() can change journal
                    // mode, create new WAL/SHM, or initialize SQLite metadata.
                    maintenance.beginActivation().get(30, TimeUnit.SECONDS);
                }
                activationVerified = true;
                getLogger().info((resuming ? "Recovered" : "Verified")
                        + " manual SQLite activation of "
                        + installed.installed().getFileName()
                        + " (preserved backup: " + installed.backup().getFileName()
                        + ", checked records: " + installed.verifiedRows()
                        + ", policy: " + installed.policy() + ").");
            } catch (Exception exception) {
                getLogger().log(Level.SEVERE, "Cannot start FragGuard: manual compacted "
                        + "database replacement has not passed provenance, backup, WAL/SHM "
                        + "and full-history verification. Stop Paper and preserve all DB "
                        + "and manifest files for recovery.", exception);
                Bukkit.shutdown();
                Bukkit.getPluginManager().disablePlugin(this);
                return;
            }
        }
        int configuredGuiLookupFetchSize = getConfig().getInt(
                "gui-lookup-fetch-size", DEFAULT_GUI_LOOKUP_FETCH_SIZE);
        guiLookupFetchSize = resolveGuiLookupFetchSize(configuredGuiLookupFetchSize);
        if (configuredGuiLookupFetchSize <= 0) {
            getLogger().warning("Invalid gui-lookup-fetch-size value " + configuredGuiLookupFetchSize
                    + "; the value must be at least 1. Using the default of "
                    + DEFAULT_GUI_LOOKUP_FETCH_SIZE + ".");
        }
        database = new Database(this);
        try {
            database.init();
        } catch (SQLException | ClassNotFoundException exception) {
            getLogger().log(Level.SEVERE, "FragGuard could not start because SQLite failed to initialize.", exception);
            if (maintenance.blocksNewJobs()) {
                getLogger().severe("Cannot protect active maintenance with FragGuard disabled. Stopping Paper.");
                Bukkit.shutdown();
            }
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        if (activationVerified) {
            try {
                // Only now, after the installed candidate was validated AND
                // the actual SQLite writer successfully initialized, can
                // the durable maintenance gate be cleared.
                maintenance.finishVerifiedActivation().get(30, TimeUnit.SECONDS);
                // The replacement was checked before this writable startup.
                // ACTIVATING initialized this connection read-only; restore
                // SQL writes on its ordered worker ONLY AFTER durable IDLE,
                // before posting any legitimate world-name refresh.
                database.resumeWritesAfterCancellationAsync().get(30, TimeUnit.SECONDS);
                // The persisted ACTIVATING -> IDLE transition must complete
                // BEFORE any world-name normalization can update retained
                // rollback jobs or undo records. This preserves crash
                // recovery's exact candidate digests through Database.init().
                database.refreshWorldNamesAfterActivationAsync()
                        .get(60, TimeUnit.SECONDS);
                getLogger().info("Verified compacted database activation complete. "
                        + "The preserved original and its sidecars were not deleted.");
            } catch (Exception failure) {
                getLogger().log(Level.SEVERE, "Could not commit verified activation state; "
                        + "stopping Paper with recovery files preserved.", failure);
                Bukkit.shutdown();
                Bukkit.getPluginManager().disablePlugin(this);
                return;
            }
        }
        quickDiagnostics = new DatabaseQuickDiagnostics(
                getDataFolder().toPath().resolve("fragguard.db"));
        double minimumPercent = getConfig().getDouble("database-reclaimable-warning-percent", 10.0);
        if (!Double.isFinite(minimumPercent)) minimumPercent = 10.0;
        reclaimableSpaceMonitor = new ReclaimableSpaceMonitor(new ReclaimableSpaceMonitor.Settings(
                getConfig().getBoolean("database-reclaimable-monitor-enabled", false),
                Math.min(1440L, Math.max(1L, getConfig().getLong(
                        "database-reclaimable-monitor-interval-minutes", 60L))),
                Math.max(1L, getConfig().getLong("database-reclaimable-warning-mib", 1024L)),
                Math.max(0.0, Math.min(100.0, minimumPercent)),
                Math.min(10080L, Math.max(1L, getConfig().getLong(
                        "database-reclaimable-warning-cooldown-minutes", 1440L)))));
        deepDiagnostics = new DatabaseDeepDiagnostics(getDataFolder().toPath());
        compactionPreflight = new CompactionStoragePreflight(getDataFolder().toPath());
        onlineCompaction = new OnlineDatabaseCompaction(
                getDataFolder().toPath(), maintenance, compactionPreflight);
        candidateVerifier = new CompactionCandidateVerifier(getDataFolder().toPath(), maintenance);
        replacementPolicy = new CompactionReplacementPolicy(getDataFolder().toPath(), maintenance);
        try {
            maintenanceAdmissionGate = new MaintenanceAdmissionGate(this, maintenance,
                    System::currentTimeMillis,
                    getConfig().getString("maintenance-kick-message",
                            "&cMaintenance is active. Please return later."),
                    getConfig().getString("maintenance-login-denied-message",
                            "&cThe server is undergoing database maintenance. Please return later."));
            Bukkit.getPluginManager().registerEvents(maintenanceAdmissionGate, this);
            maintenanceAdmissionGate.start();
            maintenanceNoticeDisplay = new MaintenanceNoticeDisplay(
                    this, maintenance, MaintenanceNoticeSettings.from(getConfig()),
                    System::currentTimeMillis, this::activateMaintenanceAtDeadline);
            Bukkit.getPluginManager().registerEvents(maintenanceNoticeDisplay, this);
            maintenanceNoticeDisplay.start();
        } catch (RuntimeException exception) {
            getLogger().log(Level.SEVERE, "FragGuard maintenance admission protection failed to initialize.", exception);
            if (maintenance.blocksNewJobs()) {
                getLogger().severe("Maintenance access must not run without an admission gate. Stopping Paper.");
                Bukkit.shutdown();
            }
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        changeListener = new BlockChangeListener(this, database);
        Bukkit.getPluginManager().registerEvents(changeListener, this);
        Bukkit.getPluginManager().registerEvents(new ShelfChangeListener(this, database), this);
        gui = new FragGuardGui(this, database);
        Bukkit.getPluginManager().registerEvents(gui, this);
        commandExecutor = new FragGuardCommand(this, database);
        Bukkit.getPluginManager().registerEvents(commandExecutor, this);
        PluginCommand command = Objects.requireNonNull(getCommand("fg"), "Command /fg is missing from plugin.yml");
        command.setExecutor(commandExecutor);
        command.setTabCompleter(commandExecutor);
        if (!maintenanceBlocksNewJobs()) {
            commandExecutor.resumeInterruptedJobs();
        } else {
            deferRollbackRecovery();
            getLogger().warning("Maintenance remains restricted: rollback/undo jobs "
                    + "will not resume until maintenance is safely cancelled, and "
                    + "world changes are NOT logged.");
        }
        scheduleCleanup();
        scheduleStorageHealthMonitor();
        scheduleReclaimableSpaceMonitor();
        getLogger().info("FragGuard enabled. Block changes are retained for " + getRetentionDays() + " days.");
    }

    /** #198: a requested Paper shutdown must never be mistaken for plugin-only reload. */
    static boolean mustStopPaperOnRestrictedDisable(boolean restricted, boolean paperStopping) {
        return restricted && !paperStopping;
    }

    @Override
    public void onDisable() {
        if (maintenanceAdmissionGate != null) {
            boolean wasRestricted = maintenanceAdmissionGate.restricted();
            maintenanceAdmissionGate.close();
            maintenanceAdmissionGate = null;
            // Bukkit.isStopping() distinguishes an ordinary, requested Paper
            // shutdown from a dangerous plugin-only disable/reload. Do not
            // recursively call shutdown (or report a false severe error) in
            // the former. Still fail closed if FragGuard is disabled alone.
            if (mustStopPaperOnRestrictedDisable(wasRestricted, Bukkit.isStopping())) {
                getLogger().severe("FragGuard disabled independently while maintenance access "
                        + "is restricted. Stopping Paper rather than exposing an unguarded server.");
                Bukkit.shutdown();
            } else if (wasRestricted) {
                getLogger().info("Paper is stopping normally during restricted maintenance; "
                        + "keeping the database and activation safeguards in place.");
            }
        }
        if (maintenanceNoticeDisplay != null) {
            maintenanceNoticeDisplay.close();
            maintenanceNoticeDisplay = null;
        }
        if (changeListener != null) {
            changeListener.clearCausalAttribution();
            changeListener = null;
        }
        if (commandExecutor != null) {
            commandExecutor.clearRollbackPreviews();
            commandExecutor = null;
        }
        gui = null;

        // Keep the policy watcher alive through the clean database shutdown.
        // STRICT already performed the expensive comparison while Paper was
        // running; the stopped-writer gate must finish within host stop grace.
        // The worker closes after the short approval/receipt sequence.

        // Stop candidate read-only verification before releasing the maintenance
        // state coordinator; verification must not outlive its state gate.
        if (candidateVerifier != null) {
            try {
                candidateVerifier.close();
            } catch (IllegalStateException exception) {
                getLogger().log(Level.SEVERE, "Candidate verifier could not stop safely. "
                        + "Keep the source and candidate until the process exits.", exception);
                Bukkit.shutdown();
            } finally {
                candidateVerifier = null;
            }
        }

        // Stop VACUUM INTO first, before the preflight reservation guard, the
        // state coordinator, and the live database writer are shut down.
        if (onlineCompaction != null) {
            try {
                onlineCompaction.close();
            } catch (IllegalStateException exception) {
                getLogger().log(Level.SEVERE, "Online SQLite compaction could not be stopped safely. "
                        + "Preserve the active DB, WAL/SHM and unverified candidate.", exception);
                Bukkit.shutdown();
            } finally {
                onlineCompaction = null;
            }
        }
        if (compactionPreflight != null) {
            compactionPreflight.close();
            compactionPreflight = null;
        }
        // Stop full-history read transactions before closing the live SQLite
        // writer, so long readers cannot retain WAL frames at shutdown.
        if (deepDiagnostics != null) {
            deepDiagnostics.close();
            deepDiagnostics = null;
        }
        reclaimableSpaceMonitor = null;
        // Close any read-only diagnostics before the SQLite writer shuts down
        // and checkpoints its WAL. This does not alter the live database.
        if (quickDiagnostics != null) {
            quickDiagnostics.close();
            quickDiagnostics = null;
        }

        // The live SQLite data_version watcher must be checked BEFORE the
        // worker's WAL checkpoint, which can increment data_version without
        // any SQLite row changes. Do not start stopping the writer until this
        // small pre-stop gate is resolved. If it fails, finish ordinary safe
        // shutdown but never issue a compaction activation receipt.
        boolean sourceUnchangedBeforeStop = false;
        if (replacementPolicy != null && (replacementPolicy.hasPendingStrict()
                || replacementPolicy.hasPendingSnapshotProof())) {
            try {
                sourceUnchangedBeforeStop = replacementPolicy
                        .validateBeforeStoppingWriter().get(3L, TimeUnit.SECONDS);
            } catch (Exception changed) {
                getLogger().log(Level.SEVERE, "Source changed since online replacement proof, "
                        + "or a safe pre-stop attestation failed. No file swap is permitted.", changed);
            }
        }

        // Keep maintenance and its blocking state available while Database
        // drains queued operations. They recheck maintenanceBlocksNewJobs()
        // even during shutdown. Clearing it before shutdown could turn a
        // queued, forbidden rollback or undo into an accepted job.
        try {
            if (database != null) {
                DatabaseShutdownSnapshot before = database.shutdown();
                DatabaseHealth after = database.health();
                StorageShutdownReport report = StorageShutdownSupport.finish(
                        before, after, database.completedWrites(),
                        database.workerStopped() ? 0 : database.inFlightWrites(),
                        database.workerStopped(), database.walCheckpointCompleted());
                String summary = "FragGuard storage shutdown: queued=" + report.queuedWritesAtStart()
                        + ", drained=" + report.drainedWrites()
                        + ", remaining=" + report.remainingWrites()
                        + ", remaining operations=" + report.remainingOperations()
                        + ", unconfirmed active operations=" + report.unconfirmedOperations()
                        + ", lost during shutdown=" + report.lostDuringShutdown()
                        + ", unconfirmed in-flight writes=" + report.unconfirmedWrites()
                        + ", total dropped this session=" + report.totalDroppedWrites()
                        + ", worker stopped=" + report.workerStopped()
                        + ", WAL checkpoint=" + (report.walCheckpointCompleted() ? "complete" : "FAILED");
                if (report.clean()) getLogger().info(summary);
                else {
                    getLogger().warning(summary);
                    if (!after.lastError().isBlank())
                        getLogger().warning("FragGuard final storage error: " + after.lastError());
                }
                // Seal the approved database replacement only after *all*
                // accepted writes stop and SQLite confirms a clean checkpoint.
                // This never moves or deletes the original, WAL, SHM or copy.
                if (maintenance != null && replacementPolicy != null
                        && (maintenance.snapshot().phase() == MaintenancePhase.READY_TO_SWAP
                            || replacementPolicy.hasPendingStrict())) {
                    if (!report.clean()) {
                        getLogger().severe("No compaction activation receipt was written: "
                                + "FragGuard shutdown was not clean. Do not swap SQLite files.");
                    } else {
                        try {
                            CompactionReplacementPolicy.StrictFinalProof strictProof = null;
                            if (replacementPolicy.hasPendingStrict()) {
                                // A write accepted/finished after the pre-stop
                                // gate invalidates the online proof even if the
                                // final SQLite checkpoint itself is clean.
                                if (!sourceUnchangedBeforeStop
                                        || report.queuedWritesAtStart() != 0
                                        || report.drainedWrites() != 0) {
                                    throw new IllegalStateException("STRICT pre-stop proof "
                                            + "failed or pending writes drained during shutdown. "
                                            + "Original and candidate preserved, no receipt.");
                                }
                                // Never wait for a full scan here: BisectHosting and
                                // other hosts may hard-kill Paper after ~40 seconds.
                                var approved = replacementPolicy.finalizeStrictAfterShutdown(
                                        report.workerStopped(), report.walCheckpointCompleted(),
                                        report.clean()).get(8L, TimeUnit.SECONDS);
                                strictProof = replacementPolicy.finalizedStrictProof();
                                getLogger().info("STRICT maintenance source still matches its "
                                        + "complete online comparison; policy " + approved.status());
                            }
                            if (maintenance.snapshot().phase() == MaintenancePhase.READY_TO_SWAP) {
                                if (maintenance.snapshot().policy() == MaintenancePolicy.MAINTENANCE_SNAPSHOT) {
                                    // Approved Snapshot loss consent is not permission
                                    // to hash 30GiB while Paper's host is killing it.
                                    if (!sourceUnchangedBeforeStop
                                            || report.queuedWritesAtStart() != 0
                                            || report.drainedWrites() != 0) {
                                        throw new IllegalStateException("Snapshot pre-stop fingerprint "
                                                + "failed or writes drained during shutdown. "
                                                + "Preserve source and candidate: no receipt.");
                                    }
                                    strictProof = replacementPolicy.finalizeSnapshotAfterShutdown(
                                            report.workerStopped(), report.walCheckpointCompleted(),
                                            report.clean()).get(8L, TimeUnit.SECONDS);
                                }
                                if (strictProof == null
                                        || strictProof.policy() != maintenance.snapshot().policy()) {
                                    throw new IllegalStateException("No in-process approved "
                                            + "replacement fingerprint proof for this policy. "
                                            + "Refusing slow shutdown hashing and file replacement");
                                }
                                var receipt = CompactionActivation.recordCleanShutdown(
                                        getDataFolder().toPath(), maintenance.snapshot(), true,
                                        strictProof);
                                getLogger().info("Clean SQLite shutdown receipt: "
                                        + receipt.path().getFileName()
                                        + "; original backup name: "
                                        + receipt.backup().getFileName()
                                        + ". Only now may the operator perform a manual swap.");
                                for (String instruction : CompactionActivation.manualInstructions(
                                        getDataFolder().toPath(), maintenance.snapshot())) {
                                    getLogger().info(instruction);
                                }
                            }
                        } catch (Exception exception) {
                            getLogger().log(Level.SEVERE, "Could not seal safe manual SQLite "
                                    + "activation. The original and its WAL/SHM remain in place. "
                                    + "DO NOT perform a database swap until the issue is resolved.",
                                    exception);
                        }
                    }
                }
            }
        } finally {
            if (replacementPolicy != null) {
                try {
                    replacementPolicy.close();
                } catch (IllegalStateException exception) {
                    getLogger().log(Level.SEVERE, "Could not stop the replacement policy worker; "
                            + "preserve every SQLite/manifest file.", exception);
                    Bukkit.shutdown();
                } finally {
                    replacementPolicy = null;
                }
            }
            if (maintenance != null) {
                try {
                    maintenance.close();
                } catch (IllegalStateException exception) {
                    getLogger().log(Level.SEVERE, "Could not flush maintenance metadata on shutdown", exception);
                }
                // Do not null the reference. If the database worker failed to
                // stop, any lingering queued operation must still see the
                // active maintenance gate until the JVM actually terminates.
            }
        }
    }

    /**
     * Close logins on the main thread before requesting the persisted
     * COUNTDOWN -> ACTIVE transition on the maintenance state worker.
     */
    private void activateMaintenanceAtDeadline(MaintenanceSnapshot expired) {
        MaintenanceAdmissionGate gate = maintenanceAdmissionGate;
        if (gate == null || !gate.beginActivation(expired)) return;
        if (database == null || commandExecutor == null) {
            getLogger().severe("Database or rollback coordinator unavailable at maintenance "
                    + "deadline. Keeping admissions closed; no compaction may begin.");
            gate.activationFailed();
            return;
        }

        // Stop NEW rollback/undo jobs without interrupting already accepted
        // jobs, which can still be applying world edits or confirming audits.
        // The SQLite freeze barrier is not allowed to race those callbacks.
        rollbackJobsQuiescing = true;
        getLogger().info("Maintenance deadline reached. Pausing background retention/reclamation "
                + "and waiting for accepted SQLite batches and rollback audits to finish.");
        long deadlineNanos = System.nanoTime() + TimeUnit.MINUTES.toNanos(5);
        database.pauseBackgroundMutationsForMaintenanceAsync()
                .whenComplete((drained, failure) -> {
            try {
                Bukkit.getScheduler().runTask(this, () -> {
                    if (!isEnabled() || maintenance == null
                            || maintenanceAdmissionGate == null
                            || maintenanceAdmissionGate.cancellationInProgress()
                            || maintenance.snapshot().phase() != MaintenancePhase.COUNTDOWN
                            || !maintenance.snapshot().operationId().equals(expired.operationId())) {
                        return; // A concurrent cancellation won; do not re-freeze.
                    }
                    if (failure != null) {
                        failMaintenanceFreeze(expired, "Background retention/reclamation could not drain "
                                + "before the SQLite freeze. No compaction may begin.");
                        getLogger().log(Level.SEVERE,
                                "Maintenance background-mutation drain failed", failure);
                        return;
                    }
                    waitForRollbackJobsBeforeFreeze(expired, deadlineNanos);
                });
            } catch (RuntimeException schedulingFailure) {
                getLogger().log(Level.SEVERE, "Could not schedule background mutation drain; "
                        + "stopping Paper with source preserved", schedulingFailure);
                Bukkit.shutdown();
            }
        });
    }

    private void waitForRollbackJobsBeforeFreeze(MaintenanceSnapshot expected,
                                                 long deadlineNanos) {
        if (!isEnabled() || maintenance == null || maintenanceAdmissionGate == null
                || maintenanceAdmissionGate.cancellationInProgress()) return;
        MaintenanceSnapshot current = maintenance.snapshot();
        if (current.phase() != MaintenancePhase.COUNTDOWN
                || !current.operationId().equals(expected.operationId())) return;
        if (System.nanoTime() - deadlineNanos >= 0) {
            failMaintenanceFreeze(expected, "Previously accepted rollback/undo jobs did not finish "
                    + "within five minutes. No database freeze or compaction occurred.");
            return;
        }
        if (commandExecutor == null || database == null) {
            failMaintenanceFreeze(expected, "Database or rollback executor became unavailable "
                    + "while waiting to freeze; source remains unchanged.");
            return;
        }
        if (commandExecutor.hasExecutingRollbackJobs()) {
            scheduleRollbackDrainRetry(expected, deadlineNanos);
            return;
        }

        // Ordered SQLite read after previously queued SQL work. Even if a
        // rollback job was created before the expiry and its main-thread
        // callback has not started, its RUNNING row prevents an early seal.
        database.hasActiveRollbackJobsAsync().whenComplete((active, failure) -> {
            try {
                Bukkit.getScheduler().runTask(this, () -> {
                    if (!isEnabled() || maintenanceAdmissionGate == null
                            || maintenanceAdmissionGate.cancellationInProgress()
                            || maintenance.snapshot().phase() != MaintenancePhase.COUNTDOWN
                            || !maintenance.snapshot().operationId().equals(expected.operationId())) return;
                    if (failure != null) {
                        failMaintenanceFreeze(expected, "Could not confirm that existing rollback/undo "
                                + "jobs finished. No database freeze or compaction occurred.");
                        getLogger().log(Level.SEVERE, "Rollback drain probe failed", failure);
                        return;
                    }
                    if (active || commandExecutor == null
                            || commandExecutor.hasExecutingRollbackJobs()) {
                        scheduleRollbackDrainRetry(expected, deadlineNanos);
                        return;
                    }
                    freezeAfterRollbackJobsDrained(expected);
                });
            } catch (RuntimeException schedulingFailure) {
                getLogger().log(Level.SEVERE, "Could not schedule rollback drain completion; "
                        + "stopping Paper to preserve maintenance restrictions", schedulingFailure);
                Bukkit.shutdown();
            }
        });
    }

    private void scheduleRollbackDrainRetry(MaintenanceSnapshot expected, long deadlineNanos) {
        try {
            Bukkit.getScheduler().runTaskLater(this,
                    () -> waitForRollbackJobsBeforeFreeze(expected, deadlineNanos), 20L);
        } catch (RuntimeException schedulingFailure) {
            getLogger().log(Level.SEVERE, "Rollback drain retry could not be scheduled; "
                    + "stopping Paper with source files preserved", schedulingFailure);
            Bukkit.shutdown();
        }
    }

    private void freezeAfterRollbackJobsDrained(MaintenanceSnapshot expected) {
        if (maintenanceAdmissionGate == null || maintenanceAdmissionGate.cancellationInProgress()
                || maintenance.snapshot().phase() != MaintenancePhase.COUNTDOWN
                || !maintenance.snapshot().operationId().equals(expected.operationId())) return;
        // At this point no new job can start, and the worker has confirmed
        // there are no RUNNING/UNDOING jobs. Close SQL admission atomically,
        // finish previously accepted history and other queued SQL, checkpoint
        // WAL and set query_only before persisting ACTIVE.
        database.freezeForMaintenanceAsync().whenComplete((unused, freezeFailure) -> {
            if (freezeFailure != null) {
                failMaintenanceFreeze(expected, "Database write freeze failed; preserve source and "
                        + "review SQLite storage. No candidate may be created.");
                getLogger().log(Level.SEVERE, "FragGuard could not seal the SQLite "
                        + "writer at maintenance activation", freezeFailure);
                return;
            }
            // #205: persist a tiny, fsynced, operation-bound proof of the
            // completed #203/#204 barriers BEFORE declaring maintenance ACTIVE.
            // Never hash multi-GiB history on the Paper thread.
            CompletableFuture.supplyAsync(() -> {
                try {
                    MaintenanceSnapshot current = maintenance.snapshot();
                    if (maintenanceAdmissionGate == null
                            || maintenanceAdmissionGate.cancellationInProgress()
                            || current.phase() != MaintenancePhase.COUNTDOWN
                            || !current.operationId().equals(expected.operationId())) {
                        throw new java.io.IOException("Maintenance cancelled before freeze proof");
                    }
                    return FrozenMaintenanceProof.persist(getDataFolder().toPath(),
                            current, database);
                } catch (java.io.IOException error) {
                    throw new CompletionException(error);
                }
            }).whenComplete((proof, proofFailure) -> {
                if (proofFailure != null) {
                    // An already cancelled operation needs no new activation.
                    // A cancelled operation may have been replaced by another
                    // COUNTDOWN while the common-pool proof task was pending.
                    if (!sameCountdown(expected)) return;
                    failMaintenanceFreeze(expected,
                            "Could not persist durable frozen SQLite proof; "
                                    + "STRICT compaction is blocked.");
                    getLogger().log(Level.SEVERE,
                            "Frozen SQLite baseline proof was not durable for operation "
                                    + expected.operationId(), proofFailure);
                    return;
                }
                if (maintenanceAdmissionGate == null
                        || maintenanceAdmissionGate.cancellationInProgress()
                        || maintenance.snapshot().phase() != MaintenancePhase.COUNTDOWN
                        || !maintenance.snapshot().operationId().equals(expected.operationId())) {
                    return;
                }
                getLogger().info("SQLite freeze and retention drain attested for operation "
                        + proof.operationId() + " (" + proof.diagnostic() + ").");
                maintenance.activateCountdownIfCurrent(expected.operationId())
                        .whenComplete((active, failure) -> {
                if (!isEnabled()) return;
                try {
                    Bukkit.getScheduler().runTask(this, () -> {
                        if (maintenanceAdmissionGate == null || maintenance == null
                                || !expected.operationId().equals(
                                        maintenance.snapshot().operationId())) {
                            return; // Another maintenance operation owns admissions.
                        }
                        if (active != null
                                && !expected.operationId().equals(active.operationId())) {
                            return; // Superseded activation cannot affect new operation.
                        }
                        if (failure != null || active == null
                                || active.phase() != MaintenancePhase.ACTIVE) {
                            // The originating countdown was cancelled, not failed.
                            if (maintenance.snapshot().phase() == MaintenancePhase.CANCELLED) return;
                            getLogger().log(Level.SEVERE, "Could not persist maintenance "
                                    + "activation; admissions remain closed", failure);
                            maintenanceAdmissionGate.activationFailed();
                            return;
                        }
                        if (maintenanceNoticeDisplay != null) maintenanceNoticeDisplay.tick();
                        maintenanceAdmissionGate.activationCompleted();
                        getLogger().warning("FragGuard maintenance ACTIVE. Block logging is "
                                + "PAUSED. World edits during maintenance cannot be rolled back.");
                    });
                } catch (RuntimeException schedulingFailure) {
                    getLogger().log(Level.SEVERE, "Could not complete maintenance activation "
                            + "on the main thread; stopping Paper", schedulingFailure);
                    Bukkit.shutdown();
                }
                });
            });
        });
    }

    private boolean sameCountdown(MaintenanceSnapshot expected) {
        MaintenanceCoordinator coordinator = maintenance;
        if (coordinator == null || expected == null) return false;
        MaintenanceSnapshot current = coordinator.snapshot();
        return current.phase() == MaintenancePhase.COUNTDOWN
                && current.operationId().equals(expected.operationId());
    }

    /** A stale completion cannot fail or kick players in a later countdown. */
    private void failMaintenanceFreeze(MaintenanceSnapshot expected, String reason) {
        if (!sameCountdown(expected)) return;
        // The operator may cancel/schedule another countdown after our check.
        // This transition is conditional INSIDE the state worker.
        maintenance.failCountdownIfCurrent(expected.operationId(), reason)
                .whenComplete((failed, error) -> {
                    if (maintenance == null || failed == null
                            || !maintenance.snapshot().operationId()
                                    .equals(expected.operationId())) {
                        return;
                    }
                    if (error == null && failed.phase() != MaintenancePhase.FAILED) {
                        return; // Cancellation committed before the failure.
                    }
                    getLogger().log(Level.SEVERE, reason, error);
                    try {
                        Bukkit.getScheduler().runTask(this, () -> {
                            if (maintenanceAdmissionGate != null && maintenance != null
                                    && expected.operationId().equals(
                                            maintenance.snapshot().operationId())) {
                                maintenanceAdmissionGate.activationFailed();
                            }
                        });
                    } catch (RuntimeException schedulingFailure) {
                        Bukkit.shutdown(); // Do not expose a frozen ungated server.
                    }
                });
    }

    /** Ensure normal players remain gated until SQLite is writable again. */
    void beginMaintenanceCancellation() {
        if (maintenanceAdmissionGate != null) {
            maintenanceAdmissionGate.beginCancellation(
                    database != null && database.maintenanceWritesPaused());
        }
    }

    void completeMaintenanceCancellation() {
        rollbackJobsQuiescing = false;
        if (maintenanceAdmissionGate != null) maintenanceAdmissionGate.cancellationCompleted();
        // Called only after durable CANCELLED and SQLite query_only=OFF.
        // Catch up at the normal bounded budget, never an unbounded purge.
        runRetentionCleanupOnce();
    }

    void deferRollbackRecovery() {
        deferredRollbackRecovery = true;
    }

    /**
     * Restart only rollback/undo jobs that were withheld during restricted
     * startup. This is deliberately invoked after the durable CANCELLED state,
     * query_only=OFF, restored connection PRAGMAs, and released job admission.
     *
     * The pending flag is consumed once on the main thread. If an intervening
     * maintenance transition restricts the async load callback, the command
     * executor re-arms this flag instead of starting any jobs.
     */
    void resumeDeferredRollbackRecoveryAfterCancellation() {
        if (!deferredRollbackRecovery) return;
        if (database == null || commandExecutor == null
                || maintenance == null || maintenanceBlocksNewJobs()
                || maintenance.snapshot().phase() != MaintenancePhase.CANCELLED
                || database.maintenanceWritesPaused()) {
            return; // Retain the request until maintenance is safely cancelled.
        }
        deferredRollbackRecovery = false;
        getLogger().info("Resuming rollback/undo recovery deferred by maintenance.");
        commandExecutor.resumeInterruptedJobs();
    }

    void failedMaintenanceCancellation() {
        if (maintenanceAdmissionGate != null) maintenanceAdmissionGate.cancellationFailed();
    }

    int getRetentionDays() {
        return Math.max(1, getConfig().getInt("retention-days", 30));
    }

    int getRollbackJobRetentionDays() {
        return Math.max(1, getConfig().getInt("rollback-job-retention-days", getRetentionDays()));
    }

    MaintenanceCoordinator getMaintenanceCoordinator() {
        return maintenance;
    }

    DatabaseQuickDiagnostics getQuickDiagnostics() {
        return quickDiagnostics;
    }

    ReclaimableSpaceMonitor getReclaimableSpaceMonitor() {
        return reclaimableSpaceMonitor;
    }

    DatabaseDeepDiagnostics getDeepDiagnostics() {
        return deepDiagnostics;
    }

    CompactionStoragePreflight getCompactionPreflight() {
        return compactionPreflight;
    }

    OnlineDatabaseCompaction getOnlineCompaction() {
        return onlineCompaction;
    }

    CompactionCandidateVerifier getCandidateVerifier() {
        return candidateVerifier;
    }

    CompactionReplacementPolicy getReplacementPolicy() {
        return replacementPolicy;
    }

    boolean maintenanceBlocksNewJobs() {
        return rollbackJobsQuiescing || (maintenance != null && maintenance.blocksNewJobs());
    }

    FragGuardCommand getFragGuardCommand() {
        return commandExecutor;
    }

    FragGuardGui getFragGuardGui() {
        return gui;
    }

    boolean maintenanceControlsReady() {
        return maintenance != null && maintenanceAdmissionGate != null
                && maintenanceNoticeDisplay != null && isEnabled();
    }

    int getGuiLookupFetchSize() {
        return guiLookupFetchSize;
    }

    static int resolveGuiLookupFetchSize(int configuredValue) {
        return configuredValue > 0 ? configuredValue : DEFAULT_GUI_LOOKUP_FETCH_SIZE;
    }

    private void scheduleCleanup() {
        long ticks = TimeUnit.MINUTES.toSeconds(Math.max(1, getConfig().getInt("cleanup-interval-minutes", 60))) * 20L;
        Bukkit.getScheduler().runTaskTimerAsynchronously(
                this, this::runRetentionCleanupOnce, 20L * 30L, ticks);
    }

    /** One bounded pass at a time, including timer ticks and post-cancel catch-up. */
    void runRetentionCleanupOnce() {
        Database db = database;
        if (db == null || !isEnabled()) return;
        if (db.backgroundMutationsPausedForMaintenance() || maintenanceBlocksNewJobs()) {
            noteRetentionCleanupPaused();
            return;
        }
        if (!retentionCleanupDispatchActive.compareAndSet(false, true)) return;
        db.cleanupOldRecordsAsync(getRetentionDays(), getRollbackJobRetentionDays())
                .whenComplete((deleted, throwable) -> {
                    retentionCleanupDispatchActive.set(false);
                    if (deleted != null && deleted.pausedForMaintenance()) {
                        noteRetentionCleanupPaused();
                        return;
                    }
                    if (throwable != null) {
                        getLogger().log(Level.WARNING, "FragGuard retention cleanup failed.", throwable);
                        return;
                    }
                    retentionCleanupPauseNotified.set(false);
                    if (deleted.blockRecordsDeleted() > 0)
                        getLogger().info("Deleted " + deleted.blockRecordsDeleted() + " old block log records.");
                    if (deleted.rollbackJobsDeleted() > 0)
                        getLogger().info("Deleted " + deleted.rollbackJobsDeleted() + " expired rollback job(s) and their saved snapshots.");
                    if (deleted.batchesProcessed() > 0
                            && (deleted.blockRecordsDeleted() > 0 || deleted.rollbackJobsDeleted() > 0))
                        getLogger().info("Retention cleanup committed " + deleted.batchesProcessed()
                                + " bounded batch(es); writes were allowed between commits.");
                    if (deleted.batchBudgetReached())
                        getLogger().info("Retention cleanup hit its per-run batch budget; "
                                + "remaining expired rows, if any, will be handled by the next run.");
                    // Optional reclamation is separately admitted by the database
                    // gate, so a countdown/async callback cannot bypass the pause.
                    if ((deleted.blockRecordsDeleted() > 0 || deleted.rollbackJobsDeleted() > 0)
                            && db.incrementalReclaimEnabled()
                            && !db.backgroundMutationsPausedForMaintenance()
                            && !maintenanceBlocksNewJobs()) {
                        db.incrementalReclaimAfterCleanupAsync().whenComplete((result, failure) -> {
                            if (failure != null) {
                                if (db.backgroundMutationsPausedForMaintenance()
                                        || failure instanceof Database.MaintenanceBackgroundPausedException
                                        || failure.getCause() instanceof Database.MaintenanceBackgroundPausedException) {
                                    noteRetentionCleanupPaused();
                                    return;
                                }
                                getLogger().log(Level.WARNING,
                                        "Optional bounded incremental SQLite reclamation skipped/failed; "
                                        + "retention cleanup and live logging continue.", failure);
                            } else if (!result.compatible()) {
                                if (!incrementalIncompatibleNotified) {
                                    incrementalIncompatibleNotified = true;
                                    getLogger().info(result.summary());
                                }
                            } else if (result.requestedPages() > 0) {
                                getLogger().info(result.summary());
                            }
                        });
                    }
                });
    }

    private void noteRetentionCleanupPaused() {
        if (retentionCleanupPauseNotified.compareAndSet(false, true)) {
            getLogger().info("Retention cleanup PAUSED FOR MAINTENANCE; no background "
                    + "database deletions or reclamation will be scheduled until safe resume.");
        }
    }

    private void scheduleReclaimableSpaceMonitor() {
        ReclaimableSpaceMonitor monitor = reclaimableSpaceMonitor;
        if (monitor == null || !monitor.settings().enabled()) return;
        long ticks = TimeUnit.MINUTES.toSeconds(monitor.settings().intervalMinutes()) * 20L;
        int historyRetentionDays = getRetentionDays();
        int jobRetentionDays = getRollbackJobRetentionDays();
        int queryTimeoutSeconds = Math.max(1, getConfig().getInt(
                "diagnostics-query-timeout-seconds", 15));
        // The timer merely queues a bounded, read-only overview on the existing
        // diagnostics worker. No SQLite or filesystem scan runs on the tick path.
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            if (!isEnabled() || quickDiagnostics == null || database == null) return;
            quickDiagnostics.run(DatabaseQuickDiagnostics.Mode.OVERVIEW,
                    database.health(), historyRetentionDays, jobRetentionDays,
                    queryTimeoutSeconds)
                    .whenComplete((report, failure) -> {
                        // An admin may already be running diagnostics. Skip that
                        // interval rather than competing for SQLite read capacity.
                        if (failure != null || reclaimableSpaceMonitor != monitor || !isEnabled()) return;
                        if (!monitor.accept(report, System.currentTimeMillis(), true)) return;
                        String message = "FragGuard maintenance recommendation: "
                                + monitor.latest().warning();
                        try {
                            Bukkit.getScheduler().runTask(this, () -> {
                                if (!isEnabled() || reclaimableSpaceMonitor != monitor) return;
                                getLogger().warning(message);
                                for (Player player : Bukkit.getOnlinePlayers()) {
                                    if (player.isOp() && player.hasPermission("fragguard.admin")) {
                                        player.sendMessage(Component.text("[FragGuard] " + message,
                                                NamedTextColor.YELLOW));
                                    }
                                }
                            });
                        } catch (IllegalStateException ignored) {
                            // The plugin was disabled between scan completion and dispatch.
                        }
                    });
        }, 20L * 60L, ticks);
    }

    private void scheduleStorageHealthMonitor() {
        long ticks = Math.max(1L, getConfig().getLong("database-health-check-interval-seconds", 5L)) * 20L;
        Bukkit.getScheduler().runTaskTimer(this, this::checkStorageHealth, ticks, ticks);
    }

    private void checkStorageHealth() {
        if (database == null) return;
        DatabaseHealth health = database.health();
        if (!health.degraded()) {
            storageWarningActive = false;
            return;
        }
        long now = System.currentTimeMillis();
        long repeat = TimeUnit.SECONDS.toMillis(Math.max(5L,
                getConfig().getLong("database-operator-warning-interval-seconds", 60L)));
        if (!StorageWarningThrottle.shouldWarn(storageWarningActive, now, lastStorageWarningAt, repeat)) return;
        storageWarningActive = true;
        lastStorageWarningAt = now;
        String availability = health.storageAvailable() ? "DEGRADED" : "UNAVAILABLE";
        String message = "FragGuard logging is " + availability + ". Dropped writes: " + health.droppedWrites()
                + "; queued writes: " + health.queuedWrites() + "/" + health.writeCapacity()
                + (health.lastError().isBlank() ? "." : "; last error: " + health.lastError());
        getLogger().warning(message);
        for (Player player : Bukkit.getOnlinePlayers())
            if (player.isOp() || player.hasPermission("fragguard.admin"))
                player.sendMessage(Component.text("[FragGuard] " + message, NamedTextColor.RED));
    }
}
