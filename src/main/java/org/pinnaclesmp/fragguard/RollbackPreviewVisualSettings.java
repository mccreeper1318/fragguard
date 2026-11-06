package org.pinnaclesmp.fragguard;

import org.bukkit.Color;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Validated, bounded settings for the client-only rollback particle display.
 * A missing configuration key keeps the built-in defaults, including in tests.
 */
record RollbackPreviewVisualSettings(
        boolean enabled,
        int quality,
        int verticalSpacing,
        boolean operatorRing,
        long updateTicks,
        Color borderColor,
        Color heightColor,
        Color centerColor
) {
    static final int DEFAULT_QUALITY = 1;
    static final int DEFAULT_VERTICAL_SPACING = 64;
    static final long DEFAULT_UPDATE_TICKS = 20;

    static RollbackPreviewVisualSettings from(FileConfiguration config) {
        return new RollbackPreviewVisualSettings(
                bool(config, "rollback-preview-visualization-enabled", true),
                clamp(integer(config, "rollback-preview-perimeter-quality", DEFAULT_QUALITY), 0, 2),
                clamp(integer(config, "rollback-preview-vertical-spacing", DEFAULT_VERTICAL_SPACING), 16, 192),
                bool(config, "rollback-preview-operator-ring-enabled", true),
                clamp(integer(config, "rollback-preview-update-ticks", (int) DEFAULT_UPDATE_TICKS), 10, 100),
                color(config, "rollback-preview-border-color", Color.AQUA),
                color(config, "rollback-preview-height-color", Color.YELLOW),
                color(config, "rollback-preview-center-color", Color.LIME));
    }

    int samples(int radius) {
        int base = radius <= 10 ? 24 : radius <= 25 ? 32 : radius <= 50 ? 48 : 64;
        int difference = switch (quality) {
            case 0 -> -8;
            case 2 -> 16;
            default -> 0;
        };
        return clamp(base + difference, 16, 96);
    }

    int verticalSections(int minHeight, int maxHeight) {
        if (maxHeight <= minHeight) {
            return 0;
        }
        // Always finite even in custom-height worlds; no per-block vertical loop.
        double height = maxHeight - (double) minHeight - 1.0;
        return clamp((int) Math.ceil(height / verticalSpacing), 2, 13);
    }

    private static boolean bool(FileConfiguration config, String key, boolean fallback) {
        return config != null && config.isSet(key) ? config.getBoolean(key, fallback) : fallback;
    }

    private static int integer(FileConfiguration config, String key, int fallback) {
        return config != null && config.isSet(key) ? config.getInt(key, fallback) : fallback;
    }

    private static Color color(FileConfiguration config, String key, Color fallback) {
        if (config == null || !config.isSet(key)) {
            return fallback;
        }
        String value = config.getString(key, "");
        if (value == null || !value.matches("#[0-9a-fA-F]{6}")) {
            return fallback;
        }
        return Color.fromRGB(Integer.parseInt(value.substring(1), 16));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
