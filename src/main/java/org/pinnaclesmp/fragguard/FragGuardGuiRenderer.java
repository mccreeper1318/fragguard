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
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

final class FragGuardGuiRenderer {
    static final int RESULTS_PER_PAGE = 36;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MMM d, yyyy h:mm:ss a")
            .withZone(ZoneId.systemDefault());

    private FragGuardGuiRenderer() {
    }

    static Inventory mainMenu() {
        Inventory inv = Bukkit.createInventory(null, 27, color("&8FragGuard"));
        inv.setItem(10, item(Material.SPYGLASS, "&bLookup History", "&7Browse retained history graphically."));
        inv.setItem(12, item(Material.RECOVERY_COMPASS, "&eRollback",
                "&7Use &f/fg rollback&7 while this", "&7part of the GUI is being built."));
        inv.setItem(14, item(Material.REDSTONE_TORCH, "&aStorage Status", "&7Run the existing status command."));
        inv.setItem(16, item(Material.BOOK, "&fCommand Help", "&7Show existing FragGuard commands."));
        inv.setItem(22, item(Material.BARRIER, "&cClose"));
        return inv;
    }

    static Inventory lookupSetup(int radius, String timeLabel) {
        Inventory inv = Bukkit.createInventory(null, 27, color("&8FragGuard &7• &bLookup"));
        fill(inv, Material.GRAY_STAINED_GLASS_PANE);
        inv.setItem(10, item(Material.GRASS_BLOCK, "&bRadius: &f" + radius + " blocks",
                "&7Left-click: next preset", "&7Right-click: previous preset"));
        inv.setItem(12, item(Material.CLOCK, "&bTime: &f" + timeLabel,
                "&7Left-click: next preset", "&7Right-click: previous preset"));
        inv.setItem(14, item(Material.PAPER, "&7Result Mode",
                "&fCondensed activities by default", "&7with exact raw events always available."));
        inv.setItem(16, item(Material.EMERALD_BLOCK, "&aRun Lookup", "&7Load the selected history window."));
        inv.setItem(18, item(Material.ARROW, "&fBack"));
        inv.setItem(26, item(Material.BARRIER, "&cClose"));
        return inv;
    }

    static RenderedResults results(List<LookupRow> rows, List<LookupActivity> activities, boolean grouped, int requestedPage) {
        Inventory inv = Bukkit.createInventory(null, 54, color("&8FragGuard &7• &bResults"));
        fillRange(inv, 0, 8, Material.BLACK_STAINED_GLASS_PANE);
        fillRange(inv, 45, 53, Material.BLACK_STAINED_GLASS_PANE);
        int count = grouped ? activities.size() : rows.size();
        int pages = Math.max(1, (int) Math.ceil(count / (double) RESULTS_PER_PAGE));
        int page = Math.max(0, Math.min(requestedPage, pages - 1));
        int start = page * RESULTS_PER_PAGE;
        int end = Math.min(count, start + RESULTS_PER_PAGE);

        inv.setItem(0, item(Material.BUNDLE, "&b" + rows.size() + " Exact Events",
                "&7Grouping never deletes a stored event."));
        inv.setItem(4, item(grouped ? Material.BUNDLE : Material.WRITABLE_BOOK,
                "&eView: &f" + (grouped ? "Condensed Activities" : "Exact Raw Events"), "&7Click to toggle views."));
        inv.setItem(8, item(Material.BARRIER, "&cClose"));

        List<LookupActivity> visible = List.of();
        if (grouped) {
            visible = activities.subList(start, end);
            for (int i = 0; i < visible.size(); i++) {
                inv.setItem(9 + i, activityItem(visible.get(i)));
            }
        } else {
            for (int i = start; i < end; i++) {
                inv.setItem(9 + i - start, rawEventItem(rows.get(i)));
            }
        }

        inv.setItem(45, item(Material.ARROW, "&fNew Lookup"));
        if (page > 0) {
            inv.setItem(48, item(Material.SPECTRAL_ARROW, "&fPrevious Page"));
        }
        inv.setItem(49, item(Material.MAP, "&bPage " + (page + 1) + " / " + pages,
                "&f" + rows.size() + " &7events", "&f" + activities.size() + " &7activities"));
        if (page + 1 < pages) {
            inv.setItem(50, item(Material.SPECTRAL_ARROW, "&fNext Page"));
        }
        return new RenderedResults(inv, visible, page, pages);
    }

    static Inventory activityDetail(LookupActivity activity) {
        Inventory inv = Bukkit.createInventory(null, 27, color("&8FragGuard &7• &eActivity"));
        fill(inv, Material.GRAY_STAINED_GLASS_PANE);
        inv.setItem(10, item(Material.PLAYER_HEAD, "&b" + actor(activity.actorName()), "&7Recorded actor"));
        inv.setItem(12, item(icon(activity.materialKey()), "&f" + LookupActivityGrouper.displayMaterial(activity.materialKey()),
                "&f" + activity.eventCount() + " &7exact event(s)", "&7Action: &f" + activity.action().displayPastTense()));
        inv.setItem(14, item(Material.CLOCK, "&eTime Span",
                "&7Newest: &f" + TIME.format(Instant.ofEpochMilli(activity.newestAt())),
                "&7Oldest: &f" + TIME.format(Instant.ofEpochMilli(activity.oldestAt()))));
        inv.setItem(16, item(Material.COMPASS, "&eAffected Area",
                "&7X: &f" + activity.minX() + " &7to &f" + activity.maxX(),
                "&7Y: &f" + activity.minY() + " &7to &f" + activity.maxY(),
                "&7Z: &f" + activity.minZ() + " &7to &f" + activity.maxZ()));
        inv.setItem(18, item(Material.ARROW, "&fBack"));
        inv.setItem(22, item(Material.WRITTEN_BOOK, "&bView All Raw Events",
                "&7Shows every exact row represented", "&7by this condensed activity."));
        return inv;
    }

    static RenderedRaw activityRaw(LookupActivity activity, int requestedPage) {
        Inventory inv = Bukkit.createInventory(null, 54, color("&8FragGuard &7• &fRaw Events"));
        List<LookupRow> rows = activity.rows();
        int pages = Math.max(1, (int) Math.ceil(rows.size() / 45.0));
        int page = Math.max(0, Math.min(requestedPage, pages - 1));
        int start = page * 45;
        int end = Math.min(rows.size(), start + 45);
        for (int i = start; i < end; i++) {
            inv.setItem(i - start, rawEventItem(rows.get(i)));
        }
        fillRange(inv, 45, 53, Material.BLACK_STAINED_GLASS_PANE);
        inv.setItem(45, item(Material.ARROW, "&fBack to Activity"));
        if (page > 0) {
            inv.setItem(48, item(Material.SPECTRAL_ARROW, "&fPrevious Page"));
        }
        inv.setItem(49, item(Material.MAP, "&bPage " + (page + 1) + " / " + pages,
                "&f" + rows.size() + " &7exact event(s)"));
        if (page + 1 < pages) {
            inv.setItem(50, item(Material.SPECTRAL_ARROW, "&fNext Page"));
        }
        return new RenderedRaw(inv, page, pages);
    }

    private static ItemStack activityItem(LookupActivity activity) {
        return item(icon(activity.materialKey()),
                "&b" + actor(activity.actorName()) + " &7• &f" + activity.eventCount() + " "
                        + LookupActivityGrouper.displayMaterial(activity.materialKey()),
                "&7Action: &f" + activity.action().displayPastTense(),
                "&7Newest: &f" + TIME.format(Instant.ofEpochMilli(activity.newestAt())),
                "", "&eClick for details and raw events");
    }

    private static ItemStack rawEventItem(LookupRow row) {
        String material = LookupActivityGrouper.materialKey(row);
        return item(icon(material),
                "&b" + actor(row.actorName()) + " &7" + row.action().displayPastTense() + " &f"
                        + LookupActivityGrouper.displayMaterial(material),
                "&7Time: &f" + TIME.format(Instant.ofEpochMilli(row.happenedAt())),
                "&7Location: &f" + row.x() + " " + row.y() + " " + row.z(),
                "&7Before: &f" + state(row.beforeData()), "&7After: &f" + state(row.afterData()));
    }

    private static ItemStack item(Material material, String name, String... lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.setDisplayName(color(name));
        if (lore.length > 0) {
            meta.setLore(Arrays.stream(lore).map(FragGuardGuiRenderer::color).toList());
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

    record RenderedResults(Inventory inventory, List<LookupActivity> visibleActivities, int page, int totalPages) {
    }

    record RenderedRaw(Inventory inventory, int page, int totalPages) {
    }
}
