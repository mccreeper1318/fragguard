package org.pinnaclesmp.fragguard;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RollbackChunkExecutionTest {
    @Test
    void loadsExistingChunksAsynchronouslyWithoutGeneratingTerrain() throws Exception {
        FragGuardPlugin plugin = mock(FragGuardPlugin.class);
        FragGuardCommand command = new FragGuardCommand(plugin, mock(Database.class));
        RollbackJob job = job(41L);
        markExecuting(command, job);

        World world = mock(World.class);
        AtomicBoolean loaded = new AtomicBoolean();
        CompletableFuture<Chunk> pending = new CompletableFuture<>();
        when(world.isChunkLoaded(1, -1)).thenAnswer(ignored -> loaded.get());
        when(world.getChunkAtAsync(1, -1, false)).thenReturn(pending);
        Runnable afterLoaded = mock(Runnable.class);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);

            ensureChunkLoaded(command, job,
                    new RollbackChunkPlan.ChunkKey("world", 1, -1), afterLoaded);

            verify(world).getChunkAtAsync(1, -1, false);
            verify(afterLoaded, never()).run();
            verify(world, never()).addPluginChunkTicket(1, -1, plugin);

            loaded.set(true);
            pending.complete(mock(Chunk.class));

            verify(world).addPluginChunkTicket(1, -1, plugin);
            verify(afterLoaded).run();

            releaseChunk(command, job.id());
            verify(world).removePluginChunkTicket(1, -1, plugin);
        }
    }

    @Test
    void sharesChunkTicketsUntilEveryJobReleasesTheSameChunk() throws Exception {
        FragGuardPlugin plugin = mock(FragGuardPlugin.class);
        FragGuardCommand command = new FragGuardCommand(plugin, mock(Database.class));
        RollbackJob first = job(41L);
        RollbackJob second = job(42L);
        markExecuting(command, first);
        markExecuting(command, second);

        World world = mock(World.class);
        when(world.isChunkLoaded(2, 3)).thenReturn(true);
        RollbackChunkPlan.ChunkKey chunk = new RollbackChunkPlan.ChunkKey("world", 2, 3);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);

            ensureChunkLoaded(command, first, chunk, () -> { });
            ensureChunkLoaded(command, second, chunk, () -> { });

            verify(world, times(1)).addPluginChunkTicket(2, 3, plugin);
            releaseChunk(command, first.id());
            verify(world, never()).removePluginChunkTicket(2, 3, plugin);

            releaseChunk(command, second.id());
            verify(world).removePluginChunkTicket(2, 3, plugin);
        }
    }

    @Test
    void pausesBelowTheConfiguredTpsAndResumesAfterRecovery() throws Exception {
        FragGuardPlugin plugin = mock(FragGuardPlugin.class);
        Server server = mock(Server.class);
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("rollback-minimum-tps", 18.0);
        when(plugin.getConfig()).thenReturn(configuration);
        when(plugin.getServer()).thenReturn(server);
        when(server.getTPS()).thenReturn(new double[]{17.0}, new double[]{19.5});

        FragGuardCommand command = new FragGuardCommand(plugin, mock(Database.class));
        RollbackJob job = job(41L);
        markExecuting(command, job);
        Player operator = mock(Player.class);
        when(operator.isOnline()).thenReturn(true);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        Runnable resume = mock(Runnable.class);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);

            assertTrue(pauseForLowTps(command, job, operator, resume));
            verify(scheduler).runTaskLater(eq(plugin), any(Runnable.class), eq(20L));
            verify(operator).sendMessage(contains("paused"));

            assertFalse(pauseForLowTps(command, job, operator, resume));
            verify(operator).sendMessage(contains("resumed"));
        }
    }

    @Test
    void reportsRollbackPreviewSnapshotLimitsWithoutLoggingAStorageFailure() throws Exception {
        FragGuardPlugin plugin = mock(FragGuardPlugin.class);
        FragGuardCommand command = new FragGuardCommand(plugin, mock(Database.class));
        Player operator = mock(Player.class);
        Method reportFailure = FragGuardCommand.class.getDeclaredMethod(
                "reportRollbackQueryFailure", Player.class, Throwable.class);
        reportFailure.setAccessible(true);

        reportFailure.invoke(command, operator,
                new Database.RollbackSnapshotLimitExceededException(64L));

        verify(operator).sendMessage(contains("64 bytes"));
        verify(operator).sendMessage(contains("rollback-max-snapshot-bytes-per-command"));
        verify(plugin, never()).getLogger();
    }

    @Test
    void appliesConfiguredSnapshotByteBudgetToRollbackAndUndoJobLoads() throws Exception {
        FragGuardPlugin plugin = mock(FragGuardPlugin.class);
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("rollback-max-snapshot-bytes-per-command", 123L);
        when(plugin.getConfig()).thenReturn(configuration);
        Database database = mock(Database.class);
        when(database.loadRollbackChangesAsync(eq(41L), eq(false), eq(123L)))
                .thenReturn(new CompletableFuture<>());
        when(database.loadRollbackChangesAsync(eq(42L), eq(true), eq(123L)))
                .thenReturn(new CompletableFuture<>());
        FragGuardCommand command = new FragGuardCommand(plugin, database);
        Method executeJob = FragGuardCommand.class.getDeclaredMethod(
                "executeJob", RollbackJob.class, Player.class, boolean.class);
        executeJob.setAccessible(true);

        executeJob.invoke(command, job(41L), null, false);
        executeJob.invoke(command, job(42L), null, true);

        verify(database).loadRollbackChangesAsync(41L, false, 123L);
        verify(database).loadRollbackChangesAsync(42L, true, 123L);
    }

    @Test
    void releasesCompletedChunkBeforeLowTpsPausesTheNextChunk() throws Exception {
        assertChunkTicketBehaviorDuringLowTpsPause(true);
    }

    @Test
    void retainsActiveChunkWhenLowTpsPausesMoreChangesInTheSameChunk() throws Exception {
        assertChunkTicketBehaviorDuringLowTpsPause(false);
    }

    @Test
    void persistsOnlyTheActiveRollbackSliceBeforeLowTpsDefersLaterCandidates() throws Exception {
        assertOnlyTheActiveSliceIsAudited(false, true, false);
    }

    @Test
    void persistsOnlyTheActiveUndoSliceBeforeLowTpsDefersLaterCandidates() throws Exception {
        assertOnlyTheActiveSliceIsAudited(true, true, false);
    }

    @Test
    void persistsOnlyTheActiveRollbackSliceBeforeTheTickBudgetDefersLaterCandidates() throws Exception {
        assertOnlyTheActiveSliceIsAudited(false, false, false);
    }

    @Test
    void persistsRollbackPhysicsCorrectionsBeforeLowTpsDefersTheNextSlice() throws Exception {
        assertOnlyTheActiveSliceIsAudited(false, true, true);
    }

    @Test
    void persistsUndoPhysicsCorrectionsBeforeLowTpsDefersTheNextSlice() throws Exception {
        assertOnlyTheActiveSliceIsAudited(true, true, true);
    }

    @Test
    void persistsRollbackPhysicsCorrectionsBeforeTheTickBudgetDefersTheNextSlice() throws Exception {
        assertOnlyTheActiveSliceIsAudited(false, false, true);
    }

    @Test
    void restoresCompatibleRollbackInventoryAfterPhysicsNormalizesChestState() throws Exception {
        assertCompatiblePhysicsCorrectionRestoresInventory(false);
    }

    @Test
    void restoresCompatibleUndoInventoryAfterPhysicsNormalizesChestState() throws Exception {
        assertCompatiblePhysicsCorrectionRestoresInventory(true);
    }

    @Test
    @SuppressWarnings("unchecked")
    void undoRejectsAPlayerEditMadeAfterRollback() throws Exception {
        FragGuardPlugin plugin = mock(FragGuardPlugin.class);
        Server server = mock(Server.class);
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("rollback-minimum-tps", 0.0);
        configuration.set("rollback-max-millis-per-tick", 50.0);
        when(plugin.getConfig()).thenReturn(configuration);
        when(plugin.getServer()).thenReturn(server);
        when(server.getTPS()).thenReturn(new double[]{20.0});
        when(server.getCurrentTick()).thenReturn(100);

        Database database = mock(Database.class);
        when(database.markUndoBatchAppliedAsync(eq(41L), anyList()))
                .thenReturn(CompletableFuture.completedFuture(null));
        FragGuardCommand command = new FragGuardCommand(plugin, database);
        RollbackJob job = job(41L);
        markExecuting(command, job);

        World world = mock(World.class);
        Block block = mock(Block.class);
        BlockData playerEdit = mock(BlockData.class);
        BlockData undoTarget = mock(BlockData.class);
        when(playerEdit.getAsString()).thenReturn("minecraft:gold_block");
        when(undoTarget.getAsString()).thenReturn("minecraft:dirt");
        when(block.getBlockData()).thenReturn(playerEdit);
        when(world.getBlockAt(4, 64, 4)).thenReturn(block);
        RollbackJobChange change = new RollbackJobChange(
                0, "world", 4, 64, 4,
                "minecraft:dirt", "minecraft:stone", "minecraft:dirt",
                true, true, false, false,
                null, null, null,
                "minecraft:stone", null, null, false
        );

        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(invocation -> {
            invocation.getArgument(1, Runnable.class).run();
            return mock(BukkitTask.class);
        });
        when(scheduler.runTaskLater(eq(plugin), any(Runnable.class), eq(1L)))
                .thenReturn(mock(BukkitTask.class));

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
            bukkit.when(() -> Bukkit.createBlockData("minecraft:dirt")).thenReturn(undoTarget);
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);

            applyPreparedBatch(command, job, List.of(change), true);

            verify(database, never()).insertPendingRollbackAuditsAsync(anyLong(), eq(true), anyList());
            ArgumentCaptor<List<RollbackStepResult>> captured = ArgumentCaptor.forClass(List.class);
            verify(database).markUndoBatchAppliedAsync(eq(41L), captured.capture());
            assertEquals(1, captured.getValue().size());
            assertTrue(captured.getValue().get(0).conflicted(),
                    "undo must leave a post-rollback player edit untouched and retryable");
        }
    }

    private void assertCompatiblePhysicsCorrectionRestoresInventory(boolean undo) throws Exception {
        FragGuardPlugin plugin = mock(FragGuardPlugin.class);
        Server server = mock(Server.class);
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("rollback-minimum-tps", 0.0);
        configuration.set("rollback-max-millis-per-tick", 50.0);
        configuration.set("apply-physics-during-rollback", true);
        when(plugin.getConfig()).thenReturn(configuration);
        when(plugin.getServer()).thenReturn(server);
        when(server.getTPS()).thenReturn(new double[]{20.0});
        when(server.getCurrentTick()).thenReturn(100);

        Database database = mock(Database.class);
        when(database.prepareRollbackBatchAsync(eq(41L), anyList()))
                .thenReturn(CompletableFuture.completedFuture(null));
        List<List<BlockChange>> pendingAudits = new ArrayList<>();
        AtomicLong auditIds = new AtomicLong();
        when(database.insertPendingRollbackAuditsAsync(eq(41L), eq(undo), anyList())).thenAnswer(invocation -> {
            List<RollbackPendingAudit> pending = invocation.getArgument(2);
            List<BlockChange> audits = pending.stream().map(RollbackPendingAudit::change).toList();
            pendingAudits.add(audits);
            return CompletableFuture.completedFuture(audits.stream()
                    .map(ignored -> auditIds.incrementAndGet())
                    .toList());
        });

        FragGuardCommand command = new FragGuardCommand(plugin, database);
        RollbackJob job = job(41L);
        markExecuting(command, job);
        Player operator = mock(Player.class);
        when(operator.isOnline()).thenReturn(true);
        World world = mock(World.class);
        when(world.getName()).thenReturn("world");

        Block block = mock(Block.class);
        BlockData before = mock(BlockData.class);
        BlockData desired = mock(BlockData.class);
        BlockData corrected = mock(BlockData.class);
        String desiredData = "minecraft:chest[facing=north,type=single,waterlogged=false]";
        String correctedData = "minecraft:chest[facing=north,type=left,waterlogged=false]";
        when(before.getAsString()).thenReturn("minecraft:air");
        when(desired.getAsString()).thenReturn(desiredData);
        when(corrected.getAsString()).thenReturn(correctedData);
        AtomicReference<BlockData> actual = new AtomicReference<>(before);
        when(block.getBlockData()).thenAnswer(ignored -> actual.get());
        when(block.getWorld()).thenReturn(world);
        when(block.getX()).thenReturn(2);
        when(block.getY()).thenReturn(64);
        when(block.getZ()).thenReturn(3);
        doAnswer(ignored -> {
            actual.set(corrected);
            return null;
        }).when(block).setBlockData(desired, true);

        byte[] snapshot = new byte[]{9, 4, 2, 1};
        RollbackJobChange change = new RollbackJobChange(0, "world", 2, 64, 3,
                "minecraft:air", desiredData, false, false, false);
        Object candidate = preparedChange(change, block, desired, "minecraft:air", null, snapshot);
        AtomicBoolean entityRestored = new AtomicBoolean();

        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(invocation -> {
            invocation.getArgument(1, Runnable.class).run();
            return mock(BukkitTask.class);
        });
        Runnable completed = mock(Runnable.class);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedStatic<BlockEntitySnapshot> snapshots = mockStatic(BlockEntitySnapshot.class)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            snapshots.when(() -> BlockEntitySnapshot.capture(block))
                    .thenAnswer(ignored -> entityRestored.get() ? snapshot : null);
            snapshots.when(() -> BlockEntitySnapshot.restoreIfCompatible(block, snapshot))
                    .thenAnswer(ignored -> {
                        entityRestored.set(true);
                        return true;
                    });

            persistAndApplyCandidates(command, job, operator, List.of(candidate), undo, completed);

            assertTrue(entityRestored.get(), "compatible chest contents must survive physics normalization");
            assertEquals(1, pendingAudits.size());
            assertEquals(desiredData, pendingAudits.get(0).get(0).afterData());
            verify(completed).run();
        }
    }

    private void assertChunkTicketBehaviorDuringLowTpsPause(boolean crossesChunkBoundary) throws Exception {
        FragGuardPlugin plugin = mock(FragGuardPlugin.class);
        Server server = mock(Server.class);
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("rollback-minimum-tps", 18.0);
        when(plugin.getConfig()).thenReturn(configuration);
        when(plugin.getServer()).thenReturn(server);
        when(server.getTPS()).thenReturn(new double[]{17.0});

        FragGuardCommand command = new FragGuardCommand(plugin, mock(Database.class));
        RollbackJob job = job(41L);
        markExecuting(command, job);
        Player operator = mock(Player.class);
        when(operator.isOnline()).thenReturn(true);
        World world = mock(World.class);
        when(world.isChunkLoaded(0, 0)).thenReturn(true);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        List<RollbackJobChange> changes = List.of(
                new RollbackJobChange(0, "world", 0, 64, 0,
                        "minecraft:stone", "minecraft:dirt", false, false, false),
                new RollbackJobChange(1, "world", crossesChunkBoundary ? 16 : 1, 64, 0,
                        "minecraft:stone", "minecraft:dirt", false, false, false));

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);

            ensureChunkLoaded(command, job, new RollbackChunkPlan.ChunkKey("world", 0, 0), () -> { });
            runJobBatch(command, job, operator, changes, 1);

            if (crossesChunkBoundary) {
                verify(world).removePluginChunkTicket(0, 0, plugin);
                verify(world, never()).addPluginChunkTicket(1, 0, plugin);
            } else {
                verify(world, never()).removePluginChunkTicket(0, 0, plugin);
            }
            verify(scheduler).runTaskLater(eq(plugin), any(Runnable.class), eq(20L));
        }
    }

    private void assertOnlyTheActiveSliceIsAudited(boolean undo, boolean lowTpsPause,
                                                    boolean applyPhysics) throws Exception {
        FragGuardPlugin plugin = mock(FragGuardPlugin.class);
        Server server = mock(Server.class);
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("rollback-minimum-tps", 18.0);
        configuration.set("rollback-max-millis-per-tick", 50.0);
        configuration.set("apply-physics-during-rollback", applyPhysics);
        when(plugin.getConfig()).thenReturn(configuration);
        when(plugin.getServer()).thenReturn(server);
        AtomicReference<Double> currentTps = new AtomicReference<>(20.0);
        AtomicInteger currentTick = new AtomicInteger(100);
        when(server.getTPS()).thenAnswer(ignored -> new double[]{currentTps.get()});
        when(server.getCurrentTick()).thenAnswer(ignored -> currentTick.get());

        Database database = mock(Database.class);
        when(database.prepareRollbackBatchAsync(eq(41L), anyList()))
                .thenReturn(CompletableFuture.completedFuture(null));
        List<List<BlockChange>> pendingAudits = new ArrayList<>();
        AtomicLong auditIds = new AtomicLong();
        when(database.insertPendingRollbackAuditsAsync(eq(41L), eq(undo), anyList())).thenAnswer(invocation -> {
            List<RollbackPendingAudit> pending = invocation.getArgument(2);
            List<BlockChange> audits = pending.stream().map(RollbackPendingAudit::change).toList();
            pendingAudits.add(audits);
            return CompletableFuture.completedFuture(audits.stream()
                    .map(ignored -> auditIds.incrementAndGet())
                    .toList());
        });

        FragGuardCommand command = new FragGuardCommand(plugin, database);
        RollbackJob job = job(41L);
        markExecuting(command, job);
        Player operator = mock(Player.class);
        when(operator.isOnline()).thenReturn(true);
        World world = mock(World.class);
        when(world.getName()).thenReturn("world");
        List<Object> candidates = new ArrayList<>();
        List<AtomicReference<BlockData>> actualStates = new ArrayList<>();
        String initialData = undo ? "minecraft:dirt" : "minecraft:stone";
        String desiredData = undo ? "minecraft:stone" : "minecraft:dirt";
        for (int index = 0; index < 17; index++) {
            Block block = mock(Block.class);
            BlockData before = mock(BlockData.class);
            BlockData desired = mock(BlockData.class);
            BlockData corrected = mock(BlockData.class);
            when(before.getAsString()).thenReturn(initialData);
            when(desired.getAsString()).thenReturn(desiredData);
            when(corrected.getAsString()).thenReturn("minecraft:air");
            AtomicReference<BlockData> actual = new AtomicReference<>(before);
            actualStates.add(actual);
            when(block.getBlockData()).thenAnswer(ignored -> actual.get());
            when(block.getWorld()).thenReturn(world);
            when(block.getX()).thenReturn(index);
            when(block.getY()).thenReturn(64);
            when(block.getZ()).thenReturn(0);
            int changeIndex = index;
            doAnswer(invocation -> {
                actual.set(applyPhysics && changeIndex == 0
                        ? corrected
                        : invocation.getArgument(0));
                if (changeIndex == 15) {
                    if (lowTpsPause) {
                        currentTps.set(17.0);
                    } else {
                        long exhaustedAt = System.nanoTime() + 60_000_000L;
                        while (System.nanoTime() < exhaustedAt) {
                            Thread.onSpinWait();
                        }
                    }
                }
                return null;
            }).when(block).setBlockData(eq(desired), eq(applyPhysics));

            RollbackJobChange change = new RollbackJobChange(index, "world", index, 64, 0,
                    "minecraft:stone", "minecraft:dirt", false, false, false);
            candidates.add(preparedChange(change, block, desired, initialData));
        }

        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(invocation -> {
            invocation.getArgument(1, Runnable.class).run();
            return mock(BukkitTask.class);
        });
        List<Runnable> postponed = new ArrayList<>();
        List<Long> delays = new ArrayList<>();
        when(scheduler.runTaskLater(eq(plugin), any(Runnable.class), anyLong())).thenAnswer(invocation -> {
            postponed.add(invocation.getArgument(1));
            delays.add(invocation.getArgument(2));
            return mock(BukkitTask.class);
        });
        Runnable completed = mock(Runnable.class);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);

            persistAndApplyCandidates(command, job, operator, candidates, undo, completed);

            int firstSliceAuditBatches = 1;
            assertEquals(firstSliceAuditBatches, pendingAudits.size(),
                    "the paused second slice must not already appear in rollback history");
            assertEquals(16, pendingAudits.get(0).size());
            assertTrue(pendingAudits.get(0).stream().allMatch(change -> change.x() < 16));
            assertEquals(initialData, actualStates.get(16).get().getAsString(),
                    "the deferred block must remain unchanged while its slice is paused");
            assertEquals(1, postponed.size());
            assertEquals(lowTpsPause ? 20L : 1L, delays.get(0));
            verify(completed, never()).run();

            currentTick.incrementAndGet();
            currentTps.set(20.0);
            postponed.get(0).run();

            assertEquals(firstSliceAuditBatches + 1, pendingAudits.size());
            assertEquals(1, pendingAudits.get(firstSliceAuditBatches).size());
            assertEquals(16, pendingAudits.get(firstSliceAuditBatches).get(0).x());
            assertEquals(desiredData, actualStates.get(16).get().getAsString());
            verify(completed).run();
            if (undo) {
                verify(database, never()).prepareRollbackBatchAsync(eq(41L), anyList());
            } else {
                verify(database, times(2)).prepareRollbackBatchAsync(eq(41L), anyList());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void markExecuting(FragGuardCommand command, RollbackJob job) throws Exception {
        Field field = FragGuardCommand.class.getDeclaredField("executingJobs");
        field.setAccessible(true);
        ((Set<Long>) field.get(command)).add(job.id());
    }

    private static void ensureChunkLoaded(FragGuardCommand command, RollbackJob job,
                                          RollbackChunkPlan.ChunkKey chunk,
                                          Runnable afterLoaded) throws Exception {
        Method method = FragGuardCommand.class.getDeclaredMethod("ensureChunkLoaded",
                RollbackJob.class, Player.class, RollbackChunkPlan.ChunkKey.class, Runnable.class);
        method.setAccessible(true);
        method.invoke(command, job, null, chunk, afterLoaded);
    }

    private static void releaseChunk(FragGuardCommand command, long jobId) throws Exception {
        Method method = FragGuardCommand.class.getDeclaredMethod("releaseJobChunk", long.class);
        method.setAccessible(true);
        method.invoke(command, jobId);
    }

    private static void runJobBatch(FragGuardCommand command, RollbackJob job,
                                    Player operator, List<RollbackJobChange> changes,
                                    int index) throws Exception {
        Method method = FragGuardCommand.class.getDeclaredMethod("runJobBatch",
                RollbackJob.class, Player.class, List.class, int.class, boolean.class, int.class);
        method.setAccessible(true);
        method.invoke(command, job, operator, changes, index, false, -1);
    }

    private static void applyPreparedBatch(FragGuardCommand command, RollbackJob job,
                                           List<RollbackJobChange> changes, boolean undo) throws Exception {
        Method method = FragGuardCommand.class.getDeclaredMethod("applyPreparedBatch",
                RollbackJob.class, Player.class, List.class, List.class,
                int.class, boolean.class, int.class);
        method.setAccessible(true);
        method.invoke(command, job, null, changes, changes, changes.size(), undo, -1);
    }

    private static boolean pauseForLowTps(FragGuardCommand command, RollbackJob job,
                                         Player operator, Runnable resume) throws Exception {
        Method method = FragGuardCommand.class.getDeclaredMethod("pauseForLowTps",
                RollbackJob.class, Player.class, boolean.class, Runnable.class);
        method.setAccessible(true);
        return (boolean) method.invoke(command, job, operator, false, resume);
    }

    private static Object preparedChange(RollbackJobChange change, Block block,
                                         BlockData desired, String beforeData) throws Exception {
        Class<?> candidateClass = Class.forName(
                "org.pinnaclesmp.fragguard.FragGuardCommand$PreparedWorldChange");
        Constructor<?> constructor = candidateClass.getDeclaredConstructor(
                RollbackJobChange.class, Block.class, BlockData.class, String.class);
        constructor.setAccessible(true);
        return constructor.newInstance(change, block, desired, beforeData);
    }

    private static Object preparedChange(RollbackJobChange change, Block block,
                                         BlockData desired, String beforeData,
                                         byte[] beforeEntityData, byte[] desiredEntityData) throws Exception {
        Class<?> candidateClass = Class.forName(
                "org.pinnaclesmp.fragguard.FragGuardCommand$PreparedWorldChange");
        Constructor<?> constructor = candidateClass.getDeclaredConstructor(
                RollbackJobChange.class, Block.class, BlockData.class,
                String.class, byte[].class, byte[].class);
        constructor.setAccessible(true);
        return constructor.newInstance(change, block, desired,
                beforeData, beforeEntityData, desiredEntityData);
    }

    private static void persistAndApplyCandidates(FragGuardCommand command, RollbackJob job,
                                                   Player operator, List<Object> candidates,
                                                   boolean undo, Runnable completed) throws Exception {
        Method method = FragGuardCommand.class.getDeclaredMethod("persistAndApplyCandidates",
                RollbackJob.class, Player.class, List.class, Map.class, List.class,
                boolean.class, boolean.class, int.class, Runnable.class);
        method.setAccessible(true);
        method.invoke(command, job, operator, candidates,
                new HashMap<Integer, RollbackStepResult>(), new ArrayList<BlockChange>(),
                undo, false, 0, completed);
    }

    private static RollbackJob job(long id) {
        return new RollbackJob(id, 1_000L, UUID.randomUUID().toString(), "Builder",
                UUID.randomUUID().toString(), "world", 0, 0, 20,
                500L, "RUNNING", 1, 0, 0, 0, null);
    }
}
