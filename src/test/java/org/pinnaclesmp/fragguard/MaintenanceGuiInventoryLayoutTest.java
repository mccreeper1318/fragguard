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

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Ensures the existing /fg menu and new confirmation UI expose the correct controls. */
class MaintenanceGuiInventoryLayoutTest {
    @Test
    void mainMenuLinksToMaintenanceAndDedicatedScreenHasAllSafeActions() {
        Inventory inventory = mock(Inventory.class);
        when(inventory.getSize()).thenReturn(54);
        Map<Integer, ItemStack> slots = new HashMap<>();
        Map<ItemStack, Detail> details = new IdentityHashMap<>();
        doAnswer(call -> {
            slots.put(call.getArgument(0), call.getArgument(1));
            return null;
        }).when(inventory).setItem(anyInt(), any(ItemStack.class));
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedStatic<GuiMaterialIcon> icons = mockStatic(GuiMaterialIcon.class);
             MockedConstruction<ItemStack> items = mockConstruction(ItemStack.class,
                     (stack, ignored) -> {
                         Detail d = new Detail();
                         details.put(stack, d);
                         ItemMeta meta = mock(ItemMeta.class);
                         when(stack.getItemMeta()).thenReturn(meta);
                         doAnswer(call -> {
                             d.name = plain(call.getArgument(0));
                             return null;
                         }).when(meta).displayName(any(Component.class));
                         doAnswer(call -> {
                             List<Component> lines = call.getArgument(0);
                             d.lore = lines.stream().map(MaintenanceGuiInventoryLayoutTest::plain).toList();
                             return null;
                         }).when(meta).lore(anyList());
                     })) {
            bukkit.when(() -> Bukkit.createInventory(isNull(), eq(27), any(Component.class)))
                    .thenReturn(inventory);
            bukkit.when(() -> Bukkit.createInventory(isNull(), eq(54), any(Component.class)))
                    .thenReturn(inventory);
            icons.when(() -> GuiMaterialIcon.asItem(any(Material.class)))
                    .thenAnswer(call -> call.getArgument(0));

            FragGuardGuiRenderer.mainMenu();
            assertTrue(details.get(slots.get(26)).name.contains("Database Maintenance"));

            slots.clear();
            var state = new MaintenanceMenuView(MaintenancePhase.VERIFYING,
                    MaintenancePolicy.STRICT, "full verification complete",
                    "operation", "fragguard-compact-test.db", true,
                    -1, -1, false, "finished", 42_000,
                    false, "verified", 120, 90_000, 40_000);
            FragGuardGuiRenderer.maintenanceMenu(state);
            for (int slot : new int[]{10, 12, 14, 16, 20, 22, 24, 28, 30, 32, 34, 45, 49, 53}) {
                assertNotNull(slots.get(slot), "Missing maintenance control at slot " + slot);
            }
            assertTrue(details.get(slots.get(14)).name.contains("Compaction Preview"));
            assertTrue(details.get(slots.get(30)).name.contains("Advanced: Replacement Policy"));
            assertTrue(details.get(slots.get(32)).name.contains("Automatic Activation"));
            assertTrue(details.get(slots.get(22)).lore.stream().anyMatch(
                    x -> x.contains("PERMANENTLY LOSE")));
            assertTrue(details.get(slots.get(16)).lore.stream().anyMatch(
                    x -> x.contains("No invented VACUUM")));
            assertTrue(details.get(slots.get(18)).lore.stream().anyMatch(
                    x -> x.contains("monitoring is disabled")));

            slots.clear();
            var advice = new ReclaimableSpaceMonitor.Snapshot(
                    java.time.Instant.parse("2026-11-16T12:00:00Z"),
                    2L << 20, 25.0, 1L << 20, 1000L, true);
            FragGuardGuiRenderer.maintenanceMenu(state, advice, true);
            assertTrue(details.get(slots.get(18)).lore.stream().anyMatch(
                    x -> x.contains("WAL size")));
            assertTrue(details.get(slots.get(18)).lore.stream().anyMatch(
                    x -> x.contains("NOT guaranteed")));
            assertTrue(details.get(slots.get(18)).lore.stream().anyMatch(
                    x -> x.contains("manual compaction")));

            slots.clear();
            FragGuardGuiRenderer.maintenanceConfirmation(
                    MaintenanceConfirmAction.APPROVE_SNAPSHOT);
            assertTrue(details.get(slots.get(13)).lore.stream().anyMatch(
                    x -> x.contains("FINAL LOSS CONSENT")));
            assertTrue(details.get(slots.get(15)).name.contains("Confirm"));
            assertTrue(details.get(slots.get(11)).name.contains("Back"));
        }
    }

    private static String plain(Component value) {
        return PlainTextComponentSerializer.plainText().serialize(value);
    }

    private static final class Detail {
        String name;
        List<String> lore = List.of();
    }
}
