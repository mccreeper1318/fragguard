package org.pinnaclesmp.fragguard;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
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

    private static boolean pauseForLowTps(FragGuardCommand command, RollbackJob job,
                                         Player operator, Runnable resume) throws Exception {
        Method method = FragGuardCommand.class.getDeclaredMethod("pauseForLowTps",
                RollbackJob.class, Player.class, boolean.class, Runnable.class);
        method.setAccessible(true);
        return (boolean) method.invoke(command, job, operator, false, resume);
    }

    private static RollbackJob job(long id) {
        return new RollbackJob(id, 1_000L, UUID.randomUUID().toString(), "Builder",
                UUID.randomUUID().toString(), "world", 0, 0, 20,
                500L, "RUNNING", 1, 0, 0, 0, null);
    }
}
