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
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests real inventory-slot assignment and per-item metadata in each layout.
 * Paper's item registry is unavailable in JUnit, so the icon resolver uses its
 * predicate seam while ItemStack construction is mocked.
 */
class GuiPagedInventoryRegressionTest {
    private static final String[] NON_ITEM = {
            "minecraft:water_cauldron", "minecraft:moving_piston",
            "minecraft:water", "minecraft:lava", "minecraft:fire",
            "minecraft:redstone_wire", "minecraft:air",
            "minecraft:stale_unknown_block"
    };

    @Test
    void groupedRawAndAll45ActivityRawSlotsKeepOriginalHistoryAndFallbackIcons() {
        List<GuiActivitySummary> grouped = new ArrayList<>();
        List<LookupRow> raw = new ArrayList<>();
        long now = System.currentTimeMillis();

        // Results display 36 events in slots 9–44; activity raw uses 45
        // events in slots 0–44. Unique ordinals expose truncated/misplaced rows.
        for (int i = 0; i < 45; i++) {
            String material = NON_ITEM[i % NON_ITEM.length];
            String before = material + "[test_state=7,ordinal=" + i + "]";
            GuiLookupCursor at = new GuiLookupCursor(now - i, 9_000L - i);
            if (i < 36) {
                grouped.add(new GuiActivitySummary(
                        UUID.randomUUID().toString(), "Builder", ChangeAction.BREAK, material,
                        now - i, now - i, 1, 64, 1, 1, 64, 1, 1, at, at));
            }
            raw.add(new LookupRow(9_000L - i, now - i, UUID.randomUUID().toString(),
                    "Builder", "world", 1, 64, 1, ChangeAction.BREAK,
                    before, "minecraft:air", false));
        }

        Inventory inventory = mock(Inventory.class);
        Map<Integer, ItemStack> placedSlots = new java.util.HashMap<>();
        Map<ItemStack, ItemDetails> details = new IdentityHashMap<>();
        doAnswer(invocation -> {
            placedSlots.put(invocation.getArgument(0, Integer.class),
                    invocation.getArgument(1, ItemStack.class));
            return null;
        }).when(inventory).setItem(anyInt(), any(ItemStack.class));

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedStatic<GuiMaterialIcon> icons = mockStatic(GuiMaterialIcon.class);
             MockedConstruction<ItemStack> stacks = mockConstruction(ItemStack.class,
                     (stack, context) -> {
                         ItemDetails item = new ItemDetails((Material) context.arguments().getFirst());
                         details.put(stack, item);
                         ItemMeta meta = mock(ItemMeta.class);
                         when(stack.getItemMeta()).thenReturn(meta);
                         doAnswer(invocation -> {
                             item.name = plain(invocation.getArgument(0, Component.class));
                             return null;
                         }).when(meta).displayName(any(Component.class));
                         doAnswer(invocation -> {
                             List<Component> lore = invocation.getArgument(0);
                             item.lore = lore.stream().map(GuiPagedInventoryRegressionTest::plain).toList();
                             return null;
                         }).when(meta).lore(anyList());
                     })) {
            bukkit.when(() -> Bukkit.createInventory(isNull(), eq(54), any(Component.class)))
                    .thenReturn(inventory);

            // Force inventory-unsafe blocks to PAPER through the real resolver
            // with a false item-eligibility predicate (Paper registry is absent).
            icons.when(() -> GuiMaterialIcon.fromHistory(anyString())).thenAnswer(invocation -> {
                String key = invocation.getArgument(0, String.class);
                String bare = key.toLowerCase(Locale.ROOT);
                if (bare.startsWith("minecraft:")) bare = bare.substring(10);
                Material material = Material.matchMaterial(bare);
                return material == null ? Material.PAPER
                        : GuiMaterialIcon.fromHistory(key, ignored -> false);
            });
            icons.when(() -> GuiMaterialIcon.fromHistory(anyString(), any())).thenCallRealMethod();
            icons.when(() -> GuiMaterialIcon.asItem(any(Material.class), any())).thenCallRealMethod();
            // All caller-supplied icons must now be safe; avoid Material.isItem
            // on JUnit's unbootstrapped Paper registry.
            icons.when(() -> GuiMaterialIcon.asItem(any(Material.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0, Material.class));

            // 36 grouped activity entries. Verify every event slot here before
            // clearing the captured inventory assignments for the next layout.
            assertSame(inventory, FragGuardPagedLookupRenderer.results(
                    List.of(), grouped, true, 2, 481, 481,
                    "No filters applied", false, true, true));
            assertEventSlots(placedSlots, details, 9, 36, false);
            assertEquals("View: Condensed Activities", details.get(placedSlots.get(4)).name);
            assertTrue(details.get(placedSlots.get(9)).lore.stream()
                    .anyMatch(s -> s.contains("Click for details and raw events")));
            placedSlots.clear();

            // 36 exact raw history entries. Each slot must independently
            // preserve its own Before/After lore, not rely on grouped metadata.
            assertSame(inventory, FragGuardPagedLookupRenderer.results(
                    raw.subList(0, 36), List.of(), false, 2, 481, 481,
                    "No filters applied", false, true, true));
            assertEventSlots(placedSlots, details, 9, 36, true);
            assertEquals("View: Exact Raw Events", details.get(placedSlots.get(4)).name);
            placedSlots.clear();

            // 45 actual events, NOT the 36-event results fixture. The final
            // nine slots must be populated with their own exact event data.
            assertSame(inventory, FragGuardPagedLookupRenderer.activityRaw(
                    raw, 2, 109, true, true));
            assertEventSlots(placedSlots, details, 0, 45, true);
            assertEquals("Page 3 / 3", details.get(placedSlots.get(49)).name);
            assertTrue(placedSlots.containsKey(44),
                    "the final activity-raw event slot must not be silently truncated");
            assertTrue(details.get(placedSlots.get(44)).lore.stream()
                    .anyMatch(s -> s.contains("ordinal=44]")));
        }
    }

    private static void assertEventSlots(Map<Integer, ItemStack> slots,
                                         Map<ItemStack, ItemDetails> details,
                                         int firstSlot, int count, boolean exactRaw) {
        for (int index = 0; index < count; index++) {
            int slot = firstSlot + index;
            ItemStack stack = slots.get(slot);
            assertNotNull(stack, "missing event at inventory slot " + slot);
            ItemDetails item = details.get(stack);
            assertNotNull(item, "missing captured metadata at inventory slot " + slot);
            String key = NON_ITEM[index % NON_ITEM.length];
            assertEquals(Material.PAPER, item.icon,
                    "non-item history needs a safe icon at inventory slot " + slot);
            assertNotNull(item.name);
            assertTrue(item.name.contains(LookupActivityGrouper.displayMaterial(key)),
                    "original historical block label missing at slot " + slot + ": " + item.name);
            if (exactRaw) {
                int eventIndex = index;
                assertTrue(item.lore.stream().anyMatch(s -> s.contains("Before:")
                                && s.contains("test_state=7") && s.contains("ordinal=" + eventIndex + "]")),
                        "before-state history missing/mismatched at slot " + slot);
                assertTrue(item.lore.stream().anyMatch(s -> s.contains("After:")
                                && s.contains("air")),
                        "after-state history missing at slot " + slot);
            } else {
                assertTrue(item.lore.stream().anyMatch(s -> s.contains("Action:")),
                        "grouped activity details missing at slot " + slot);
            }
        }
        assertEquals(count, slots.keySet().stream()
                        .filter(slot -> slot >= firstSlot && slot < firstSlot + count).count(),
                "the renderer must populate every event slot independently");
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    private static final class ItemDetails {
        private final Material icon;
        private String name;
        private List<String> lore = List.of();

        private ItemDetails(Material icon) {
            this.icon = icon;
        }
    }
}
