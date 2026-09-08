package org.pinnaclesmp.fragguard;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RollbackEntityRestoreFailureRegressionTest {

    @Test
    @SuppressWarnings("unchecked")
    void restoreFailureAfterBlockMutationKeepsCurrentAuditAndUnknownEntityState() throws Exception {
        FragGuardPlugin plugin = mock(FragGuardPlugin.class);
        Server server = mock(Server.class);
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("apply-physics-during-rollback", false);
        configuration.set("rollback-max-millis-per-tick", 50.0);
        when(plugin.getConfig()).thenReturn(configuration);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("FragGuardTest"));
        when(server.getCurrentTick()).thenReturn(100);

        Database database = mock(Database.class);
        when(database.markRollbackBatchAppliedAsync(eq(41L), anyList()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(database.failRollbackJobAsync(eq(41L), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        FragGuardCommand command = new FragGuardCommand(plugin, database);
        RollbackJob job = new RollbackJob(41L, 1_000L, UUID.randomUUID().toString(), "Builder",
                UUID.randomUUID().toString(), "world", 0, 0, 20,
                500L, "RUNNING", 1, 0, 0, 0, null);

        BlockData initial = mock(BlockData.class);
        BlockData desired = mock(BlockData.class);
        when(initial.getAsString()).thenReturn("minecraft:stone");
        when(desired.getAsString()).thenReturn("minecraft:chest");
        AtomicReference<BlockData> liveState = new AtomicReference<>(initial);
        Block block = mock(Block.class);
        when(block.getBlockData()).thenAnswer(ignored -> liveState.get());
        doAnswer(invocation -> {
            liveState.set(invocation.getArgument(0));
            return null;
        }).when(block).setBlockData(desired, false);

        byte[] desiredEntityData = new byte[]{9};
        RollbackJobChange change = new RollbackJobChange(
                0, "world", 1, 64, 1,
                "minecraft:stone", "minecraft:chest", false, false, false);
        Object candidate = preparedChange(change, block, desired, "minecraft:stone", null, desiredEntityData);

        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(invocation -> {
            invocation.getArgument(1, Runnable.class).run();
            return mock(BukkitTask.class);
        });

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedStatic<BlockEntitySnapshot> snapshots = mockStatic(BlockEntitySnapshot.class)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            snapshots.when(() -> BlockEntitySnapshot.capture(block)).thenReturn(null);
            snapshots.when(() -> BlockEntitySnapshot.restore(block, desiredEntityData))
                    .thenThrow(new IllegalStateException("simulated restore rejection"));

            applyPersistedCandidates(command, job, candidate);

            verify(database, never()).deleteRequiredAsync(anyList());
            ArgumentCaptor<List<RollbackStepResult>> results = ArgumentCaptor.forClass(List.class);
            verify(database).markRollbackBatchAppliedAsync(eq(41L), results.capture());
            assertEquals(1, results.getValue().size());
            RollbackStepResult persisted = results.getValue().get(0);
            assertTrue(persisted.changed());
            assertEquals("minecraft:chest", persisted.appliedData());
            assertArrayEquals(new byte[]{0}, persisted.appliedEntityData(),
                    "restore failure must persist the explicit unknown-entity marker");
            verify(database).failRollbackJobAsync(eq(41L), anyString());
        }
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

    private static void applyPersistedCandidates(FragGuardCommand command, RollbackJob job,
                                                 Object candidate) throws Exception {
        Method method = FragGuardCommand.class.getDeclaredMethod("applyPersistedCandidates",
                RollbackJob.class, org.bukkit.entity.Player.class, List.class, List.class,
                Map.class, List.class, boolean.class, int.class, Runnable.class);
        method.setAccessible(true);
        method.invoke(command, job, null, List.of(candidate), List.of(11L),
                new HashMap<Integer, RollbackStepResult>(), new ArrayList<BlockChange>(),
                false, 0, (Runnable) () -> { });
    }
}
