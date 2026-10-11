package org.pinnaclesmp.fragguard;

import org.bukkit.Material;

import java.util.Locale;
import java.util.function.Predicate;

/**
 * Converts stored block material keys into inventory-safe display icons.
 *
 * <p>Block history can contain materials such as WATER, WATER_CAULDRON, and
 * MOVING_PISTON that are valid world blocks but cannot be used as ItemStacks.
 * The display icon may fall back to PAPER; the original history material and
 * before/after block data must remain unchanged in the lookup.</p>
 */
final class GuiMaterialIcon {
    private GuiMaterialIcon() {
    }

    static Material fromHistory(String key) {
        return fromHistory(key, Material::isItem);
    }

    // Package-private seam for tests: Paper's item registry is unavailable without a running server.
    static Material fromHistory(String key, Predicate<Material> isItem) {
        if (key == null || key.isBlank()) {
            return Material.PAPER;
        }

        String normalized = key.trim();
        if (normalized.regionMatches(true, 0, "minecraft:", 0, "minecraft:".length())) {
            normalized = normalized.substring("minecraft:".length());
        }
        return asItem(Material.matchMaterial(normalized.toUpperCase(Locale.ROOT)), isItem);
    }

    static Material asItem(Material material) {
        return asItem(material, Material::isItem);
    }

    static Material asItem(Material material, Predicate<Material> isItem) {
        return material != null && isItem.test(material) ? material : Material.PAPER;
    }
}
