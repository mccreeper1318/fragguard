package org.pinnaclesmp.fragguard;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuiMaterialIconTest {
    @ParameterizedTest
    @ValueSource(strings = {
            "WATER_CAULDRON", "MOVING_PISTON", "WATER",
            "LAVA", "FIRE", "REDSTONE_WIRE", "AIR"
    })
    void historicalNonItemBlocksUseSafeIconsInAllGuiPaths(String blockKey) {
        Material recorded = Material.matchMaterial(blockKey);
        assertNotNull(recorded);
        assertFalse(recorded.isItem(), blockKey + " is a block state, not a valid inventory item");

        String storedKey = "minecraft:" + blockKey.toLowerCase(Locale.ROOT);
        assertEquals(Material.PAPER, GuiMaterialIcon.fromHistory(storedKey));
        assertEquals(Material.PAPER, FragGuardPagedLookupRenderer.icon(storedKey),
                "paged results and activity details must not construct an invalid ItemStack");
        assertEquals(Material.PAPER, FragGuardGuiRenderer.icon(storedKey),
                "legacy GUI result and detail paths must use the same safe resolver");
        assertEquals(Material.PAPER,
                FragGuardGuiRenderer.filterIcon(LookupFilters.Category.MATERIAL, storedKey),
                "material filter options must be inventory-safe too");

        assertDoesNotThrow(() -> new ItemStack(FragGuardPagedLookupRenderer.icon(storedKey)));
        assertDoesNotThrow(() -> new ItemStack(FragGuardGuiRenderer.icon(storedKey)));
    }

    @Test
    void unknownBlankAndNullHistoryKeysUseSafeFallback() {
        assertEquals(Material.PAPER, GuiMaterialIcon.fromHistory(null));
        assertEquals(Material.PAPER, GuiMaterialIcon.fromHistory(""));
        assertEquals(Material.PAPER, GuiMaterialIcon.fromHistory("   "));
        assertEquals(Material.PAPER, GuiMaterialIcon.fromHistory("minecraft:not_a_real_block"));
        assertEquals(Material.PAPER, GuiMaterialIcon.asItem(null));
        assertEquals(Material.PAPER, GuiMaterialIcon.asItem(Material.WATER));
    }

    @Test
    void validInventoryMaterialsRetainTheirOriginalIcons() {
        assertEquals(Material.CHEST, GuiMaterialIcon.fromHistory("minecraft:chest"));
        assertEquals(Material.DIAMOND_BLOCK, GuiMaterialIcon.fromHistory("MINECRAFT:DIAMOND_BLOCK"));
        assertEquals(Material.OAK_LOG, GuiMaterialIcon.fromHistory("  minecraft:oak_log  "));
        assertEquals(Material.WATER_BUCKET, GuiMaterialIcon.fromHistory("minecraft:water_bucket"));
        assertEquals(Material.CHEST, GuiMaterialIcon.asItem(Material.CHEST));
        assertTrue(GuiMaterialIcon.fromHistory("minecraft:chest").isItem());
    }

    @Test
    void fallbackIconDoesNotChangeTheHistoryMaterialOrDisplayName() {
        LookupRow stored = new LookupRow(
                System.currentTimeMillis(), "Builder", "world", 10, 64, 10,
                ChangeAction.BREAK, "minecraft:water_cauldron[level=3]",
                "minecraft:water_cauldron[level=2]");

        String key = LookupActivityGrouper.materialKey(stored);
        assertEquals("minecraft:water_cauldron", key);
        assertEquals("Water Cauldron", LookupActivityGrouper.displayMaterial(key));
        assertEquals(Material.PAPER, GuiMaterialIcon.fromHistory(key));
        assertEquals("minecraft:water_cauldron[level=3]", stored.beforeData());
        assertEquals("minecraft:water_cauldron[level=2]", stored.afterData());
    }
}
