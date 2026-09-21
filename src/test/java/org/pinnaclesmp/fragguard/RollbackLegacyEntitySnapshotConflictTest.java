package org.pinnaclesmp.fragguard;

import io.papermc.paper.block.TileStateInventoryHolder;
import net.kyori.adventure.text.Component;
import org.bukkit.DyeColor;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class RollbackLegacyEntitySnapshotConflictTest {
    @Test
    void legacyContainerSnapshotFailsClosedAgainstNewerLiveInventory() {
        TileStateInventoryHolder container = mock(TileStateInventoryHolder.class);
        Inventory inventory = mock(Inventory.class);
        ItemStack[] contents = new ItemStack[]{mock(ItemStack.class)};
        byte[] serializedContents = new byte[]{7, 0, 7, 0};

        when(container.getSnapshotInventory()).thenReturn(inventory);
        when(inventory.getContents()).thenReturn(contents);

        byte[] liveSnapshot;
        try (MockedStatic<ItemStack> itemStacks = mockStatic(ItemStack.class)) {
            itemStacks.when(() -> ItemStack.serializeItemsAsBytes(contents)).thenReturn(serializedContents);
            liveSnapshot = BlockEntitySnapshot.capture(container);
        }

        assertNotNull(liveSnapshot);
        assertFalse(FragGuardCommand.matchesState(
                "minecraft:chest", liveSnapshot, "minecraft:chest", null),
                "legacy missing inventory data must not wildcard a supported live container");
    }

    @Test
    void legacySignSnapshotFailsClosedAgainstNewerLiveText() {
        Sign sign = mock(Sign.class);
        SignSide front = signSide(DyeColor.WHITE, false,
                Component.text("newer text"), Component.empty(), Component.empty(), Component.empty());
        SignSide back = signSide(DyeColor.WHITE, false,
                Component.empty(), Component.empty(), Component.empty(), Component.empty());

        when(sign.isWaxed()).thenReturn(false);
        when(sign.getSide(Side.FRONT)).thenReturn(front);
        when(sign.getSide(Side.BACK)).thenReturn(back);

        byte[] liveSnapshot = BlockEntitySnapshot.capture(sign);

        assertNotNull(liveSnapshot);
        assertFalse(FragGuardCommand.matchesState(
                "minecraft:oak_sign", liveSnapshot, "minecraft:oak_sign", null),
                "legacy missing sign data must not wildcard supported live sign state");
    }

    @Test
    void ordinaryBlocksWithNoEntityStateStillMatchLegacyNullSnapshots() {
        assertTrue(FragGuardCommand.matchesState(
                "minecraft:stone", null, "minecraft:stone", null));
    }

    @Test
    void modernBlockEntitySnapshotsStillRequireExactEquality() {
        byte[] snapshot = new byte[]{1, 2, 3, 4};

        assertTrue(FragGuardCommand.matchesState(
                "minecraft:chest", snapshot, "minecraft:chest", snapshot.clone()));
        assertFalse(FragGuardCommand.matchesState(
                "minecraft:chest", snapshot, "minecraft:chest", new byte[]{4, 3, 2, 1}));
    }

    private SignSide signSide(DyeColor color, boolean glowing, Component... lines) {
        SignSide side = mock(SignSide.class);
        when(side.getColor()).thenReturn(color);
        when(side.isGlowingText()).thenReturn(glowing);
        when(side.lines()).thenReturn(Arrays.asList(lines));
        return side;
    }
}
