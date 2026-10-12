package org.pinnaclesmp.fragguard;

import io.papermc.paper.block.TileStateInventoryHolder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Shelf;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.SideChaining;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShelfInventory;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.MockedStatic;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class Paper263ShelfCompatibilityTest {
    private static final UUID WORLD_UUID = UUID.fromString("563fce36-6445-43e9-9e79-3bb6d0780b13");
    private static final UUID PLAYER_UUID = UUID.fromString("714ea63f-075e-4694-b2c4-ae06a79748aa");
    private static final String SHELF_DATA =
            "minecraft:oak_shelf[facing=north,powered=false,side_chain=unconnected,waterlogged=false]";

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
    void shelfUsesGenericInventorySnapshotForLookupConflictRollbackAndUndo() throws Exception {
        assertTrue(TileStateInventoryHolder.class.isAssignableFrom(Shelf.class),
                "Paper 26.3 Shelf must remain on FragGuard's generic inventory snapshot path");

        ItemStack afterItem = mock(ItemStack.class);
        ItemStack[] beforeContents = new ItemStack[]{null, null, null};
        ItemStack[] afterContents = new ItemStack[]{afterItem, null, null};
        byte[] beforeBytes = serializedItems(null, null, null);
        byte[] afterBytes = serializedItems(new byte[]{4, 5}, null, null);

        Shelf beforeState = shelfState(beforeContents);
        Shelf afterState = shelfState(afterContents);

        try (MockedStatic<ItemStack> itemStacks = mockStatic(ItemStack.class)) {
            itemStacks.when(() -> ItemStack.serializeItemsAsBytes(beforeContents)).thenReturn(beforeBytes);
            itemStacks.when(() -> ItemStack.serializeItemsAsBytes(afterContents)).thenReturn(afterBytes);
            itemStacks.when(() -> ItemStack.deserializeItemsFromBytes(beforeBytes)).thenReturn(beforeContents);
            itemStacks.when(() -> ItemStack.deserializeItemsFromBytes(afterBytes)).thenReturn(afterContents);

            byte[] beforeSnapshot = BlockEntitySnapshot.capture(beforeState);
            byte[] afterSnapshot = BlockEntitySnapshot.capture(afterState);

            BlockEntitySnapshot.SnapshotDescription description = BlockEntitySnapshot.describe(beforeSnapshot);
            assertTrue(description.readable());
            assertEquals("Container", description.type());
            assertTrue(description.details().stream().anyMatch(detail -> detail.equals("Items: 0 non-empty slot(s)")),
                    "raw block-entity details should expose Shelf inventory information");

            assertTrue(FragGuardCommand.matchesState(
                    SHELF_DATA, beforeSnapshot, SHELF_DATA, beforeSnapshot.clone()));
            assertFalse(FragGuardCommand.matchesState(
                    SHELF_DATA, afterSnapshot, SHELF_DATA, beforeSnapshot),
                    "Shelf inventory changes must participate in rollback conflict protection");

            Shelf restorationState = mock(Shelf.class);
            ShelfInventory restorationInventory = mock(ShelfInventory.class);
            when(restorationState.getSnapshotInventory()).thenReturn(restorationInventory);
            when(restorationState.update(true, false)).thenReturn(true);
            Block restorationBlock = mock(Block.class);
            when(restorationBlock.getState()).thenReturn(restorationState);

            // The first restore models rollback to the historical Shelf contents; the second models undo.
            BlockEntitySnapshot.restore(restorationBlock, beforeSnapshot);
            BlockEntitySnapshot.restore(restorationBlock, afterSnapshot);

            InOrder ordered = inOrder(restorationInventory, restorationState);
            ordered.verify(restorationInventory).setContents(beforeContents);
            ordered.verify(restorationState).update(true, false);
            ordered.verify(restorationInventory).setContents(afterContents);
            ordered.verify(restorationState).update(true, false);
        }
    }

    @Test
    void playerShelfSwapIsLoggedWhenOnlyInventoryContentsChange() throws Exception {
        try (ShelfInteractionHarness harness = new ShelfInteractionHarness()) {
            ItemStack[] beforeContents = new ItemStack[]{mock(ItemStack.class), null, null};
            ItemStack[] afterContents = new ItemStack[]{null, mock(ItemStack.class), null};
            byte[] beforeBytes = serializedItems(new byte[]{1}, null, null);
            byte[] afterBytes = serializedItems(null, new byte[]{2}, null);

            org.bukkit.block.data.type.Shelf beforeData = shelfData(false, SideChaining.ChainPart.UNCONNECTED);
            org.bukkit.block.data.type.Shelf afterData = shelfData(false, SideChaining.ChainPart.UNCONNECTED);
            Shelf beforeState = shelfState(beforeContents);
            Shelf afterState = shelfState(afterContents);
            Block shelf = harness.shelfBlock(10, beforeData, afterData, beforeState, afterState);

            try (MockedStatic<ItemStack> itemStacks = mockStatic(ItemStack.class)) {
                itemStacks.when(() -> ItemStack.serializeItemsAsBytes(beforeContents)).thenReturn(beforeBytes);
                itemStacks.when(() -> ItemStack.serializeItemsAsBytes(afterContents)).thenReturn(afterBytes);

                harness.listener.onShelfInteract(harness.interactEvent(shelf));
                harness.runNextTick();
            }

            BlockChange change = harness.captureSingleChange();
            assertEquals(ChangeAction.PLAYER_INTERACT, change.action());
            assertEquals(PLAYER_UUID.toString(), change.actorUuid());
            assertEquals("Builder", change.actorName());
            assertEquals(SHELF_DATA, change.beforeData());
            assertEquals(SHELF_DATA, change.afterData());
            assertNotNull(change.beforeEntityData());
            assertNotNull(change.afterEntityData());
            assertFalse(Arrays.equals(change.beforeEntityData(), change.afterEntityData()),
                    "an entity-only Shelf inventory swap must not be discarded as an unchanged block");
        }
    }

    @Test
    void poweredConnectedShelfSwapLogsEveryChangedShelf() throws Exception {
        try (ShelfInteractionHarness harness = new ShelfInteractionHarness()) {
            ItemStack[] leftBefore = new ItemStack[]{mock(ItemStack.class), null, null};
            ItemStack[] centerBefore = new ItemStack[]{mock(ItemStack.class), null, null};
            ItemStack[] rightBefore = new ItemStack[]{mock(ItemStack.class), null, null};
            ItemStack[] leftAfter = new ItemStack[]{null, mock(ItemStack.class), null};
            ItemStack[] centerAfter = new ItemStack[]{null, mock(ItemStack.class), null};
            ItemStack[] rightAfter = new ItemStack[]{null, mock(ItemStack.class), null};

            org.bukkit.block.data.type.Shelf leftData = shelfData(true, SideChaining.ChainPart.LEFT);
            org.bukkit.block.data.type.Shelf centerData = shelfData(true, SideChaining.ChainPart.CENTER);
            org.bukkit.block.data.type.Shelf rightData = shelfData(true, SideChaining.ChainPart.RIGHT);

            Block left = harness.shelfBlock(9, leftData, leftData,
                    shelfState(leftBefore), shelfState(leftAfter));
            Block center = harness.shelfBlock(10, centerData, centerData,
                    shelfState(centerBefore), shelfState(centerAfter));
            Block right = harness.shelfBlock(11, rightData, rightData,
                    shelfState(rightBefore), shelfState(rightAfter));
            Block outsideLeft = harness.nonShelfBlock(8);
            Block outsideRight = harness.nonShelfBlock(12);

            when(center.getRelative(BlockFace.WEST, 1)).thenReturn(left);
            when(center.getRelative(BlockFace.WEST, 2)).thenReturn(outsideLeft);
            when(center.getRelative(BlockFace.EAST, 1)).thenReturn(right);
            when(center.getRelative(BlockFace.EAST, 2)).thenReturn(outsideRight);

            byte[] leftBeforeBytes = serializedItems(new byte[]{1}, null, null);
            byte[] centerBeforeBytes = serializedItems(new byte[]{2}, null, null);
            byte[] rightBeforeBytes = serializedItems(new byte[]{3}, null, null);
            byte[] leftAfterBytes = serializedItems(null, new byte[]{4}, null);
            byte[] centerAfterBytes = serializedItems(null, new byte[]{5}, null);
            byte[] rightAfterBytes = serializedItems(null, new byte[]{6}, null);

            try (MockedStatic<ItemStack> itemStacks = mockStatic(ItemStack.class)) {
                itemStacks.when(() -> ItemStack.serializeItemsAsBytes(leftBefore)).thenReturn(leftBeforeBytes);
                itemStacks.when(() -> ItemStack.serializeItemsAsBytes(centerBefore)).thenReturn(centerBeforeBytes);
                itemStacks.when(() -> ItemStack.serializeItemsAsBytes(rightBefore)).thenReturn(rightBeforeBytes);
                itemStacks.when(() -> ItemStack.serializeItemsAsBytes(leftAfter)).thenReturn(leftAfterBytes);
                itemStacks.when(() -> ItemStack.serializeItemsAsBytes(centerAfter)).thenReturn(centerAfterBytes);
                itemStacks.when(() -> ItemStack.serializeItemsAsBytes(rightAfter)).thenReturn(rightAfterBytes);

                harness.listener.onShelfInteract(harness.interactEvent(center));
                harness.runNextTick();
            }

            ArgumentCaptor<BlockChange> changes = ArgumentCaptor.forClass(BlockChange.class);
            verify(harness.database, org.mockito.Mockito.times(3)).insertAsync(changes.capture());
            assertEquals(Set.of(9, 10, 11),
                    changes.getAllValues().stream().map(BlockChange::x).collect(Collectors.toSet()));
            assertTrue(changes.getAllValues().stream()
                    .allMatch(change -> change.action() == ChangeAction.PLAYER_INTERACT));
        }
    }

    @Test
    void guiLookupRetainsShelfSnapshotsForExactRawDetails() throws Exception {
        ItemStack[] beforeContents = new ItemStack[]{mock(ItemStack.class), null, null};
        ItemStack[] afterContents = new ItemStack[]{null, mock(ItemStack.class), null};
        byte[] beforeBytes = serializedItems(new byte[]{7}, null, null);
        byte[] afterBytes = serializedItems(null, new byte[]{7}, null);
        byte[] beforeSnapshot;
        byte[] afterSnapshot;
        try (MockedStatic<ItemStack> itemStacks = mockStatic(ItemStack.class)) {
            itemStacks.when(() -> ItemStack.serializeItemsAsBytes(beforeContents)).thenReturn(beforeBytes);
            itemStacks.when(() -> ItemStack.serializeItemsAsBytes(afterContents)).thenReturn(afterBytes);
            beforeSnapshot = BlockEntitySnapshot.capture(shelfState(beforeContents));
            afterSnapshot = BlockEntitySnapshot.capture(shelfState(afterContents));
        }

        database = startDatabase();
        long now = System.currentTimeMillis();
        database.insertRequiredAsync(List.of(new BlockChange(
                now, PLAYER_UUID.toString(), "Builder", "world",
                2, 64, 2, ChangeAction.PLAYER_INTERACT,
                SHELF_DATA, SHELF_DATA, beforeSnapshot, afterSnapshot))).join();

        GuiLookupStore guiStore = new GuiLookupStore(temporaryDirectory.toFile(), 5);
        List<LookupRow> rows = guiStore.selectRowsAsync(
                WORLD_UUID.toString(), "world", 2, 2, 2,
                now - 1_000L, now + 1_000L, 50).join();

        assertEquals(1, rows.size());
        LookupRow row = rows.getFirst();
        assertTrue(row.blockEntityDataPresent());
        assertFalse(row.blockEntityPayloadLoaded());

        LookupEventPayload payload = guiStore.loadEventPayloadAsync(row.id()).join();
        assertArrayEquals(beforeSnapshot, payload.beforeEntityData());
        assertArrayEquals(afterSnapshot, payload.afterEntityData());
        assertTrue(payload.changed());
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
        when(plugin.getLogger()).thenReturn(Logger.getLogger("Paper263ShelfCompatibilityTest"));
        when(server.getCurrentTick()).thenReturn(100);
        when(server.getWorld("world")).thenReturn(world);
        when(server.getWorlds()).thenReturn(List.of(world));
        when(world.getUID()).thenReturn(WORLD_UUID);
        when(world.getName()).thenReturn("world");

        Database instance = new Database(plugin);
        instance.init();
        return instance;
    }

    private static Shelf shelfState(ItemStack[] contents) {
        Shelf state = mock(Shelf.class);
        ShelfInventory inventory = mock(ShelfInventory.class);
        when(state.getSnapshotInventory()).thenReturn(inventory);
        when(inventory.getContents()).thenReturn(contents);
        return state;
    }

    private static org.bukkit.block.data.type.Shelf shelfData(
            boolean powered,
            SideChaining.ChainPart chainPart
    ) {
        org.bukkit.block.data.type.Shelf data = mock(org.bukkit.block.data.type.Shelf.class);
        when(data.getAsString()).thenReturn(SHELF_DATA);
        when(data.getFacing()).thenReturn(BlockFace.NORTH);
        when(data.isPowered()).thenReturn(powered);
        when(data.getSideChain()).thenReturn(chainPart);
        return data;
    }

    private static byte[] serializedItems(byte[]... payloads) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeByte(1);
            output.writeInt(payloads.length);
            for (byte[] payload : payloads) {
                if (payload == null) {
                    output.writeInt(0);
                } else {
                    output.writeInt(payload.length);
                    output.write(payload);
                }
            }
        }
        return bytes.toByteArray();
    }

    private static final class ShelfInteractionHarness implements AutoCloseable {
        private final FragGuardPlugin plugin = mock(FragGuardPlugin.class);
        private final Database database = mock(Database.class);
        private final FileConfiguration config = mock(FileConfiguration.class);
        private final BukkitScheduler scheduler = mock(BukkitScheduler.class);
        private final BukkitTask task = mock(BukkitTask.class);
        private final World world = mock(World.class);
        private final Player player = mock(Player.class);
        private final Map<Integer, Block> blocks = new HashMap<>();
        private final List<Runnable> scheduledTasks = new ArrayList<>();
        private final MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
        private final ShelfChangeListener listener = new ShelfChangeListener(plugin, database);

        private ShelfInteractionHarness() {
            when(plugin.getConfig()).thenReturn(config);
            when(world.getName()).thenReturn("world");
            when(player.getUniqueId()).thenReturn(PLAYER_UUID);
            when(player.getName()).thenReturn("Builder");
            when(world.getBlockAt(anyInt(), anyInt(), anyInt()))
                    .thenAnswer(invocation -> blocks.get(invocation.getArgument(0)));
            when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(invocation -> {
                scheduledTasks.add(invocation.getArgument(1));
                return task;
            });
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            bukkit.when(Bukkit::getCurrentTick).thenReturn(500);
            bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
        }

        private Block shelfBlock(
                int x,
                org.bukkit.block.data.type.Shelf beforeData,
                org.bukkit.block.data.type.Shelf afterData,
                Shelf beforeState,
                Shelf afterState
        ) {
            Block block = mock(Block.class);
            when(block.getWorld()).thenReturn(world);
            when(block.getX()).thenReturn(x);
            when(block.getY()).thenReturn(64);
            when(block.getZ()).thenReturn(10);
            when(block.getType()).thenReturn(Material.OAK_SHELF);
            when(block.getBlockData()).thenReturn(beforeData, afterData);
            when(block.getState()).thenReturn(beforeState, afterState);
            blocks.put(x, block);
            return block;
        }

        private Block nonShelfBlock(int x) {
            Block block = mock(Block.class);
            BlockData data = mock(BlockData.class);
            when(block.getBlockData()).thenReturn(data);
            when(block.getWorld()).thenReturn(world);
            when(block.getX()).thenReturn(x);
            when(block.getY()).thenReturn(64);
            when(block.getZ()).thenReturn(10);
            blocks.put(x, block);
            return block;
        }

        private PlayerInteractEvent interactEvent(Block block) {
            PlayerInteractEvent event = mock(PlayerInteractEvent.class);
            when(event.getAction()).thenReturn(Action.RIGHT_CLICK_BLOCK);
            when(event.useInteractedBlock()).thenReturn(Event.Result.DEFAULT);
            when(event.useItemInHand()).thenReturn(Event.Result.DEFAULT);
            when(event.getClickedBlock()).thenReturn(block);
            when(event.getPlayer()).thenReturn(player);
            return event;
        }

        private void runNextTick() {
            assertEquals(1, scheduledTasks.size());
            scheduledTasks.removeFirst().run();
        }

        private BlockChange captureSingleChange() {
            ArgumentCaptor<BlockChange> change = ArgumentCaptor.forClass(BlockChange.class);
            verify(database).insertAsync(change.capture());
            return change.getValue();
        }

        @Override
        public void close() {
            bukkit.close();
        }
    }
}
