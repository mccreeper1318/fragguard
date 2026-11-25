package org.pinnaclesmp.fragguard;

import org.bukkit.command.Command;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** The actual /fg dispatcher must not bypass permission, phase or consent gates. */
class MaintenanceCommandIntegrationTest {
    private static final UUID ADMIN = UUID.fromString("cbd3f68b-9df5-4b8e-b30f-18f4fc3f1fac");
    private static final long NOW = System.currentTimeMillis();

    @Test
    void nonAdminCannotStartOrApproveMaintenance() {
        var test = setup(false);
        test.command.onCommand(test.player, mock(Command.class), "fg",
                new String[]{"maintenance", "start"});
        verifyNoInteractions(test.coordinator, test.compaction);
    }

    @Test
    void doubleStartCannotQueueTwoDurableCountDowns() {
        var test = setup(true);
        CompletableFuture<MaintenanceSnapshot> future = new CompletableFuture<>();
        when(test.coordinator.schedule(any(UUID.class), anyLong(), anyLong(), eq(MaintenancePolicy.STRICT)))
                .thenReturn(future);
        execute(test,"start");
        execute(test,"start");
        verify(test.coordinator, times(1)).schedule(eq(ADMIN), anyLong(), anyLong(),
                eq(MaintenancePolicy.STRICT));
        assertFalse(future.isDone());
    }

    @Test
    void snapshotCannotBeScheduledWithoutExplicitOptIn() {
        var test = setup(true);
        execute(test, "start", "snapshot");
        verify(test.coordinator, never()).schedule(any(), anyLong(), anyLong(), any());
    }

    @Test
    void snapshotReplacementPolicyNeedsIndependentSecondConsent() {
        var test = setup(true);
        MaintenanceSnapshot state = MaintenanceSnapshot.scheduled(ADMIN, NOW, NOW + 600_000,
                MaintenancePolicy.MAINTENANCE_SNAPSHOT)
                .withPhase(MaintenancePhase.ACTIVE, "active")
                .withCandidate("fragguard-compact-test.db", "copying")
                .withCutoff(NOW + 1, "copy complete")
                .withPhase(MaintenancePhase.VERIFYING, "checking")
                .withManifest("fragguard-compact-test.manifest", "verified");
        when(test.coordinator.snapshot()).thenReturn(state);
        CompactionReplacementPolicy policy = mock(CompactionReplacementPolicy.class);
        when(test.plugin.getReplacementPolicy()).thenReturn(policy);
        execute(test,"approve");
        verifyNoInteractions(policy);
        var pending = new CompletableFuture<CompactionReplacementPolicy.Decision>();
        when(policy.prepare(eq(ADMIN), eq(CompactionReplacementPolicy.SNAPSHOT_ACKNOWLEDGEMENT)))
                .thenReturn(pending);
        execute(test,"approve", "snapshot-acknowledge");
        verify(policy, times(1)).prepare(eq(ADMIN),
                eq(CompactionReplacementPolicy.SNAPSHOT_ACKNOWLEDGEMENT));
    }

    @Test
    void noCompactionCanStartBeforeMaintenanceIsActive() {
        var test = setup(true);
        execute(test,"compact");
        verify(test.compaction, never()).start(any());
    }

    @Test
    void failedMaintenanceCanBeCancelledAfterWorkersStop() {
        var test = setup(true);
        MaintenanceSnapshot failed = MaintenanceSnapshot.scheduled(ADMIN, NOW, NOW + 600_000,
                        MaintenancePolicy.STRICT)
                .withPhase(MaintenancePhase.ACTIVE, "active")
                .withPhase(MaintenancePhase.FAILED, "compaction failure");
        when(test.coordinator.snapshot()).thenReturn(failed);
        when(test.compaction.progress()).thenReturn(new OnlineDatabaseCompaction.Progress(
                false, "failed", "", 0, 0, 0, "no running worker"));
        when(test.coordinator.cancel(anyString())).thenReturn(new CompletableFuture<>());
        execute(test,"cancel");
        verify(test.coordinator, times(1)).cancel(contains("Cancelled explicitly"));
    }

    @Test
    void failedMaintenanceCannotCancelWhileWorkerStillActive() {
        var test = setup(true);
        MaintenanceSnapshot failed = MaintenanceSnapshot.scheduled(ADMIN, NOW, NOW + 600_000,
                        MaintenancePolicy.STRICT)
                .withPhase(MaintenancePhase.ACTIVE, "active")
                .withPhase(MaintenancePhase.FAILED, "compaction failure");
        when(test.coordinator.snapshot()).thenReturn(failed);
        when(test.compaction.progress()).thenReturn(new OnlineDatabaseCompaction.Progress(
                true, "cancelling", "", 0, 0, 0, "still terminating"));
        execute(test,"cancel");
        verify(test.coordinator, never()).cancel(anyString());
    }

    @Test
    void approvedStrictInstructionsCanBePreviewedWithoutSwappingFiles() {
        var test = setup(true);
        MaintenanceSnapshot verifiedStrict = MaintenanceSnapshot.scheduled(ADMIN, NOW, NOW + 600_000,
                        MaintenancePolicy.STRICT)
                .withPhase(MaintenancePhase.ACTIVE, "active")
                .withCandidate("fragguard-compact-test.db", "copying")
                .withCutoff(NOW + 1, "copy complete")
                .withPhase(MaintenancePhase.VERIFYING, "checking")
                .withManifest("fragguard-compact-test.manifest", "verified");
        when(test.coordinator.snapshot()).thenReturn(verifiedStrict);
        execute(test,"instructions");
        verify(test.player, atLeastOnce()).sendMessage(any(net.kyori.adventure.text.Component.class));
    }

    @Test
    void deferredRollbackRecoveryWaitsForSafeCancellationAndOnlyStartsOnce() throws Exception {
        FragGuardPlugin plugin = mock(FragGuardPlugin.class, CALLS_REAL_METHODS);
        MaintenanceCoordinator coordinator = mock(MaintenanceCoordinator.class);
        Database db = mock(Database.class);
        FragGuardCommand command = mock(FragGuardCommand.class);
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getLogger("DeferredRecoveryTest"));
        var coordinatorField = FragGuardPlugin.class.getDeclaredField("maintenance");
        coordinatorField.setAccessible(true);
        coordinatorField.set(plugin, coordinator);
        var dbField = FragGuardPlugin.class.getDeclaredField("database");
        dbField.setAccessible(true);
        dbField.set(plugin, db);
        var commandField = FragGuardPlugin.class.getDeclaredField("commandExecutor");
        commandField.setAccessible(true);
        commandField.set(plugin, command);

        var scheduled = MaintenanceSnapshot.scheduled(ADMIN, NOW, NOW + 600_000,
                MaintenancePolicy.STRICT);
        when(coordinator.snapshot()).thenReturn(
                scheduled.withPhase(MaintenancePhase.FAILED, "recovery pending"));
        plugin.deferRollbackRecovery();
        plugin.resumeDeferredRollbackRecoveryAfterCancellation();
        verify(command, never()).resumeInterruptedJobs();

        when(coordinator.snapshot()).thenReturn(
                scheduled.withPhase(MaintenancePhase.CANCELLED, "cancelled"));
        when(db.maintenanceWritesPaused()).thenReturn(true);
        plugin.resumeDeferredRollbackRecoveryAfterCancellation();
        verify(command, never()).resumeInterruptedJobs();

        when(db.maintenanceWritesPaused()).thenReturn(false);
        plugin.resumeDeferredRollbackRecoveryAfterCancellation();
        plugin.resumeDeferredRollbackRecoveryAfterCancellation();
        verify(command, times(1)).resumeInterruptedJobs();
    }

    private static void execute(Fixture f, String... args) {
        String[] full = new String[args.length + 1];
        full[0] = "maintenance";
        System.arraycopy(args, 0, full, 1, args.length);
        f.command.onCommand(f.player, mock(Command.class), "fg", full);
    }

    private static Fixture setup(boolean admin) {
        FragGuardPlugin plugin = mock(FragGuardPlugin.class);
        MaintenanceCoordinator coordinator = mock(MaintenanceCoordinator.class);
        OnlineDatabaseCompaction compaction = mock(OnlineDatabaseCompaction.class);
        Player player = mock(Player.class);
        when(player.isOp()).thenReturn(admin);
        when(player.hasPermission("fragguard.admin")).thenReturn(admin);
        when(player.getUniqueId()).thenReturn(ADMIN);
        when(plugin.getMaintenanceCoordinator()).thenReturn(coordinator);
        when(plugin.getOnlineCompaction()).thenReturn(compaction);
        when(plugin.maintenanceControlsReady()).thenReturn(true);
        when(plugin.getDataFolder()).thenReturn(new java.io.File("."));
        YamlConfiguration config = new YamlConfiguration();
        config.set("maintenance-countdown-seconds", 600);
        when(plugin.getConfig()).thenReturn(config);
        when(coordinator.snapshot()).thenReturn(MaintenanceSnapshot.idle());
        return new Fixture(plugin, coordinator, compaction, player,
                new FragGuardCommand(plugin, mock(Database.class)));
    }

    private record Fixture(FragGuardPlugin plugin, MaintenanceCoordinator coordinator,
                           OnlineDatabaseCompaction compaction, Player player,
                           FragGuardCommand command) { }
}
