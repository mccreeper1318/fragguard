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
import java.util.Objects;

final class FragGuardGuiRenderer {
    static final int RESULTS_PER_PAGE = 36;
    static final int FILTER_OPTIONS_PER_PAGE = 45;
    static final int UNDO_JOBS_PER_PAGE = 36;
    private static final int MAX_BLOCK_ENTITY_DETAIL_LINES = 8;
    private static final int MAX_LORE_DETAIL_LENGTH = 72;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MMM d, yyyy h:mm:ss a")
            .withZone(ZoneId.systemDefault());

    private FragGuardGuiRenderer() {
    }

    static Inventory mainMenu() {
        Inventory inv = Bukkit.createInventory(null, 27, color("&8FragGuard"));
        inv.setItem(10, item(Material.SPYGLASS, "&bLookup History", "&7Browse retained history graphically."));
        inv.setItem(12, item(Material.RECOVERY_COMPASS, "&eRollback",
                "&7Configure, preview, and confirm", "&7an area rollback."));
        inv.setItem(14, item(Material.CLOCK, "&6Undo Rollback",
                "&7Browse rollback jobs that still", "&7have applied changes to undo."));
        inv.setItem(16, item(Material.REDSTONE_TORCH, "&aStorage Status", "&7Run the existing status command."));
        inv.setItem(20, item(Material.BOOK, "&fCommand Help", "&7Show existing FragGuard commands."));
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

    static RenderedResults results(List<LookupRow> rows, List<LookupActivity> activities,
                                   boolean grouped, int requestedPage, int totalRows,
                                   String filterSummary, boolean filtersActive) {
        Inventory inv = Bukkit.createInventory(null, 54, color("&8FragGuard &7• &bResults"));
        fillRange(inv, 0, 8, Material.BLACK_STAINED_GLASS_PANE);
        fillRange(inv, 45, 53, Material.BLACK_STAINED_GLASS_PANE);
        int count = grouped ? activities.size() : rows.size();
        int pages = Math.max(1, (int) Math.ceil(count / (double) RESULTS_PER_PAGE));
        int page = Math.max(0, Math.min(requestedPage, pages - 1));
        int start = page * RESULTS_PER_PAGE;
        int end = Math.min(count, start + RESULTS_PER_PAGE);

        String eventCount = filtersActive ? rows.size() + " / " + totalRows : Integer.toString(rows.size());
        inv.setItem(0, item(Material.BUNDLE, "&b" + eventCount + " Exact Events",
                filtersActive ? "&7Filtered events / total lookup events." : "&7Grouping never deletes a stored event."));
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

        List<LookupActivity> visibleActivities = List.of();
        List<LookupRow> visibleRows = List.of();
        if (grouped) {
            visibleActivities = activities.subList(start, end);
            for (int i = 0; i < visibleActivities.size(); i++) {
                inv.setItem(9 + i, activityItem(visibleActivities.get(i)));
            }
        } else {
            visibleRows = rows.subList(start, end);
            for (int i = 0; i < visibleRows.size(); i++) {
                inv.setItem(9 + i, rawEventItem(visibleRows.get(i)));
            }
        }

        inv.setItem(45, item(Material.ARROW, "&fNew Lookup"));
        if (page > 0) {
            inv.setItem(48, item(Material.SPECTRAL_ARROW, "&fPrevious Page"));
        }
        inv.setItem(49, item(Material.MAP, "&bPage " + (page + 1) + " / " + pages,
                "&f" + rows.size() + " &7matching event(s)", "&f" + activities.size() + " &7matching activities"));
        if (page + 1 < pages) {
            inv.setItem(50, item(Material.SPECTRAL_ARROW, "&fNext Page"));
        }
        return new RenderedResults(inv, visibleActivities, visibleRows, page, pages);
    }

    static Inventory lookupFilters(String playerFilter, String actionFilter, String materialFilter,
                                   boolean filtersActive) {
        Inventory inv = Bukkit.createInventory(null, 27, color("&8FragGuard &7• &eFilters"));
        fill(inv, Material.GRAY_STAINED_GLASS_PANE);
        inv.setItem(10, item(Material.PLAYER_HEAD, "&bPlayer: &f" + playerFilter,
                "&7Choose from actors returned", "&7by this exact lookup."));
        inv.setItem(12, item(Material.IRON_PICKAXE, "&bAction: &f" + actionFilter,
                "&7Choose from actions returned", "&7by this exact lookup."));
        inv.setItem(14, item(Material.GRASS_BLOCK, "&bMaterial: &f" + materialFilter,
                "&7Choose from materials returned", "&7by this exact lookup."));
        inv.setItem(16, item(filtersActive ? Material.MILK_BUCKET : Material.GRAY_DYE,
                filtersActive ? "&fClear All Filters" : "&7No Filters Active",
                filtersActive ? "&7Restore every event from this lookup." : "&7All lookup events are currently visible."));
        inv.setItem(18, item(Material.ARROW, "&fBack to Results"));
        inv.setItem(26, item(Material.BARRIER, "&cClose"));
        return inv;
    }

    static RenderedFilterOptions filterOptions(LookupFilters.Category category,
                                                List<LookupFilters.Option> options,
                                                String selectedKey,
                                                int totalRows,
                                                int requestedPage) {
        List<LookupFilters.Option> choices = new ArrayList<>(options.size() + 1);
        choices.add(new LookupFilters.Option(null, "Any", totalRows));
        choices.addAll(options);
        int pages = Math.max(1, (int) Math.ceil(choices.size() / (double) FILTER_OPTIONS_PER_PAGE));
        int page = Math.max(0, Math.min(requestedPage, pages - 1));
        int start = page * FILTER_OPTIONS_PER_PAGE;
        int end = Math.min(choices.size(), start + FILTER_OPTIONS_PER_PAGE);
        List<LookupFilters.Option> visible = List.copyOf(choices.subList(start, end));

        Inventory inv = Bukkit.createInventory(null, 54,
                color("&8FragGuard &7• &e" + category.displayName()));
        for (int i = 0; i < visible.size(); i++) {
            LookupFilters.Option option = visible.get(i);
            boolean selected = Objects.equals(selectedKey, option.key());
            Material icon = filterIcon(category, option.key());
            inv.setItem(i, item(icon,
                    (selected ? "&a✔ " : "&f") + option.label(),
                    "&7Matching exact events: &f" + option.count(),
                    selected ? "&aCurrently selected" : "&eClick to select"));
        }
        fillRange(inv, 45, 53, Material.BLACK_STAINED_GLASS_PANE);
        inv.setItem(45, item(Material.ARROW, "&fBack to Filters"));
        if (page > 0) {
            inv.setItem(48, item(Material.SPECTRAL_ARROW, "&fPrevious Page"));
        }
        inv.setItem(49, item(Material.MAP, "&bPage " + (page + 1) + " / " + pages,
                "&f" + options.size() + " &7returned option(s)"));
        if (page + 1 < pages) {
            inv.setItem(50, item(Material.SPECTRAL_ARROW, "&fNext Page"));
        }
        return new RenderedFilterOptions(inv, visible, page, pages);
    }

    static Inventory rollbackSetup(int radius, String timeLabel, boolean force, boolean activePreview) {
        Inventory inv = Bukkit.createInventory(null, 27, color("&8FragGuard &7• &eRollback"));
        fill(inv, Material.GRAY_STAINED_GLASS_PANE);
        inv.setItem(10, item(Material.GRASS_BLOCK, "&eRadius: &f" + radius + " blocks",
                "&7Left-click: next preset", "&7Right-click: previous preset"));
        inv.setItem(12, item(Material.CLOCK, "&eTime: &f" + timeLabel,
                "&7Left-click: next preset", "&7Right-click: previous preset"));
        inv.setItem(14, item(force ? Material.TNT : Material.SHIELD,
                force ? "&cForce Mode: ON" : "&aConflict Protection: ON",
                force ? "&cNewer conflicting states may be overwritten." : "&7Newer conflicting states will be skipped.",
                "&eClick to toggle."));
        inv.setItem(16, item(Material.SPYGLASS, "&ePreview Rollback",
                "&7Search the selected area first.", "&7No blocks change until confirmation."));
        if (activePreview) {
            inv.setItem(20, item(Material.EMERALD_BLOCK, "&aConfirm Active Preview",
                    "&7Run your most recent unexpired", "&7rollback preview."));
        } else {
            inv.setItem(20, item(Material.GRAY_DYE, "&7No Active Preview",
                    "&7Run a rollback preview first."));
        }
        inv.setItem(18, item(Material.ARROW, "&fBack"));
        inv.setItem(26, item(Material.BARRIER, "&cClose"));
        return inv;
    }

    static RenderedUndoJobs undoJobs(List<GuiRollbackJob> jobs, int requestedPage) {
        Inventory inv = Bukkit.createInventory(null, 54, color("&8FragGuard &7• &6Undo"));
        fillRange(inv, 0, 8, Material.BLACK_STAINED_GLASS_PANE);
        fillRange(inv, 45, 53, Material.BLACK_STAINED_GLASS_PANE);
        int pages = Math.max(1, (int) Math.ceil(jobs.size() / (double) UNDO_JOBS_PER_PAGE));
        int page = Math.max(0, Math.min(requestedPage, pages - 1));
        int start = page * UNDO_JOBS_PER_PAGE;
        int end = Math.min(jobs.size(), start + UNDO_JOBS_PER_PAGE);
        List<GuiRollbackJob> visible = List.copyOf(jobs.subList(start, end));

        inv.setItem(0, item(Material.CLOCK, "&6Undoable Rollback Jobs",
                "&7Completed/failed jobs with applied", "&7changes are listed newest first."));
        inv.setItem(8, item(Material.BARRIER, "&cClose"));
        for (int i = 0; i < visible.size(); i++) {
            inv.setItem(9 + i, undoJobItem(visible.get(i)));
        }
        inv.setItem(45, item(Material.ARROW, "&fBack"));
        if (page > 0) {
            inv.setItem(48, item(Material.SPECTRAL_ARROW, "&fPrevious Page"));
        }
        inv.setItem(49, item(Material.MAP, "&bPage " + (page + 1) + " / " + pages,
                "&f" + jobs.size() + " &7undoable job(s)"));
        if (page + 1 < pages) {
            inv.setItem(50, item(Material.SPECTRAL_ARROW, "&fNext Page"));
        }
        return new RenderedUndoJobs(inv, visible, page, pages);
    }

    static Inventory undoConfirmation(GuiRollbackJob job) {
        Inventory inv = Bukkit.createInventory(null, 27, color("&8FragGuard &7• &cConfirm Undo"));
        fill(inv, Material.GRAY_STAINED_GLASS_PANE);
        inv.setItem(10, undoJobItem(job));
        inv.setItem(14, item(Material.REDSTONE_BLOCK, "&cConfirm Undo #" + job.id(),
                "&7This reverses blocks actually changed", "&7by the saved rollback job.", "", "&cClick to continue."));
        inv.setItem(18, item(Material.ARROW, "&fBack to Jobs"));
        inv.setItem(26, item(Material.BARRIER, "&cClose"));
        return inv;
    }

    static Inventory activityDetail(LookupActivity activity) {
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
        List<LookupRow> visibleRows = rows.subList(start, end);
        for (int i = 0; i < visibleRows.size(); i++) {
            inv.setItem(i, rawEventItem(visibleRows.get(i)));
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
        return new RenderedRaw(inv, visibleRows, page, pages);
    }

    static Inventory exactEventDetail(
            LookupRow row,
            BlockEntitySnapshot.SnapshotDescription beforeEntity,
            BlockEntitySnapshot.SnapshotDescription afterEntity,
            boolean blockEntityChanged
    ) {
        Inventory inv = Bukkit.createInventory(null, 27, color("&8FragGuard &7• &fExact Event"));
        fill(inv, Material.GRAY_STAINED_GLASS_PANE);
        String material = LookupActivityGrouper.materialKey(row);

        inv.setItem(10, item(Material.PLAYER_HEAD, "&b" + actor(row.actorName()),
                "&7Recorded actor", "&7Action: &f" + row.action().displayPastTense()));
        inv.setItem(12, item(Material.CLOCK, "&eRecorded Time",
                "&f" + TIME.format(Instant.ofEpochMilli(row.happenedAt()))));
        inv.setItem(14, item(icon(material), "&f" + LookupActivityGrouper.displayMaterial(material),
                "&7Location: &f" + row.x() + " " + row.y() + " " + row.z(),
                "&7Before: &f" + state(row.beforeData()),
                "&7After: &f" + state(row.afterData())));

        List<String> blockEntityLore = new ArrayList<>();
        if (!row.blockEntityDataPresent()) {
            blockEntityLore.add("&7No stored block-entity payload for this event.");
        } else if (beforeEntity == null || afterEntity == null) {
            blockEntityLore.add("&cBlock-entity details are unavailable.");
        } else {
            blockEntityLore.add(blockEntityChanged
                    ? "&6Stored block-entity data changed"
                    : "&7Stored block-entity data was recorded");
            appendSnapshotDescription(blockEntityLore, "Before", beforeEntity);
            appendSnapshotDescription(blockEntityLore, "After", afterEntity);
        }
        inv.setItem(16, item(Material.WRITTEN_BOOK, "&eBlock Entity Details", blockEntityLore));
        inv.setItem(18, item(Material.ARROW, "&fBack"));
        inv.setItem(26, item(Material.BARRIER, "&cClose"));
        return inv;
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

    private static ItemStack undoJobItem(GuiRollbackJob job) {
        List<String> lore = new ArrayList<>(List.of(
                "&7Status: &f" + job.status(),
                "&7Actor: &f" + actor(job.actorName()),
                "&7World: &f" + actor(job.worldName()),
                "&7Radius: &f" + job.radius(),
                "&7Target: &f" + TIME.format(Instant.ofEpochMilli(job.targetTimestamp())),
                "&7Applied: &f" + job.appliedBlocks() + "&7 / " + job.totalBlocks(),
                "&7Conflicts: &f" + job.conflictBlocks()));
        if (job.lastError() != null && !job.lastError().isBlank()) {
            lore.add("&cLast error: " + truncate(job.lastError()));
        }
        lore.add("");
        lore.add("&eClick to review undo.");
        return item(Material.RECOVERY_COMPASS, "&6Rollback Job #" + job.id(), lore);
    }

    private static Material filterIcon(LookupFilters.Category category, String key) {
        if (key == null) {
            return Material.BARRIER;
        }
        return switch (category) {
            case PLAYER -> Material.PLAYER_HEAD;
            case ACTION -> Material.WRITABLE_BOOK;
            case MATERIAL -> icon(key);
        };
    }

    private static void appendSnapshotDescription(
            List<String> lore,
            String label,
            BlockEntitySnapshot.SnapshotDescription description
    ) {
        lore.add("&7" + label + ": &f" + description.type());
        int lines = Math.min(MAX_BLOCK_ENTITY_DETAIL_LINES, description.details().size());
        for (int index = 0; index < lines; index++) {
            lore.add("&8  " + truncate(description.details().get(index)));
        }
        if (description.details().size() > lines) {
            lore.add("&8  ... and " + (description.details().size() - lines) + " more detail line(s)");
        }
    }

    private static String truncate(String value) {
        if (value.length() <= MAX_LORE_DETAIL_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_LORE_DETAIL_LENGTH - 3) + "...";
    }

    private static ItemStack item(Material material, String name, String... lore) {
        return item(material, name, Arrays.asList(lore));
    }

    private static ItemStack item(Material material, String name, List<String> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.setDisplayName(color(name));
        if (!lore.isEmpty()) {
            meta.setLore(lore.stream().map(FragGuardGuiRenderer::color).toList());
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

    record RenderedResults(
            Inventory inventory,
            List<LookupActivity> visibleActivities,
            List<LookupRow> visibleRows,
            int page,
            int totalPages
    ) {
    }

    record RenderedRaw(Inventory inventory, List<LookupRow> visibleRows, int page, int totalPages) {
    }

    record RenderedFilterOptions(
            Inventory inventory,
            List<LookupFilters.Option> visibleOptions,
            int page,
            int totalPages
    ) {
    }

    record RenderedUndoJobs(
            Inventory inventory,
            List<GuiRollbackJob> visibleJobs,
            int page,
            int totalPages
    ) {
    }
}
