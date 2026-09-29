package org.pinnaclesmp.fragguard;

import com.destroystokyo.paper.profile.ProfileProperty;
import io.papermc.paper.block.TileStateInventoryHolder;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.DyeColor;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Banner;
import org.bukkit.block.Block;
import org.bukkit.block.DecoratedPot;
import org.bukkit.block.Lectern;
import org.bukkit.block.Sign;
import org.bukkit.block.Skull;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.DecoratedPotInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Migration fixtures for the exact block-entity envelope and schema version used by 26.2-1.1.2.
 *
 * <p>The historical encoder below intentionally mirrors the version-1 format from the
 * 26.2-1.1.2 tag rather than calling the current capture implementation. That prevents a
 * future serializer change from making this compatibility test accidentally self-validating.</p>
 */
class Paper26_2PersistenceCompatibilityTest {
    private static final int MAGIC = 0x46474245;
    private static final int FORMAT_VERSION = 1;
    private static final UUID WORLD_UUID = UUID.fromString("563fce36-6445-43e9-9e79-3bb6d0780b13");
    private static final UUID ACTOR_UUID = UUID.fromString("714ea63f-075e-4694-b2c4-ae06a79748aa");

    @TempDir
    Path temporaryDirectory;

    private final AtomicInteger currentTick = new AtomicInteger(100);
    private Database database;

    @AfterEach
    void shutDownDatabase() {
        if (database != null) {
            database.shutdown();
        }
    }

    @Test
    void restoresRepresentative26_2BlockEntitySnapshotsOn26_3() throws Exception {
        Component customName = Component.text("Legacy container");
        byte[] inventoryBytes = new byte[]{4, 8, 15, 16, 23, 42};
        ItemStack[] restoredItems = new ItemStack[]{mock(ItemStack.class), null, mock(ItemStack.class)};

        TileStateInventoryHolder inventory = mock(TileStateInventoryHolder.class);
        Inventory targetInventory = mock(Inventory.class);
        when(inventory.getSnapshotInventory()).thenReturn(targetInventory);
        when(inventory.customName()).thenReturn(null);

        try (MockedStatic<ItemStack> itemStacks = mockStatic(ItemStack.class)) {
            itemStacks.when(() -> ItemStack.deserializeItemsFromBytes(inventoryBytes)).thenReturn(restoredItems);
            BlockEntitySnapshot.restore(block(inventory), legacyInventorySnapshot("INVENTORY", customName, inventoryBytes));
        }

        verify(inventory).customName(customName);
        verify(targetInventory).setContents(restoredItems);
        verify(inventory).update(true, false);

        Sign sign = mock(Sign.class);
        SignSide front = signSide();
        SignSide back = signSide();
        when(sign.getSide(Side.FRONT)).thenReturn(front);
        when(sign.getSide(Side.BACK)).thenReturn(back);
        BlockEntitySnapshot.restore(block(sign), legacySignSnapshot());
        verify(sign).setWaxed(true);
        verify(front).setColor(DyeColor.RED);
        verify(front).setGlowingText(true);
        verify(front).line(0, Component.text("Front legacy"));
        verify(back).setColor(DyeColor.BLUE);
        verify(back).line(1, Component.text("Back legacy"));

        Lectern lectern = mock(Lectern.class);
        Inventory lecternInventory = mock(Inventory.class);
        when(lectern.getSnapshotInventory()).thenReturn(lecternInventory);
        try (MockedStatic<ItemStack> itemStacks = mockStatic(ItemStack.class)) {
            itemStacks.when(() -> ItemStack.deserializeItemsFromBytes(inventoryBytes)).thenReturn(restoredItems);
            BlockEntitySnapshot.restore(block(lectern), legacyLecternSnapshot(inventoryBytes, 7));
        }
        verify(lecternInventory).setContents(restoredItems);
        verify(lectern).setPage(7);

        DecoratedPot pot = mock(DecoratedPot.class);
        DecoratedPotInventory potInventory = mock(DecoratedPotInventory.class);
        when(pot.getSnapshotInventory()).thenReturn(potInventory);
        try (MockedStatic<ItemStack> itemStacks = mockStatic(ItemStack.class)) {
            itemStacks.when(() -> ItemStack.deserializeItemsFromBytes(inventoryBytes)).thenReturn(restoredItems);
            BlockEntitySnapshot.restore(block(pot), legacyDecoratedPotSnapshot(inventoryBytes));
        }
        verify(potInventory).setContents(restoredItems);
        for (DecoratedPot.Side side : DecoratedPot.Side.values()) {
            verify(pot).setSherd(side, org.bukkit.Material.BRICK);
        }

        Banner banner = mock(Banner.class);
        BlockEntitySnapshot.restore(block(banner), legacyEmptyBannerSnapshot(Component.text("Legacy banner")));
        verify(banner).customName(Component.text("Legacy banner"));
        verify(banner).setPatterns(List.of());

        Skull skull = mock(Skull.class);
        ResolvableProfile restoredProfile = mock(ResolvableProfile.class);
        ResolvableProfile.Builder builder = mock(ResolvableProfile.Builder.class);
        when(builder.uuid(ACTOR_UUID)).thenReturn(builder);
        when(builder.name("Builder")).thenReturn(builder);
        when(builder.addProperty(any(ProfileProperty.class))).thenReturn(builder);
        when(builder.build()).thenReturn(restoredProfile);
        try (MockedStatic<ResolvableProfile> profiles = mockStatic(ResolvableProfile.class)) {
            profiles.when(ResolvableProfile::resolvableProfile).thenReturn(builder);
            BlockEntitySnapshot.restore(block(skull), legacySkullSnapshot());
        }
        verify(skull).setProfile(restoredProfile);
    }

    @Test
    void migratesSchema3HistoryAndRollbackSnapshotsWithoutRewritingLegacyPayloads() throws Exception {
        database = startDatabase();
        long now = System.currentTimeMillis();
        byte[] legacyBefore = legacySignSnapshot();
        byte[] legacyAfter = legacyEmptyBannerSnapshot(Component.text("After"));

        database.insertRequiredAsync(List.of(new BlockChange(now - 2_000L, ACTOR_UUID.toString(), "Builder", "world",
                4, 64, 4, ChangeAction.BREAK, "minecraft:oak_sign", "minecraft:white_banner",
                legacyBefore, legacyAfter))).join();

        RollbackJob job = database.createRollbackJobAsync(ACTOR_UUID.toString(), "Builder", "world",
                4, 4, 5, now - 3_000L, now, false,
                List.of(new RollbackTarget("world", 4, 64, 4,
                        "minecraft:oak_sign", "minecraft:white_banner", legacyBefore, legacyAfter))).join();
        RollbackJobChange originalJobChange = database.loadRollbackChangesAsync(job.id(), false).join().get(0);
        database.prepareRollbackBatchAsync(job.id(), List.of(
                originalJobChange.withBeforeState("minecraft:white_banner", legacyAfter))).join();
        database.shutdown();
        database = null;

        try (Connection connection = openDatabase(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_fg_tick_coalesce ON block_changes(world_uuid, x, y, z)");
            statement.execute("PRAGMA user_version=3");
        }

        database = startDatabase();

        try (Connection connection = openDatabase(); Statement statement = connection.createStatement()) {
            try (ResultSet version = statement.executeQuery("PRAGMA user_version")) {
                assertTrue(version.next());
                assertEquals(Database.SCHEMA_VERSION, version.getInt(1));
            }
            try (ResultSet index = statement.executeQuery(
                    "SELECT 1 FROM sqlite_master WHERE type='index' AND name='idx_fg_tick_coalesce'")) {
                assertFalse(index.next(), "the schema-3-only coalescing index should be removed during migration");
            }
            try (ResultSet row = statement.executeQuery(
                    "SELECT before_entity_data, after_entity_data FROM block_changes WHERE x=4 AND y=64 AND z=4")) {
                assertTrue(row.next());
                assertArrayEquals(legacyBefore, row.getBytes("before_entity_data"));
                assertArrayEquals(legacyAfter, row.getBytes("after_entity_data"));
            }
        }

        LookupPage lookup = database.lookupSinceAsync("world", 4, 4, 2, 1, 10, now - 10_000L).join();
        assertEquals(1, lookup.totalRows());
        assertArrayEquals(legacyBefore, lookup.rows().get(0).beforeEntityData());
        assertArrayEquals(legacyAfter, lookup.rows().get(0).afterEntityData());

        List<RollbackTarget> targets = database.rollbackTargetsAsync("world", 4, 4, 2,
                now - 10_000L, now, 10).join();
        assertEquals(1, targets.size());
        assertArrayEquals(legacyBefore, targets.get(0).targetEntityData());
        assertArrayEquals(legacyAfter, targets.get(0).expectedEntityData());

        RollbackJobChange migratedJobChange = database.loadRollbackChangesAsync(job.id(), false).join().get(0);
        assertArrayEquals(legacyBefore, migratedJobChange.targetEntityData());
        assertArrayEquals(legacyAfter, migratedJobChange.expectedEntityData());
        assertArrayEquals(legacyAfter, migratedJobChange.beforeEntityData());

        database.markRollbackBatchAppliedAsync(job.id(),
                List.of(new RollbackStepResult(migratedJobChange.sequence(), true, false))).join();
        database.completeRollbackJobAsync(job.id(), false).join();
        database.beginUndoAsync(job.id()).join();
        RollbackJobChange undo = database.loadRollbackChangesAsync(job.id(), true).join().get(0);
        assertArrayEquals(legacyAfter, undo.beforeEntityData(),
                "undo must retain the exact pre-rollback entity snapshot across the 26.2 -> 26.3 migration");
    }

    @Test
    void legacyMissingEntitySnapshotsStillFailClosedForSupportedBlockEntities() {
        byte[] liveEntityState = legacySignSnapshot();
        assertFalse(FragGuardCommand.matchesState(
                "minecraft:oak_sign", liveEntityState, "minecraft:oak_sign", null));
        assertTrue(FragGuardCommand.matchesState(
                "minecraft:stone", null, "minecraft:stone", null));
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
        when(plugin.getLogger()).thenReturn(Logger.getLogger("Paper26_2CompatibilityTest"));
        when(server.getCurrentTick()).thenAnswer(invocation -> currentTick.get());
        when(server.getWorld("world")).thenReturn(world);
        when(server.getWorlds()).thenReturn(List.of(world));
        when(world.getUID()).thenReturn(WORLD_UUID);
        when(world.getName()).thenReturn("world");
        Database instance = new Database(plugin);
        instance.init();
        return instance;
    }

    private Connection openDatabase() throws Exception {
        Class.forName("org.sqlite.JDBC");
        return DriverManager.getConnection("jdbc:sqlite:" + temporaryDirectory.resolve("fragguard.db"));
    }

    private Block block(org.bukkit.block.BlockState state) {
        Block block = mock(Block.class);
        when(block.getState()).thenReturn(state);
        when(state.update(true, false)).thenReturn(true);
        return block;
    }

    private SignSide signSide() {
        SignSide side = mock(SignSide.class);
        when(side.lines()).thenReturn(Arrays.asList(Component.empty(), Component.empty(), Component.empty(), Component.empty()));
        return side;
    }

    private byte[] legacyInventorySnapshot(String kind, Component customName, byte[] inventoryBytes) throws Exception {
        return legacySnapshot(kind, customName, output -> writeInventory(output, inventoryBytes));
    }

    private byte[] legacySignSnapshot() throws Exception {
        return legacySnapshot("SIGN", null, output -> {
            output.writeBoolean(true);
            writeSignSide(output, DyeColor.RED, true,
                    List.of(Component.text("Front legacy"), Component.empty(), Component.empty(), Component.empty()));
            writeSignSide(output, DyeColor.BLUE, false,
                    List.of(Component.empty(), Component.text("Back legacy"), Component.empty(), Component.empty()));
        });
    }

    private byte[] legacyLecternSnapshot(byte[] inventoryBytes, int page) throws Exception {
        return legacySnapshot("LECTERN", Component.text("Legacy lectern"), output -> {
            writeInventory(output, inventoryBytes);
            output.writeInt(page);
        });
    }

    private byte[] legacyDecoratedPotSnapshot(byte[] inventoryBytes) throws Exception {
        return legacySnapshot("DECORATED_POT", null, output -> {
            writeInventory(output, inventoryBytes);
            output.writeInt(DecoratedPot.Side.values().length);
            for (DecoratedPot.Side side : DecoratedPot.Side.values()) {
                output.writeUTF(side.name());
                output.writeUTF("BRICK");
            }
        });
    }

    private byte[] legacyEmptyBannerSnapshot(Component customName) throws Exception {
        return legacySnapshot("BANNER", customName, output -> output.writeInt(0));
    }

    private byte[] legacySkullSnapshot() throws Exception {
        return legacySnapshot("SKULL", Component.text("Legacy head"), output -> {
            output.writeBoolean(true);
            writeNullableString(output, ACTOR_UUID.toString());
            writeNullableString(output, "Builder");
            output.writeInt(1);
            output.writeUTF("textures");
            output.writeUTF("legacy-base64-skin");
            writeNullableString(output, "legacy-signature");
        });
    }

    private byte[] legacySnapshot(String kind, Component customName, SnapshotWriter writer) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(new GZIPOutputStream(bytes))) {
            output.writeInt(MAGIC);
            output.writeByte(FORMAT_VERSION);
            output.writeUTF(kind);
            writeComponent(output, customName);
            writer.write(output);
        }
        return bytes.toByteArray();
    }

    private void writeInventory(DataOutputStream output, byte[] inventoryBytes) throws Exception {
        output.writeInt(inventoryBytes.length);
        output.write(inventoryBytes);
    }

    private void writeSignSide(DataOutputStream output, DyeColor color, boolean glowing,
                               List<Component> lines) throws Exception {
        output.writeUTF(color.name());
        output.writeBoolean(glowing);
        output.writeInt(lines.size());
        for (Component line : lines) {
            writeComponent(output, line);
        }
    }

    private void writeComponent(DataOutputStream output, Component component) throws Exception {
        writeNullableString(output, component == null ? null : GsonComponentSerializer.gson().serialize(component));
    }

    private void writeNullableString(DataOutputStream output, String value) throws Exception {
        output.writeBoolean(value != null);
        if (value != null) {
            output.writeUTF(value);
        }
    }

    @FunctionalInterface
    private interface SnapshotWriter {
        void write(DataOutputStream output) throws Exception;
    }
}
