package org.pinnaclesmp.fragguard;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Rendering for database-backed lookup pages that are already bounded before reaching the server thread. */
final class FragGuardPagedLookupRenderer {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MMM d, yyyy h:mm:ss a")
            .withZone(ZoneId.systemDefault());

    private FragGuardPagedLookupRenderer() {
    }

    static Inventory results(
            List<LookupRow> rows,
            List<GuiActivitySummary> activities,
            boolean grouped,
            int page,
            long matchingRows,
            long totalRows,
            String filterSummary,
            boolean filtersActive,
            boolean hasPrevious,
            boolean hasNext
    ) {
        Inventory inv = Bukkit.createInventory(null, 54, color("&8FragGuard &7• &bResults"));
        fillRange(inv, 0, 8, Material.BLACK_STAINED_GLASS_PANE);
        fillRange(inv, 45, 53, Material.BLACK_STAINED_GLASS_PANE);

        String eventCount = filtersActive
                ? matchingRows + " / " + totalRows
                : Long.toString(matchingRows);
        inv.setItem(0, item(Material.BUNDLE, "&b" + eventCount + " Exact Events",
                filtersActive
                        ? "&7Filtered events / total lookup events."
                        : "&7All matching events remain database-backed."));
        inv.setItem(2, item(Material.HOPPER, "&eLookup Filters",
                "&7" + truncate(filterSummary), "", "&eClick to configure."));
        inv.setItem(4, item(grouped ? Material.BUNDLE : Material.WRITABLE_BOOK,
                "&eView: &f" + (grouped ? "Condensed Activities" : "Exact Raw Events"),
                "&7Click to toggle views."));
        if (filtersActive) {
            inv.setItem(6, item(Material.MILK_BUCKET, "&fClear Filters",
                    "&7Restore every event from this lookup."));
        }
        inv.setItem(8, item(Material.BARRIER, "&cClose"));

        if (grouped) {
            for (int i = 0; i < activities.size() && i < FragGuardGuiRenderer.RESULTS_PER_PAGE; i++) {
                inv.setItem(9 + i, activityItem(activities.get(i)));
            }
        } else {
            for (int i = 0; i < rows.size() && i < FragGuardGuiRenderer.RESULTS_PER_PAGE; i++) {
                inv.setItem(9 + i, rawEventItem(rows.get(i)));
            }
        }

        inv.setItem(45, item(Material.ARROW, "&fNew Lookup"));
        if (hasPrevious) {
            inv.setItem(48, item(Material.SPECTRAL_ARROW, "&fPrevious Page"));
        }
        List<String> pageLore = new ArrayList<>();
        pageLore.add("&f" + matchingRows + " &7matching exact event(s)");
        if (grouped) {
            pageLore.add("&7Activities are streamed from SQLite.");
        } else {
            long totalPages = Math.max(1L,
                    (matchingRows + FragGuardGuiRenderer.RESULTS_PER_PAGE - 1L)
                            / FragGuardGuiRenderer.RESULTS_PER_PAGE);
            pageLore.add("&7Exact page " + (page + 1) + " / " + totalPages);
        }
        inv.setItem(49, item(Material.MAP, "&bPage " + (page + 1), pageLore));
        if (hasNext) {
            inv.setItem(50, item(Material.SPECTRAL_ARROW, "&fNext Page"));
        }
        return inv;
    }

    static Inventory activityDetail(GuiActivitySummary activity) {
        Inventory inv = Bukkit.createInventory(null, 27, color("&8FragGuard &7• &eActivity"));
        fill(inv, Material.GRAY_STAINED_GLASS_PANE);
        inv.setItem(10, item(Material.PLAYER_HEAD, "&b" + actor(activity.actorName()), "&7Recorded actor"));
        inv.setItem(12, item(icon(activity.materialKey()),
                "&f" + LookupActivityGrouper.displayMaterial(activity.materialKey()),
                "&f" + activity.eventCount() + " &7exact event(s)",
                "&7Action: &f" + activity.action().displayPastTense()));
        inv.setItem(14, item(Material.CLOCK, "&eTime Span",
                "&7Newest: &f" + TIME.format(Instant.ofEpochMilli(activity.newestAt())),
                "&7Oldest: &f" + TIME.format(Instant.ofEpochMilli(activity.oldestAt()))));
        inv.setItem(16, item(Material.COMPASS, "&eAffected Area",
                "&7X: &f" + activity.minX() + " &7to &f" + activity.maxX(),
                "&7Y: &f" + activity.minY() + " &7to &f" + activity.maxY(),
                "&7Z: &f" + activity.minZ() + " &7to &f" + activity.maxZ()));
        inv.setItem(18, item(Material.ARROW, "&fBack"));
        inv.setItem(22, item(Material.WRITTEN_BOOK, "&bView All Raw Events",
                "&7Pages exact rows directly from SQLite.",
                "&7No represented event is discarded."));
        return inv;
    }

    static Inventory activityRaw(
            List<LookupRow> rows,
            int page,
            int totalEvents,
            boolean hasPrevious,
            boolean hasNext
    ) {
        Inventory inv = Bukkit.createInventory(null, 54, color("&8FragGuard &7• &fRaw Events"));
        for (int i = 0; i < rows.size() && i < 45; i++) {
            inv.setItem(i, rawEventItem(rows.get(i)));
        }
        fillRange(inv, 45, 53, Material.BLACK_STAINED_GLASS_PANE);
        inv.setItem(45, item(Material.ARROW, "&fBack to Activity"));
        if (hasPrevious) {
            inv.setItem(48, item(Material.SPECTRAL_ARROW, "&fPrevious Page"));
        }
        int pages = Math.max(1, (int) Math.ceil(totalEvents / 45.0));
        inv.setItem(49, item(Material.MAP, "&bPage " + (page + 1) + " / " + pages,
                "&f" + totalEvents + " &7exact event(s)"));
        if (hasNext) {
            inv.setItem(50, item(Material.SPECTRAL_ARROW, "&fNext Page"));
        }
        return inv;
    }

    private static ItemStack activityItem(GuiActivitySummary activity) {
        return item(icon(activity.materialKey()),
                "&b" + actor(activity.actorName()) + " &7• &f" + activity.eventCount() + " "
                        + LookupActivityGrouper.displayMaterial(activity.materialKey()),
                "&7Action: &f" + activity.action().displayPastTense(),
                "&7Newest: &f" + TIME.format(Instant.ofEpochMilli(activity.newestAt())),
                "", "&eClick for details and raw events");
    }

    private static ItemStack rawEventItem(LookupRow row) {
        String material = LookupActivityGrouper.materialKey(row);
        List<String> lore = new ArrayList<>(List.of(
                "&7Time: &f" + TIME.format(Instant.ofEpochMilli(row.happenedAt())),
                "&7Location: &f" + row.x() + " " + row.y() + " " + row.z(),
                "&7Before: &f" + state(row.beforeData()),
                "&7After: &f" + state(row.afterData())));
        if (row.blockEntityDataPresent()) {
            lore.add("");
            lore.add("&6Block-entity details available");
        }
        lore.add("");
        lore.add("&eClick for exact event details");
        return item(icon(material),
                "&b" + actor(row.actorName()) + " &7" + row.action().displayPastTense() + " &f"
                        + LookupActivityGrouper.displayMaterial(material), lore);
    }

    private static ItemStack item(Material material, String name, String... lore) {
        return item(material, name, Arrays.asList(lore));
    }

    private static ItemStack item(Material material, String name, List<String> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.setDisplayName(color(name));
        if (!lore.isEmpty()) {
            meta.setLore(lore.stream().map(FragGuardPagedLookupRenderer::color).toList());
        }
        stack.setItemMeta(meta);
        return stack;
    }

    private static Material icon(String key) {
        if (key == null) {
            return Material.PAPER;
        }
        Material material = Material.matchMaterial(key.replace("minecraft:", "").toUpperCase(Locale.ROOT));
        return material == null || material.isAir() ? Material.PAPER : material;
    }

    private static String actor(String value) {
        return value == null || value.isBlank() ? "Unknown" : value;
    }

    private static String state(String value) {
        return value == null ? "unknown" : value.replace("minecraft:", "");
    }

    private static String truncate(String value) {
        int maximum = 72;
        if (value.length() <= maximum) {
            return value;
        }
        return value.substring(0, maximum - 3) + "...";
    }

    private static String color(String value) {
        return ChatColor.translateAlternateColorCodes('&', value);
    }

    private static void fill(Inventory inv, Material material) {
        for (int i = 0; i < inv.getSize(); i++) {
            inv.setItem(i, item(material, " "));
        }
    }

    private static void fillRange(Inventory inv, int from, int to, Material material) {
        for (int i = from; i <= to; i++) {
            inv.setItem(i, item(material, " "));
        }
    }
}
