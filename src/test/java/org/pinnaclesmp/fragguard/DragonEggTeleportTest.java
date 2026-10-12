package org.pinnaclesmp.fragguard;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DragonEggTeleportTest {
    private static final UUID WORLD_UUID = UUID.fromString("d1a8d90f-cf0e-4acb-9f61-6f5e6b4e57e5");
    private static final UUID ACTOR_UUID = UUID.fromString("3e9ac6ea-6f9e-4c57-b93c-c3b792a0d31b");

    @TempDir
    Path temporaryDirectory;

    private Database database;

    @AfterEach
    void shutDownDatabase() {
        if (database != null) {
            database.shutdown();
        }
    }

    @Test
    void recordsBothEndsOfDragonEggTeleportEvenWhenLiquidLoggingIsDisabled() {
        FragGuardPlugin plugin = mock(FragGuardPlugin.class);
        Database mockedDatabase = mock(Database.class);
        FileConfiguration config = mock(FileConfiguration.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        BukkitTask task = mock(BukkitTask.class);
        World world = mock(World.class);
        Map<String, Block> blocks = new ConcurrentHashMap<>();
        List<Runnable> scheduled = new ArrayList<>();

        when(plugin.getConfig()).thenReturn(config);
        when(config.getBoolean("log-liquid-flow", true)).thenReturn(false);
        when(world.getName()).thenReturn("world");
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(invocation ->
                blocks.get(key(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2))));
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(invocation -> {
            scheduled.add(invocation.getArgument(1));
            return task;
        });

        Block source = block(world, 4, 64, 4, Material.DRAGON_EGG,
                "minecraft:dragon_egg", "minecraft:air");
        Block destination = block(world, 9, 67, -2, Material.AIR,
                "minecraft:air", "minecraft:dragon_egg");
        blocks.put(key(4, 64, 4), source);
        blocks.put(key(9, 67, -2), destination);

        BlockFromToEvent event = mock(BlockFromToEvent.class);
        when(event.getBlock()).thenReturn(source);
        when(event.getToBlock()).thenReturn(destination);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            bukkit.when(Bukkit::getCurrentTick).thenReturn(600);
            bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);

            new BlockChangeListener(plugin, mockedDatabase).onLiquidFlow(event);
            assertEquals(1, scheduled.size());
            scheduled.removeFirst().run();
        }

        ArgumentCaptor<BlockChange> changes = ArgumentCaptor.forClass(BlockChange.class);
        verify(mockedDatabase, times(2)).insertAsync(changes.capture());
        List<BlockChange> captured = changes.getAllValues();
        assertEquals(List.of(4, 9), captured.stream().map(BlockChange::x).toList());
        assertEquals(List.of("minecraft:dragon_egg", "minecraft:air"),
                captured.stream().map(BlockChange::beforeData).toList());
        assertEquals(List.of("minecraft:air", "minecraft:dragon_egg"),
                captured.stream().map(BlockChange::afterData).toList());
        assertTrue(captured.stream().allMatch(change -> change.action() == ChangeAction.DRAGON_EGG_TELEPORT));
        assertTrue(captured.stream().allMatch(change -> change.actorUuid().equals("SYSTEM")));
        assertTrue(captured.stream().allMatch(change -> change.actorName().equals("Dragon Egg Teleport")));
    }

    @Test
    void dragonEggPlayerInteractionDefersHistoryToTeleportEvent() {
        FragGuardPlugin plugin = mock(FragGuardPlugin.class);
        Database mockedDatabase = mock(Database.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        Block egg = mock(Block.class);
        PlayerInteractEvent event = mock(PlayerInteractEvent.class);

        when(egg.getType()).thenReturn(Material.DRAGON_EGG);
        when(event.getAction()).thenReturn(Action.RIGHT_CLICK_BLOCK);
        when(event.useInteractedBlock()).thenReturn(Event.Result.DEFAULT);
        when(event.useItemInHand()).thenReturn(Event.Result.DEFAULT);
        when(event.getClickedBlock()).thenReturn(egg);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            new BlockChangeListener(plugin, mockedDatabase).onPlayerInteract(event);
        }

        verify(mockedDatabase, never()).insertAsync(any());
        verify(scheduler, never()).runTask(eq(plugin), any(Runnable.class));
        verify(egg, never()).getBlockData();
    }

    @Test
    void lookupRollbackAndUndoPlansKeepExactlyOneDragonEgg() throws Exception {
        database = startDatabase();
        long timestamp = System.currentTimeMillis();
        database.insertRequiredAsync(List.of(
                new BlockChange(timestamp, ACTOR_UUID.toString(), "Dragon Egg Teleport", "world",
                        4, 64, 4, ChangeAction.DRAGON_EGG_TELEPORT,
                        "minecraft:dragon_egg", "minecraft:air"),
                new BlockChange(timestamp, ACTOR_UUID.toString(), "Dragon Egg Teleport", "world",
                        9, 67, -2, ChangeAction.DRAGON_EGG_TELEPORT,
                        "minecraft:air", "minecraft:dragon_egg")
        )).join();

        LookupPage lookup = database.lookupAsync("world", 6, 1, 8, 1, 10, 30).join();
        assertEquals(2, lookup.totalRows());
        assertTrue(lookup.rows().stream().allMatch(row -> row.action() == ChangeAction.DRAGON_EGG_TELEPORT));

        List<RollbackTarget> targets = database.rollbackTargetsAsync(
                "world", 6, 1, 8, timestamp - 1L, timestamp + 1L, 10).join();
        assertEquals(2, targets.size());
        assertEquals(1, countEggs(targets.stream().map(RollbackTarget::blockData).toList()),
                "the rollback target must restore exactly one egg");
        assertEquals(1, countEggs(targets.stream().map(RollbackTarget::expectedData).toList()),
                "the post-teleport snapshot must contain exactly one egg");

        RollbackJob job = database.createRollbackJobAsync(
                ACTOR_UUID.toString(), "Builder", "world", 6, 1, 8,
                timestamp - 1L, timestamp + 1L, false, targets).join();
        List<RollbackJobChange> rollbackChanges = database.loadRollbackChangesAsync(job.id(), false).join();
        List<RollbackJobChange> prepared = rollbackChanges.stream()
                .map(change -> change.withBeforeData(change.expectedData()))
                .toList();
        database.prepareRollbackBatchAsync(job.id(), prepared).join();
        database.markRollbackBatchAppliedAsync(job.id(), prepared.stream()
                .map(change -> new RollbackStepResult(change.sequence(), true, false))
                .toList()).join();
        database.completeRollbackJobAsync(job.id(), false).join();

        database.beginUndoAsync(job.id()).join();
        List<RollbackJobChange> undoChanges = database.loadRollbackChangesAsync(job.id(), true).join();
        assertEquals(2, undoChanges.size());
        assertEquals(1, countEggs(undoChanges.stream().map(RollbackJobChange::targetData).toList()),
                "rollback must leave exactly one egg at the original coordinate");
        assertEquals(1, countEggs(undoChanges.stream().map(RollbackJobChange::beforeData).toList()),
                "undo must restore exactly one egg at the teleported coordinate");
    }

    private Database startDatabase() throws Exception {
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        World world = mock(World.class);
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("database-write-queue-capacity", 64);
        configuration.set("database-write-batch-size", 16);
        configuration.set("database-query-timeout-seconds", 5);
        when(plugin.getDataFolder()).thenReturn(temporaryDirectory.toFile());
        when(plugin.getConfig()).thenReturn(configuration);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("DragonEggTeleportTest"));
        when(server.getCurrentTick()).thenReturn(700);
        when(server.getWorld("world")).thenReturn(world);
        when(server.getWorlds()).thenReturn(List.of(world));
        when(world.getUID()).thenReturn(WORLD_UUID);
        when(world.getName()).thenReturn("world");

        Database instance = new Database(plugin);
        instance.init();
        return instance;
    }

    private static Block block(World world, int x, int y, int z, Material type,
                               String beforeData, String afterData) {
        Block block = mock(Block.class);
        BlockData before = mock(BlockData.class);
        BlockData after = mock(BlockData.class);
        BlockState state = mock(BlockState.class);
        when(before.getAsString()).thenReturn(beforeData);
        when(after.getAsString()).thenReturn(afterData);
        when(block.getWorld()).thenReturn(world);
        when(block.getX()).thenReturn(x);
        when(block.getY()).thenReturn(y);
        when(block.getZ()).thenReturn(z);
        when(block.getType()).thenReturn(type);
        when(block.getBlockData()).thenReturn(before, after);
        when(block.getState()).thenReturn(state);
        return block;
    }

    private static String key(int x, int y, int z) {
        return x + ":" + y + ":" + z;
    }

    private static long countEggs(List<String> states) {
        return states.stream().filter("minecraft:dragon_egg"::equals).count();
    }
}
