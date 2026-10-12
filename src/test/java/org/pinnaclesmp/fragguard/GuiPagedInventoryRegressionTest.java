package org.pinnaclesmp.fragguard;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Render both real 54-slot page layouts with Bukkit/ItemStack constructor
 * scaffolding. A Paper server registry is unavailable in JUnit, so icon
 * item-eligibility is injected through GuiMaterialIcon's tested predicate seam.
 */
class GuiPagedInventoryRegressionTest {
    private static final String[] NON_ITEM = {
            "minecraft:water_cauldron", "minecraft:moving_piston",
            "minecraft:water", "minecraft:lava", "minecraft:fire",
            "minecraft:redstone_wire", "minecraft:air",
            "minecraft:stale_unknown_block"
    };

    @Test
    void rawAndGroupedPageThreeRenderNonItemMaterialsWithTheirOriginalHistoryLabels() {
        List<GuiActivitySummary> grouped = new ArrayList<>();
        List<LookupRow> raw = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (int i = 0; i < 36; i++) {
            String material = NON_ITEM[i % NON_ITEM.length];
            String before = material + "[test_state=7]";
            GuiLookupCursor at = new GuiLookupCursor(now - i, 9_000L - i);
            grouped.add(new GuiActivitySummary(
                    UUID.randomUUID().toString(), "Builder", ChangeAction.BREAK, material,
                    now - i, now - i, 1, 64, 1, 1, 64, 1, 1, at, at));
            raw.add(new LookupRow(9_000L - i, now - i, UUID.randomUUID().toString(),
                    "Builder", "world", 1, 64, 1, ChangeAction.BREAK,
                    before, "minecraft:air", false));
        }

        Inventory inventory = mock(Inventory.class);
        ItemMeta meta = mock(ItemMeta.class);
        List<String> shownNames = new ArrayList<>();
        doAnswer(invocation -> {
            shownNames.add(PlainTextComponentSerializer.plainText()
                    .serialize(invocation.getArgument(0, Component.class)));
            return null;
        }).when(meta).displayName(any(Component.class));

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedStatic<GuiMaterialIcon> icons = mockStatic(GuiMaterialIcon.class);
             MockedConstruction<ItemStack> stacks = mockConstruction(ItemStack.class,
                     (item, context) -> when(item.getItemMeta()).thenReturn(meta))) {
            bukkit.when(() -> Bukkit.createInventory(isNull(), eq(54), any(Component.class)))
                    .thenReturn(inventory);
            // Material.isItem uses Paper's uninitialized registry in JUnit.
            // The *actual* resolver overload is invoked with the deterministic
            // non-item predicate; ItemStack construction is mocked.
            icons.when(() -> GuiMaterialIcon.fromHistory(anyString())).thenAnswer(invocation -> {
                String key = invocation.getArgument(0, String.class);
                String bare = key.toLowerCase(Locale.ROOT);
                if (bare.startsWith("minecraft:")) bare = bare.substring(10);
                Material material = Material.matchMaterial(bare);
                return material == null ? Material.PAPER
                        : GuiMaterialIcon.fromHistory(key, candidate -> false);
            });
            icons.when(() -> GuiMaterialIcon.fromHistory(anyString(), any())).thenCallRealMethod();
            icons.when(() -> GuiMaterialIcon.asItem(any(Material.class), any())).thenCallRealMethod();
            icons.when(() -> GuiMaterialIcon.asItem(any(Material.class))).thenReturn(Material.PAPER);

            assertSame(inventory, FragGuardPagedLookupRenderer.results(
                    List.of(), grouped, true, 2, 481, 481,
                    "No filters applied", false, true, true));
            assertSame(inventory, FragGuardPagedLookupRenderer.results(
                    raw, List.of(), false, 2, 481, 481,
                    "No filters applied", false, true, true));
            assertSame(inventory, FragGuardPagedLookupRenderer.activityRaw(
                    raw, 2, 109, true, true));
            // All original block names and before/after states are preserved in
            // display components even though icons fall back to PAPER.
            assertTrue(shownNames.stream().anyMatch(n -> n.contains("Water Cauldron")));
            assertTrue(shownNames.stream().anyMatch(n -> n.contains("Moving Piston")));
            assertTrue(shownNames.stream().anyMatch(n -> n.contains("Redstone Wire")));
            assertTrue(shownNames.stream().anyMatch(n -> n.contains("stale_unknown_block")
                    || n.contains("Stale Unknown Block")));
            assertTrue(shownNames.stream().anyMatch(n -> n.contains("test_state=7")));
            verify(inventory, atLeast(3)).setItem(eq(9), any(ItemStack.class));
            assertTrue(stacks.constructed().size() > 100,
                    "both 36-item results pages and drilldown must build actual inventory slots");
        }
    }
}
