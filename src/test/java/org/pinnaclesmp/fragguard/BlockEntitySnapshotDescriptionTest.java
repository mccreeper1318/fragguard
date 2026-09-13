package org.pinnaclesmp.fragguard;

import io.papermc.paper.block.TileStateInventoryHolder;
import net.kyori.adventure.text.Component;
import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class BlockEntitySnapshotDescriptionTest {
    @Test
    void describesContainerContentsWithoutExposingOpaqueBytes() {
        TileStateInventoryHolder source = mock(TileStateInventoryHolder.class);
        Inventory inventory = mock(Inventory.class);
        ItemStack diamonds = mock(ItemStack.class);
        ItemStack book = mock(ItemStack.class);
        ItemStack[] contents = new ItemStack[]{diamonds, null, book};
        byte[] serialized = new byte[]{4, 8, 15, 16, 23, 42};

        when(source.getSnapshotInventory()).thenReturn(inventory);
        when(inventory.getContents()).thenReturn(contents);
        when(diamonds.getType()).thenReturn(Material.DIAMOND);
        when(diamonds.getAmount()).thenReturn(12);
        when(book.getType()).thenReturn(Material.WRITTEN_BOOK);
        when(book.getAmount()).thenReturn(1);

        try (MockedStatic<ItemStack> itemStacks = mockStatic(ItemStack.class)) {
            itemStacks.when(() -> ItemStack.serializeItemsAsBytes(contents)).thenReturn(serialized);
            itemStacks.when(() -> ItemStack.deserializeItemsFromBytes(serialized)).thenReturn(contents);

            BlockEntitySnapshot.SnapshotDescription description =
                    BlockEntitySnapshot.describe(BlockEntitySnapshot.capture(source));

            assertEquals("Container", description.type());
            assertTrue(description.readable());
            assertTrue(description.details().contains("Items: 2 non-empty slot(s)"));
            assertTrue(description.details().contains("Slot 1: 12x Diamond"));
            assertTrue(description.details().contains("Slot 3: 1x Written Book"));
        }
    }

    @Test
    void describesBothSidesOfSignTextAndStyle() {
        Sign sign = mock(Sign.class);
        SignSide front = signSide(DyeColor.RED, true,
                Component.text("Front line"), Component.empty(), Component.empty(), Component.empty());
        SignSide back = signSide(DyeColor.BLUE, false,
                Component.text("Back line"), Component.empty(), Component.empty(), Component.empty());
        when(sign.isWaxed()).thenReturn(true);
        when(sign.getSide(Side.FRONT)).thenReturn(front);
        when(sign.getSide(Side.BACK)).thenReturn(back);

        BlockEntitySnapshot.SnapshotDescription description =
                BlockEntitySnapshot.describe(BlockEntitySnapshot.capture(sign));

        assertEquals("Sign", description.type());
        assertTrue(description.readable());
        assertTrue(description.details().contains("Waxed: Yes"));
        assertTrue(description.details().stream().anyMatch(line -> line.contains("Front line")));
        assertTrue(description.details().stream().anyMatch(line -> line.contains("Back line")));
        assertTrue(description.details().stream().anyMatch(line -> line.contains("Red, glowing")));
    }

    private SignSide signSide(DyeColor color, boolean glowing, Component... lines) {
        SignSide side = mock(SignSide.class);
        when(side.getColor()).thenReturn(color);
        when(side.isGlowingText()).thenReturn(glowing);
        when(side.lines()).thenReturn(Arrays.asList(lines));
        return side;
    }
}
