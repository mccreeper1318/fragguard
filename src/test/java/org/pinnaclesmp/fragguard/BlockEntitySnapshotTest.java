package org.pinnaclesmp.fragguard;

import com.destroystokyo.paper.profile.ProfileProperty;
import io.papermc.paper.block.TileStateInventoryHolder;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.kyori.adventure.text.Component;
import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.block.Banner;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.DecoratedPot;
import org.bukkit.block.Lectern;
import org.bukkit.block.Sign;
import org.bukkit.block.Skull;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.bukkit.inventory.DecoratedPotInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BlockEntitySnapshotTest {
    @Test
    void roundTripsBothSignSidesColorsGlowWaxAndComponents() {
        Sign source = mock(Sign.class);
        SignSide sourceFront = signSide(DyeColor.RED, true,
                Component.text("Front"), Component.empty(), Component.empty(), Component.empty());
        SignSide sourceBack = signSide(DyeColor.BLUE, false,
                Component.empty(), Component.text("Back"), Component.empty(), Component.empty());
        when(source.isWaxed()).thenReturn(true);
        when(source.getSide(Side.FRONT)).thenReturn(sourceFront);
        when(source.getSide(Side.BACK)).thenReturn(sourceBack);

        byte[] snapshot = BlockEntitySnapshot.capture(source);
        assertNotNull(snapshot);
        assertArrayEquals(snapshot, BlockEntitySnapshot.capture(source),
                "equivalent block-entity states must have deterministic compressed snapshots");

        Sign target = mock(Sign.class);
        SignSide targetFront = signSide(DyeColor.WHITE, false,
                Component.empty(), Component.empty(), Component.empty(), Component.empty());
        SignSide targetBack = signSide(DyeColor.WHITE, false,
                Component.empty(), Component.empty(), Component.empty(), Component.empty());
        when(target.getSide(Side.FRONT)).thenReturn(targetFront);
        when(target.getSide(Side.BACK)).thenReturn(targetBack);
        Block block = block(target);

        BlockEntitySnapshot.restore(block, snapshot);

        verify(target).setWaxed(true);
        verify(targetFront).setColor(DyeColor.RED);
        verify(targetFront).setGlowingText(true);
        verify(targetFront).line(0, Component.text("Front"));
        verify(targetBack).setColor(DyeColor.BLUE);
        verify(targetBack).line(1, Component.text("Back"));
        verify(target).update(true, false);
    }

    @Test
    void roundTripsContainerItemsAndBooksThroughPaperBinarySerialization() {
        TileStateInventoryHolder source = mock(TileStateInventoryHolder.class);
        Inventory sourceInventory = mock(Inventory.class);
        ItemStack[] items = new ItemStack[]{mock(ItemStack.class), null, mock(ItemStack.class)};
        when(source.getSnapshotInventory()).thenReturn(sourceInventory);
        when(sourceInventory.getContents()).thenReturn(items);

        TileStateInventoryHolder target = mock(TileStateInventoryHolder.class);
        Inventory targetInventory = mock(Inventory.class);
        when(target.getSnapshotInventory()).thenReturn(targetInventory);
        byte[] serializedItems = new byte[]{4, 8, 15, 16, 23, 42};

        try (MockedStatic<ItemStack> itemStacks = mockStatic(ItemStack.class)) {
            itemStacks.when(() -> ItemStack.serializeItemsAsBytes(items)).thenReturn(serializedItems);
            itemStacks.when(() -> ItemStack.deserializeItemsFromBytes(serializedItems)).thenReturn(items);
            BlockEntitySnapshot.restore(block(target), BlockEntitySnapshot.capture(source));
        }

        verify(targetInventory).setContents(items);
        verify(target).update(true, false);
    }

    @Test
    void restoresCompatibleInventoryAfterPhysicsNormalizesItsBlockState() {
        TileStateInventoryHolder source = mock(TileStateInventoryHolder.class);
        Inventory sourceInventory = mock(Inventory.class);
        ItemStack[] contents = new ItemStack[]{mock(ItemStack.class)};
        when(source.getSnapshotInventory()).thenReturn(sourceInventory);
        when(sourceInventory.getContents()).thenReturn(contents);

        TileStateInventoryHolder corrected = mock(TileStateInventoryHolder.class);
        Inventory correctedInventory = mock(Inventory.class);
        when(corrected.getSnapshotInventory()).thenReturn(correctedInventory);
        byte[] serializedContents = new byte[]{3, 1, 4, 1, 5};

        try (MockedStatic<ItemStack> itemStacks = mockStatic(ItemStack.class)) {
            itemStacks.when(() -> ItemStack.serializeItemsAsBytes(contents)).thenReturn(serializedContents);
            itemStacks.when(() -> ItemStack.deserializeItemsFromBytes(serializedContents)).thenReturn(contents);

            assertTrue(BlockEntitySnapshot.restoreIfCompatible(block(corrected),
                    BlockEntitySnapshot.capture(source)));
        }

        verify(correctedInventory).setContents(contents);
        verify(corrected).update(true, false);
    }

    @Test
    void skipsSnapshotWhenPhysicsReplacesTheBlockWithAnIncompatibleState() {
        TileStateInventoryHolder source = mock(TileStateInventoryHolder.class);
        Inventory sourceInventory = mock(Inventory.class);
        ItemStack[] contents = new ItemStack[]{mock(ItemStack.class)};
        when(source.getSnapshotInventory()).thenReturn(sourceInventory);
        when(sourceInventory.getContents()).thenReturn(contents);
        byte[] serializedContents = new byte[]{2, 7, 1, 8};

        try (MockedStatic<ItemStack> itemStacks = mockStatic(ItemStack.class)) {
            itemStacks.when(() -> ItemStack.serializeItemsAsBytes(contents)).thenReturn(serializedContents);

            assertFalse(BlockEntitySnapshot.restoreIfCompatible(block(mock(BlockState.class)),
                    BlockEntitySnapshot.capture(source)));
        }
    }

    @Test
    void restoresLecternBookBeforeItsSavedPage() {
        Lectern source = mock(Lectern.class);
        Inventory sourceInventory = mock(Inventory.class);
        ItemStack[] book = new ItemStack[]{mock(ItemStack.class)};
        when(source.getSnapshotInventory()).thenReturn(sourceInventory);
        when(sourceInventory.getContents()).thenReturn(book);
        when(source.getPage()).thenReturn(12);

        Lectern target = mock(Lectern.class);
        Inventory targetInventory = mock(Inventory.class);
        when(target.getSnapshotInventory()).thenReturn(targetInventory);
        byte[] serializedBook = new byte[]{9, 7, 5};

        try (MockedStatic<ItemStack> itemStacks = mockStatic(ItemStack.class)) {
            itemStacks.when(() -> ItemStack.serializeItemsAsBytes(book)).thenReturn(serializedBook);
            itemStacks.when(() -> ItemStack.deserializeItemsFromBytes(serializedBook)).thenReturn(book);
            BlockEntitySnapshot.restore(block(target), BlockEntitySnapshot.capture(source));
        }

        var ordered = org.mockito.Mockito.inOrder(targetInventory, target);
        ordered.verify(targetInventory).setContents(book);
        ordered.verify(target).setPage(12);
        ordered.verify(target).update(true, false);
    }

    @Test
    void restoresDecoratedPotInventoryAndEveryNamedSherdSide() {
        DecoratedPot source = mock(DecoratedPot.class);
        DecoratedPotInventory sourceInventory = mock(DecoratedPotInventory.class);
        ItemStack[] contents = new ItemStack[]{mock(ItemStack.class)};
        when(source.getSnapshotInventory()).thenReturn(sourceInventory);
        when(sourceInventory.getContents()).thenReturn(contents);
        for (DecoratedPot.Side side : DecoratedPot.Side.values()) {
            when(source.getSherd(side)).thenReturn(side == DecoratedPot.Side.values()[0]
                    ? Material.ANGLER_POTTERY_SHERD : Material.BRICK);
        }

        DecoratedPot target = mock(DecoratedPot.class);
        DecoratedPotInventory targetInventory = mock(DecoratedPotInventory.class);
        when(target.getSnapshotInventory()).thenReturn(targetInventory);
        byte[] serializedContents = new byte[]{2, 4, 6};

        try (MockedStatic<ItemStack> itemStacks = mockStatic(ItemStack.class)) {
            itemStacks.when(() -> ItemStack.serializeItemsAsBytes(contents)).thenReturn(serializedContents);
            itemStacks.when(() -> ItemStack.deserializeItemsFromBytes(serializedContents)).thenReturn(contents);
            BlockEntitySnapshot.restore(block(target), BlockEntitySnapshot.capture(source));
        }

        verify(targetInventory).setContents(contents);
        for (DecoratedPot.Side side : DecoratedPot.Side.values()) {
            verify(target).setSherd(side, side == DecoratedPot.Side.values()[0]
                    ? Material.ANGLER_POTTERY_SHERD : Material.BRICK);
        }
        verify(target).update(true, false);
    }

    @Test
    void restoresUnpatternedBannersAndCustomNamesWithoutStartingTheServerRegistry() {
        Banner source = mock(Banner.class);
        Component name = Component.text("Battle flag");
        when(source.getPatterns()).thenReturn(List.of());
        when(source.customName()).thenReturn(name);

        Banner target = mock(Banner.class);

        BlockEntitySnapshot.restore(block(target), BlockEntitySnapshot.capture(source));

        verify(target).customName(name);
        verify(target).setPatterns(List.of());
        verify(target).update(true, false);
    }

    @Test
    void restoresPlayerHeadUuidNameAndSignedTextureProperties() {
        UUID uuid = UUID.fromString("563fce36-6445-43e9-9e79-3bb6d0780b13");
        ProfileProperty texture = new ProfileProperty("textures", "base64-skin", "mojang-signature");
        ResolvableProfile original = mock(ResolvableProfile.class);
        when(original.uuid()).thenReturn(uuid);
        when(original.name()).thenReturn("Builder");
        when(original.properties()).thenReturn(List.of(texture));
        Skull source = mock(Skull.class);
        when(source.getProfile()).thenReturn(original);

        Skull target = mock(Skull.class);
        ResolvableProfile restored = mock(ResolvableProfile.class);
        ResolvableProfile.Builder builder = mock(ResolvableProfile.Builder.class);
        when(builder.uuid(uuid)).thenReturn(builder);
        when(builder.name("Builder")).thenReturn(builder);
        when(builder.addProperty(any(ProfileProperty.class))).thenReturn(builder);
        when(builder.build()).thenReturn(restored);

        try (MockedStatic<ResolvableProfile> profiles = mockStatic(ResolvableProfile.class)) {
            profiles.when(ResolvableProfile::resolvableProfile).thenReturn(builder);
            BlockEntitySnapshot.restore(block(target), BlockEntitySnapshot.capture(source));
        }

        ArgumentCaptor<ProfileProperty> property = ArgumentCaptor.forClass(ProfileProperty.class);
        verify(builder).addProperty(property.capture());
        assertEquals("textures", property.getValue().getName());
        assertEquals("base64-skin", property.getValue().getValue());
        assertEquals("mojang-signature", property.getValue().getSignature());
        verify(target).setProfile(restored);
        verify(target).update(true, false);
    }

    @Test
    void ignoresUnsupportedBlocksAndRejectsUnknownSnapshotFormats() throws Exception {
        assertNull(BlockEntitySnapshot.capture((BlockState) null));
        assertNull(BlockEntitySnapshot.capture(mock(BlockState.class)));

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(new GZIPOutputStream(bytes))) {
            output.writeInt(0x46474245);
            output.writeByte(BlockEntitySnapshot.FORMAT_VERSION + 1);
        }

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> BlockEntitySnapshot.restore(block(mock(Sign.class)), bytes.toByteArray()));
        assertEquals("Unsupported block-entity snapshot format version 2", failure.getCause().getMessage());
    }

    private SignSide signSide(DyeColor color, boolean glowing, Component... lines) {
        SignSide side = mock(SignSide.class);
        when(side.getColor()).thenReturn(color);
        when(side.isGlowingText()).thenReturn(glowing);
        when(side.lines()).thenReturn(Arrays.asList(lines));
        return side;
    }

    private Block block(BlockState state) {
        Block block = mock(Block.class);
        when(block.getState()).thenReturn(state);
        when(state.update(true, false)).thenReturn(true);
        return block;
    }
}
