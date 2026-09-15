package org.pinnaclesmp.fragguard;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;

final class FragGuardGui implements Listener {
    private final FragGuardPlugin plugin;
    private final Database database;
    private final GuiLookupStore guiLookupStore;
    private final GuiRollbackStore guiRollbackStore;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    FragGuardGui(FragGuardPlugin plugin, Database database) {
        this.plugin = plugin;
        this.database = database;
        int queryTimeoutSeconds = plugin.getConfig().getInt("database-query-timeout-seconds", 15);
        this.guiLookupStore = new GuiLookupStore(plugin.getDataFolder(), queryTimeoutSeconds);
        this.guiRollbackStore = new GuiRollbackStore(plugin.getDataFolder(), queryTimeoutSeconds);
    }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String message = event.getMessage().trim().toLowerCase(Locale.ROOT);
        if (!message.equals("/fg") && !message.equals("/fragguard")) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (!player.isOp() || !player.hasPermission("fragguard.admin")) {
            player.sendMessage(color("&cOnly server operators can use FragGuard."));
            return;
        }
        openMain(player);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Session session = sessions.get(player.getUniqueId());
        if (session == null || session.inventory == null
                || !event.getView().getTopInventory().equals(session.inventory)) {
            return;
        }
        event.setCancelled(true);
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= session.inventory.getSize()) {
            return;
        }
        switch (session.screen) {
            case MAIN -> clickMain(player, session, slot);
            case SETUP -> clickSetup(player, session, slot, event.isRightClick());
            case RESULTS -> clickResults(player, session, slot);
            case FILTERS -> clickFilters(player, session, slot);
            case FILTER_OPTIONS -> clickFilterOptions(player, session, slot);
            case DETAIL -> clickDetail(player, session, slot);
            case ACTIVITY_RAW -> clickActivityRaw(player, session, slot);
            case EXACT_DETAIL -> clickExactDetail(player, session, slot);
            case ROLLBACK_SETUP -> clickRollbackSetup(player, session, slot, event.isRightClick());
            case UNDO_LIST -> clickUndoList(player, session, slot);
            case UNDO_CONFIRM -> clickUndoConfirm(player, session, slot);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Session session = sessions.get(player.getUniqueId());
        if (session == null || session.inventory == null
                || !event.getView().getTopInventory().equals(session.inventory)) {
            return;
        }
        if (event.getRawSlots().stream().anyMatch(slot -> slot < session.inventory.getSize())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Session session = sessions.remove(event.getPlayer().getUniqueId());
        if (session != null) {
            session.lookupRequests.invalidate();
            session.detailRequests.invalidate();
            session.undoRequests.invalidate();
        }
    }

    private void openMain(Player player) {
        Session session = sessions.computeIfAbsent(player.getUniqueId(), ignored -> new Session());
        session.reset(plugin.getRetentionDays(), maxRadius(), maxRollbackRadius());
        session.screen = Screen.MAIN;
        open(player, session, FragGuardGuiRenderer.mainMenu());
    }

    private void clickMain(Player player, Session session, int slot) {
        switch (slot) {
            case 10 -> openSetup(player, session);
            case 12 -> openRollbackSetup(player, session);
            case 14 -> loadUndoJobs(player, session);
            case 16 -> {
                player.closeInventory();
                player.performCommand("fg status");
            }
            case 20 -> {
                player.closeInventory();
                player.performCommand("fg help");
            }
            case 22 -> player.closeInventory();
            default -> { }
        }
    }

    private void openSetup(Player player, Session session) {
        session.screen = Screen.SETUP;
        open(player, session, FragGuardGuiRenderer.lookupSetup(session.radius, times().get(session.timeIndex).label()));
    }

    private void clickSetup(Player player, Session session, int slot, boolean rightClick) {
        switch (slot) {
            case 10 -> {
                cycleRadius(session, rightClick ? -1 : 1);
                openSetup(player, session);
            }
            case 12 -> {
                session.timeIndex = Math.floorMod(session.timeIndex + (rightClick ? -1 : 1), times().size());
                openSetup(player, session);
            }
            case 16 -> runLookup(player, session);
            case 18 -> openMain(player);
            case 26 -> player.closeInventory();
            default -> { }
        }
    }

    private void runLookup(Player player, Session session) {
        long requestGeneration = session.lookupRequests.begin();
        session.detailRequests.invalidate();
        UUID playerId = player.getUniqueId();
        TimePreset preset = times().get(session.timeIndex);
        long snapshotTimestamp = System.currentTimeMillis();
        long cutoff = snapshotTimestamp - preset.millis();
        int centerX = player.getLocation().getBlockX();
        int centerZ = player.getLocation().getBlockZ();
        int radius = session.radius;
        String world = player.getWorld().getName();
        String worldUuid = player.getWorld().getUID().toString();
        GuiLookupQuery query = new GuiLookupQuery(
                worldUuid, world, centerX, centerZ, radius,
                cutoff, snapshotTimestamp, LookupFilters.State.empty());

        long maxGapMillis = Math.max(0L,
                plugin.getConfig().getLong("gui-activity-max-gap-millis", 2500L));
        int maxDistance = Math.max(0,
                plugin.getConfig().getInt("gui-activity-max-distance", 6));
        long maxDurationMillis = Math.max(0L,
                plugin.getConfig().getLong("gui-activity-max-duration-millis",
                        LookupActivityGrouper.DEFAULT_MAX_DURATION_MILLIS));
        int maxSpan = Math.max(0,
                plugin.getConfig().getInt("gui-activity-max-span", LookupActivityGrouper.DEFAULT_MAX_SPAN));

        player.closeInventory();
        player.sendMessage(color("&7Loading FragGuard lookup..."));

        // Keep the main Database read barrier so every accepted gameplay write submitted before this
        // lookup is visible before the read-only GUI connection takes its fixed snapshot boundary.
        // LIMIT 0 prevents the legacy path from materializing history rows or block-entity payloads.
        database.lookupSinceAsync(world, centerX, centerZ, radius, 1, 0, cutoff)
                .thenCompose(ignored -> guiLookupStore.prepareLookupAsync(query))
                .whenComplete((overview, throwable) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline() || sessions.get(playerId) != session
                            || !session.lookupRequests.isCurrent(requestGeneration)) {
                        return;
                    }
                    if (throwable != null) {
                        reportLookupFailure(player, throwable);
                        openSetup(player, session);
                        return;
                    }
                    session.initializeLookup(
                            query, overview, maxGapMillis, maxDistance, maxDurationMillis, maxSpan);
                    loadResultsPage(player, session, 0, false);
                }));
    }

    private void loadResultsPage(Player player, Session session, int targetPage, boolean recount) {
        if (session.baseQuery == null || targetPage < 0 || targetPage >= session.resultPageStarts.size()) {
            return;
        }

        long requestGeneration = session.lookupRequests.begin();
        UUID playerId = player.getUniqueId();
        GuiLookupQuery query = session.baseQuery.withFilters(session.filters);
        GuiLookupCursor start = session.resultPageStarts.get(targetPage);
        boolean grouped = session.grouped;
        long existingCount = session.filteredTotalRows;

        player.closeInventory();
        player.sendMessage(color("&7Loading FragGuard history page..."));

        CompletableFuture<Long> countFuture = recount
                ? guiLookupStore.countRowsAsync(query)
                : CompletableFuture.completedFuture(existingCount);
        countFuture.thenCompose(count -> {
            if (grouped) {
                return guiLookupStore.selectActivityPageAsync(
                                query, start, FragGuardGuiRenderer.RESULTS_PER_PAGE,
                                plugin.getGuiLookupFetchSize(),
                                session.maxGapMillis, session.maxDistance,
                                session.maxDurationMillis, session.maxSpan)
                        .thenApply(page -> new PreparedResultsPage(count, null, page));
            }
            return guiLookupStore.selectRawPageAsync(
                            query, start, FragGuardGuiRenderer.RESULTS_PER_PAGE)
                    .thenApply(page -> new PreparedResultsPage(count, page, null));
        }).whenComplete((prepared, throwable) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline() || sessions.get(playerId) != session
                    || !session.lookupRequests.isCurrent(requestGeneration)) {
                return;
            }
            if (throwable != null) {
                reportLookupFailure(player, throwable);
                if (session.baseQuery == null) {
                    openSetup(player, session);
                } else {
                    renderResults(player, session);
                }
                return;
            }

            session.filteredTotalRows = prepared.totalRows();
            session.page = targetPage;
            session.visibleRows = prepared.rawPage() == null
                    ? List.of() : prepared.rawPage().rows();
            session.visibleActivities = prepared.activityPage() == null
                    ? List.of() : prepared.activityPage().activities();
            GuiLookupCursor next = prepared.rawPage() != null
                    ? prepared.rawPage().nextCursor()
                    : prepared.activityPage().nextCursor();
            session.rememberResultNext(targetPage, next);
            renderResults(player, session);
        }));
    }

    private void reportLookupFailure(Player player, Throwable throwable) {
        Throwable cause = root(throwable);
        if (cause instanceof TimeoutException) {
            player.sendMessage(color("&cLookup timed out. Reduce the radius or time window and try again."));
        } else {
            plugin.getLogger().log(Level.WARNING, "FragGuard GUI lookup failed", cause);
            player.sendMessage(color("&cFragGuard GUI lookup failed. Check console for details."));
        }
    }

    private void renderResults(Player player, Session session) {
        if (session.baseQuery == null) {
            openSetup(player, session);
            return;
        }
        session.screen = Screen.RESULTS;
        open(player, session, FragGuardPagedLookupRenderer.results(
                session.visibleRows,
                session.visibleActivities,
                session.grouped,
                session.page,
                session.filteredTotalRows,
                session.totalRows,
                LookupFilters.summary(session.filters, session.filterCatalog),
                session.filters.active(),
                session.page > 0,
                session.resultHasNext));
    }

    private void clickResults(Player player, Session session, int slot) {
        if (session.grouped && slot >= 9 && slot < 45) {
            int index = slot - 9;
            if (index < session.visibleActivities.size()) {
                session.detail = session.visibleActivities.get(index);
                session.resetActivityRawPaging();
                openDetail(player, session);
            }
            return;
        }
        if (!session.grouped && slot >= 9 && slot < 45) {
            int index = slot - 9;
            if (index < session.visibleRows.size()) {
                loadExactEvent(player, session, session.visibleRows.get(index), Screen.RESULTS);
            }
            return;
        }
        switch (slot) {
            case 2 -> openFilters(player, session);
            case 4 -> {
                session.grouped = !session.grouped;
                session.resetResultPaging();
                session.detail = null;
                loadResultsPage(player, session, 0, false);
            }
            case 6 -> {
                if (session.filters.active()) {
                    session.filters = LookupFilters.State.empty();
                    session.filteredTotalRows = session.totalRows;
                    session.resetResultPaging();
                    session.detail = null;
                    loadResultsPage(player, session, 0, false);
                }
            }
            case 8 -> player.closeInventory();
            case 45 -> openSetup(player, session);
            case 48 -> {
                if (session.page > 0) {
                    loadResultsPage(player, session, session.page - 1, false);
                }
            }
            case 50 -> {
                if (session.resultHasNext && session.page + 1 < session.resultPageStarts.size()) {
                    loadResultsPage(player, session, session.page + 1, false);
                }
            }
            default -> { }
        }
    }

    private void openFilters(Player player, Session session) {
        session.screen = Screen.FILTERS;
        open(player, session, FragGuardGuiRenderer.lookupFilters(
                session.filterCatalog.selectedLabel(session.filters, LookupFilters.Category.PLAYER),
                session.filterCatalog.selectedLabel(session.filters, LookupFilters.Category.ACTION),
                session.filterCatalog.selectedLabel(session.filters, LookupFilters.Category.MATERIAL),
                session.filters.active()));
    }

    private void clickFilters(Player player, Session session, int slot) {
        switch (slot) {
            case 10 -> openFilterOptions(player, session, LookupFilters.Category.PLAYER, 0);
            case 12 -> openFilterOptions(player, session, LookupFilters.Category.ACTION, 0);
            case 14 -> openFilterOptions(player, session, LookupFilters.Category.MATERIAL, 0);
            case 16 -> {
                if (session.filters.active()) {
                    session.filters = LookupFilters.State.empty();
                    session.filteredTotalRows = session.totalRows;
                    session.resetResultPaging();
                    session.detail = null;
                    session.exactEvent = null;
                }
                openFilters(player, session);
            }
            case 18 -> loadResultsPage(player, session, 0, false);
            case 26 -> player.closeInventory();
            default -> { }
        }
    }

    private void openFilterOptions(Player player, Session session, LookupFilters.Category category, int page) {
        session.filterCategory = category;
        session.screen = Screen.FILTER_OPTIONS;
        FragGuardGuiRenderer.RenderedFilterOptions rendered = FragGuardGuiRenderer.filterOptions(
                category,
                session.filterCatalog.options(category),
                session.filters.selectedKey(category),
                safeUiCount(session.totalRows),
                page);
        session.filterPage = rendered.page();
        session.visibleFilterOptions = rendered.visibleOptions();
        open(player, session, rendered.inventory());
    }

    private void clickFilterOptions(Player player, Session session, int slot) {
        if (slot >= 0 && slot < FragGuardGuiRenderer.FILTER_OPTIONS_PER_PAGE) {
            if (slot < session.visibleFilterOptions.size() && session.filterCategory != null) {
                LookupFilters.Option option = session.visibleFilterOptions.get(slot);
                session.filters = session.filters.with(session.filterCategory, option.key());
                session.resetResultPaging();
                session.detail = null;
                session.exactEvent = null;
                refreshFilterCount(player, session);
            }
            return;
        }
        if (slot == 45) {
            openFilters(player, session);
        } else if (slot == 48 && session.filterPage > 0) {
            openFilterOptions(player, session, session.filterCategory, session.filterPage - 1);
        } else if (slot == 50) {
            openFilterOptions(player, session, session.filterCategory, session.filterPage + 1);
        }
    }

    private void refreshFilterCount(Player player, Session session) {
        if (session.baseQuery == null) {
            openSetup(player, session);
            return;
        }
        if (!session.filters.active()) {
            session.filteredTotalRows = session.totalRows;
            openFilters(player, session);
            return;
        }

        long requestGeneration = session.lookupRequests.begin();
        UUID playerId = player.getUniqueId();
        GuiLookupQuery query = session.baseQuery.withFilters(session.filters);
        player.closeInventory();
        player.sendMessage(color("&7Applying FragGuard lookup filters..."));
        guiLookupStore.countRowsAsync(query)
                .whenComplete((count, throwable) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline() || sessions.get(playerId) != session
                            || !session.lookupRequests.isCurrent(requestGeneration)) {
                        return;
                    }
                    if (throwable != null) {
                        reportLookupFailure(player, throwable);
                        openFilters(player, session);
                        return;
                    }
                    session.filteredTotalRows = count;
                    openFilters(player, session);
                }));
    }

    private void openDetail(Player player, Session session) {
        if (session.detail == null) {
            renderResults(player, session);
            return;
        }
        session.screen = Screen.DETAIL;
        open(player, session, FragGuardPagedLookupRenderer.activityDetail(session.detail));
    }

    private void clickDetail(Player player, Session session, int slot) {
        if (slot == 18) {
            renderResults(player, session);
        } else if (slot == 22) {
            session.resetActivityRawPaging();
            loadActivityRawPage(player, session, 0);
        }
    }

    private void loadActivityRawPage(Player player, Session session, int targetPage) {
        if (session.detail == null || session.baseQuery == null
                || targetPage < 0 || targetPage >= session.activityRawPageStarts.size()) {
            renderResults(player, session);
            return;
        }

        long requestGeneration = session.lookupRequests.begin();
        UUID playerId = player.getUniqueId();
        GuiLookupCursor start = session.activityRawPageStarts.get(targetPage);
        GuiLookupQuery query = session.baseQuery.withFilters(session.filters);
        GuiActivitySummary detail = session.detail;
        player.closeInventory();
        player.sendMessage(color("&7Loading exact activity events..."));

        guiLookupStore.selectActivityRowsPageAsync(query, detail, start, 45)
                .whenComplete((page, throwable) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline() || sessions.get(playerId) != session
                            || !session.lookupRequests.isCurrent(requestGeneration)
                            || session.detail != detail) {
                        return;
                    }
                    if (throwable != null) {
                        reportLookupFailure(player, throwable);
                        openDetail(player, session);
                        return;
                    }
                    session.rawPage = targetPage;
                    session.visibleRows = page.rows();
                    session.rememberActivityRawNext(targetPage, page.nextCursor());
                    renderActivityRaw(player, session);
                }));
    }

    private void renderActivityRaw(Player player, Session session) {
        if (session.detail == null) {
            renderResults(player, session);
            return;
        }
        session.screen = Screen.ACTIVITY_RAW;
        open(player, session, FragGuardPagedLookupRenderer.activityRaw(
                session.visibleRows,
                session.rawPage,
                session.detail.eventCount(),
                session.rawPage > 0,
                session.activityRawHasNext));
    }

    private void clickActivityRaw(Player player, Session session, int slot) {
        if (slot >= 0 && slot < 45) {
            if (slot < session.visibleRows.size()) {
                loadExactEvent(player, session, session.visibleRows.get(slot), Screen.ACTIVITY_RAW);
            }
            return;
        }
        if (slot == 45) {
            openDetail(player, session);
        } else if (slot == 48 && session.rawPage > 0) {
            loadActivityRawPage(player, session, session.rawPage - 1);
        } else if (slot == 50 && session.activityRawHasNext
                && session.rawPage + 1 < session.activityRawPageStarts.size()) {
            loadActivityRawPage(player, session, session.rawPage + 1);
        }
    }

    private void loadExactEvent(Player player, Session session, LookupRow row, Screen returnScreen) {
        session.exactReturnScreen = returnScreen;
        session.exactEvent = null;
        if (!row.blockEntityDataPresent()) {
            session.detailRequests.invalidate();
            session.exactEvent = new PreparedExactEvent(row, null, null, false);
            renderExactEvent(player, session);
            return;
        }
        if (row.id() < 0L) {
            player.sendMessage(color("&cThat history event does not have a stable row ID for detail loading."));
            returnToExactSource(player, session);
            return;
        }

        long requestGeneration = session.detailRequests.begin();
        UUID playerId = player.getUniqueId();
        player.closeInventory();
        player.sendMessage(color("&7Loading exact block-entity details..."));
        guiLookupStore.loadEventPayloadAsync(row.id())
                .thenApplyAsync(payload -> new PreparedExactEvent(
                        row,
                        BlockEntitySnapshot.describe(payload.beforeEntityData()),
                        BlockEntitySnapshot.describe(payload.afterEntityData()),
                        payload.changed()
                ))
                .whenComplete((prepared, throwable) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline() || sessions.get(playerId) != session
                            || !session.detailRequests.isCurrent(requestGeneration)) {
                        return;
                    }
                    if (throwable != null) {
                        reportExactDetailFailure(player, throwable);
                        returnToExactSource(player, session);
                        return;
                    }
                    session.exactEvent = prepared;
                    renderExactEvent(player, session);
                }));
    }

    private void renderExactEvent(Player player, Session session) {
        if (session.exactEvent == null) {
            returnToExactSource(player, session);
            return;
        }
        session.screen = Screen.EXACT_DETAIL;
        PreparedExactEvent event = session.exactEvent;
        open(player, session, FragGuardGuiRenderer.exactEventDetail(
                event.row(), event.beforeEntity(), event.afterEntity(), event.blockEntityChanged()));
    }

    private void clickExactDetail(Player player, Session session, int slot) {
        if (slot == 18) {
            returnToExactSource(player, session);
        } else if (slot == 26) {
            player.closeInventory();
        }
    }

    private void returnToExactSource(Player player, Session session) {
        session.detailRequests.invalidate();
        session.exactEvent = null;
        if (session.exactReturnScreen == Screen.ACTIVITY_RAW && session.detail != null) {
            renderActivityRaw(player, session);
        } else {
            renderResults(player, session);
        }
    }

    private void reportExactDetailFailure(Player player, Throwable throwable) {
        Throwable cause = root(throwable);
        if (cause instanceof IllegalStateException && cause.getMessage() != null) {
            player.sendMessage(color("&c" + cause.getMessage()));
            return;
        }
        if (cause instanceof TimeoutException) {
            player.sendMessage(color("&cExact event details timed out. Try again."));
            return;
        }
        plugin.getLogger().log(Level.WARNING, "FragGuard exact GUI event detail lookup failed", cause);
        player.sendMessage(color("&cCould not load exact event details. Check console for details."));
    }

    private void openRollbackSetup(Player player, Session session) {
        session.screen = Screen.ROLLBACK_SETUP;
        open(player, session, FragGuardGuiRenderer.rollbackSetup(
                session.rollbackRadius,
                times().get(session.rollbackTimeIndex).label(),
                session.rollbackForce,
                activeRollbackToken(player) != null));
    }

    private void clickRollbackSetup(Player player, Session session, int slot, boolean rightClick) {
        switch (slot) {
            case 10 -> {
                cycleRollbackRadius(session, rightClick ? -1 : 1);
                openRollbackSetup(player, session);
            }
            case 12 -> {
                session.rollbackTimeIndex = Math.floorMod(
                        session.rollbackTimeIndex + (rightClick ? -1 : 1), times().size());
                openRollbackSetup(player, session);
            }
            case 14 -> {
                session.rollbackForce = !session.rollbackForce;
                openRollbackSetup(player, session);
            }
            case 16 -> {
                TimePreset preset = times().get(session.rollbackTimeIndex);
                String command = "fg rollback r:" + session.rollbackRadius + " t:" + preset.commandToken()
                        + (session.rollbackForce ? " force" : "");
                player.closeInventory();
                player.performCommand(command);
            }
            case 18 -> openMain(player);
            case 20 -> {
                String token = activeRollbackToken(player);
                if (token == null) {
                    player.sendMessage(color("&cYou do not have an active rollback preview to confirm."));
                    openRollbackSetup(player, session);
                    return;
                }
                player.closeInventory();
                player.performCommand("fg rollback confirm " + token);
            }
            case 26 -> player.closeInventory();
            default -> { }
        }
    }

    private String activeRollbackToken(Player player) {
        var command = plugin.getCommand("fg");
        if (command == null) {
            return null;
        }
        try {
            List<String> completions = command.tabComplete(
                    player, "fg", new String[]{"rollback", "confirm", ""});
            return completions == null || completions.isEmpty() ? null : completions.getFirst();
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.FINE, "Could not resolve active rollback preview for GUI", exception);
            return null;
        }
    }

    private void loadUndoJobs(Player player, Session session) {
        long requestGeneration = session.undoRequests.begin();
        UUID playerId = player.getUniqueId();
        player.closeInventory();
        player.sendMessage(color("&7Loading undoable FragGuard rollback jobs..."));
        guiRollbackStore.loadUndoableJobsAsync()
                .whenComplete((jobs, throwable) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline() || sessions.get(playerId) != session
                            || !session.undoRequests.isCurrent(requestGeneration)) {
                        return;
                    }
                    if (throwable != null) {
                        plugin.getLogger().log(Level.WARNING, "FragGuard GUI undo job lookup failed", root(throwable));
                        player.sendMessage(color("&cCould not load rollback jobs for undo. Check console for details."));
                        openMain(player);
                        return;
                    }
                    session.undoJobs = List.copyOf(jobs);
                    session.undoPage = 0;
                    session.selectedUndoJob = null;
                    renderUndoJobs(player, session);
                }));
    }

    private void renderUndoJobs(Player player, Session session) {
        session.screen = Screen.UNDO_LIST;
        FragGuardGuiRenderer.RenderedUndoJobs rendered =
                FragGuardGuiRenderer.undoJobs(session.undoJobs, session.undoPage);
        session.undoPage = rendered.page();
        session.visibleUndoJobs = rendered.visibleJobs();
        open(player, session, rendered.inventory());
    }

    private void clickUndoList(Player player, Session session, int slot) {
        if (slot >= 9 && slot < 45) {
            int index = slot - 9;
            if (index < session.visibleUndoJobs.size()) {
                session.selectedUndoJob = session.visibleUndoJobs.get(index);
                openUndoConfirmation(player, session);
            }
            return;
        }
        if (slot == 8) {
            player.closeInventory();
        } else if (slot == 45) {
            openMain(player);
        } else if (slot == 48 && session.undoPage > 0) {
            session.undoPage--;
            renderUndoJobs(player, session);
        } else if (slot == 50) {
            session.undoPage++;
            renderUndoJobs(player, session);
        }
    }

    private void openUndoConfirmation(Player player, Session session) {
        if (session.selectedUndoJob == null) {
            renderUndoJobs(player, session);
            return;
        }
        session.screen = Screen.UNDO_CONFIRM;
        open(player, session, FragGuardGuiRenderer.undoConfirmation(session.selectedUndoJob));
    }

    private void clickUndoConfirm(Player player, Session session, int slot) {
        if (slot == 14 && session.selectedUndoJob != null) {
            long jobId = session.selectedUndoJob.id();
            player.closeInventory();
            player.performCommand("fg undo " + jobId);
        } else if (slot == 18) {
            renderUndoJobs(player, session);
        } else if (slot == 26) {
            player.closeInventory();
        }
    }

    private void open(Player player, Session session, Inventory inventory) {
        session.inventory = inventory;
        player.openInventory(inventory);
    }

    private void cycleRadius(Session session, int delta) {
        List<Integer> radii = radii(maxRadius());
        int index = Math.max(0, radii.indexOf(session.radius));
        session.radius = radii.get(Math.floorMod(index + delta, radii.size()));
    }

    private void cycleRollbackRadius(Session session, int delta) {
        List<Integer> radii = radii(maxRollbackRadius());
        int index = Math.max(0, radii.indexOf(session.rollbackRadius));
        session.rollbackRadius = radii.get(Math.floorMod(index + delta, radii.size()));
    }

    private List<Integer> radii(int maximum) {
        List<Integer> values = new ArrayList<>();
        for (int value : new int[]{5, 10, 15, 30, 50, 75, 100, 150}) {
            if (value <= maximum) {
                values.add(value);
            }
        }
        if (values.isEmpty() || values.get(values.size() - 1) != maximum) {
            values.add(maximum);
        }
        return values.stream().distinct().toList();
    }

    private List<TimePreset> times() {
        long maximum = plugin.getRetentionDays() * 86_400_000L;
        List<TimePreset> values = new ArrayList<>(List.of(
                new TimePreset("15 minutes", 900_000L, "15m"),
                new TimePreset("1 hour", 3_600_000L, "1h"),
                new TimePreset("6 hours", 21_600_000L, "6h"),
                new TimePreset("12 hours", 43_200_000L, "12h"),
                new TimePreset("1 day", 86_400_000L, "1d"),
                new TimePreset("2 days", 172_800_000L, "2d"),
                new TimePreset("7 days", 604_800_000L, "7d"),
                new TimePreset("30 days", 2_592_000_000L, "30d")));
        values.removeIf(value -> value.millis() > maximum);
        if (values.isEmpty() || values.get(values.size() - 1).millis() < maximum) {
            values.add(new TimePreset(plugin.getRetentionDays() + " days", maximum,
                    plugin.getRetentionDays() + "d"));
        }
        return values;
    }

    private int maxRadius() {
        return Math.max(1, plugin.getConfig().getInt("max-lookup-radius", 150));
    }

    private int maxRollbackRadius() {
        return Math.max(1, plugin.getConfig().getInt("max-rollback-radius", 100));
    }

    private int safeUiCount(long count) {
        return count >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.max(0L, count);
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    private Throwable root(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private enum Screen {
        MAIN,
        SETUP,
        RESULTS,
        FILTERS,
        FILTER_OPTIONS,
        DETAIL,
        ACTIVITY_RAW,
        EXACT_DETAIL,
        ROLLBACK_SETUP,
        UNDO_LIST,
        UNDO_CONFIRM
    }

    private record TimePreset(String label, long millis, String commandToken) {
    }

    private record PreparedResultsPage(
            long totalRows,
            GuiRawPage rawPage,
            GuiActivityPage activityPage
    ) {
    }

    private record PreparedExactEvent(
            LookupRow row,
            BlockEntitySnapshot.SnapshotDescription beforeEntity,
            BlockEntitySnapshot.SnapshotDescription afterEntity,
            boolean blockEntityChanged
    ) {
    }

    private static final class Session {
        private final LookupRequestGeneration lookupRequests = new LookupRequestGeneration();
        private final LookupRequestGeneration detailRequests = new LookupRequestGeneration();
        private final LookupRequestGeneration undoRequests = new LookupRequestGeneration();
        private Screen screen;
        private Screen exactReturnScreen = Screen.RESULTS;
        private Inventory inventory;
        private int radius;
        private int timeIndex;
        private boolean grouped;
        private int page;
        private int rawPage;
        private GuiLookupQuery baseQuery;
        private long totalRows;
        private long filteredTotalRows;
        private long maxGapMillis;
        private int maxDistance;
        private long maxDurationMillis;
        private int maxSpan;
        private LookupFilters.Catalog filterCatalog = LookupFilters.Catalog.empty();
        private LookupFilters.State filters = LookupFilters.State.empty();
        private LookupFilters.Category filterCategory;
        private int filterPage;
        private List<LookupFilters.Option> visibleFilterOptions = List.of();
        private final List<GuiLookupCursor> resultPageStarts = new ArrayList<>();
        private boolean resultHasNext;
        private List<GuiActivitySummary> visibleActivities = List.of();
        private List<LookupRow> visibleRows = List.of();
        private GuiActivitySummary detail;
        private final List<GuiLookupCursor> activityRawPageStarts = new ArrayList<>();
        private boolean activityRawHasNext;
        private PreparedExactEvent exactEvent;
        private int rollbackRadius;
        private int rollbackTimeIndex;
        private boolean rollbackForce;
        private List<GuiRollbackJob> undoJobs = List.of();
        private List<GuiRollbackJob> visibleUndoJobs = List.of();
        private int undoPage;
        private GuiRollbackJob selectedUndoJob;

        private void initializeLookup(
                GuiLookupQuery query,
                GuiLookupOverview overview,
                long activityMaxGapMillis,
                int activityMaxDistance,
                long activityMaxDurationMillis,
                int activityMaxSpan
        ) {
            baseQuery = query.withFilters(LookupFilters.State.empty()).withMaxRowId(overview.maxRowId());
            totalRows = overview.totalRows();
            filteredTotalRows = totalRows;
            maxGapMillis = activityMaxGapMillis;
            maxDistance = activityMaxDistance;
            maxDurationMillis = activityMaxDurationMillis;
            maxSpan = activityMaxSpan;
            filterCatalog = overview.catalog();
            filters = LookupFilters.State.empty();
            filterCategory = null;
            filterPage = 0;
            visibleFilterOptions = List.of();
            grouped = true;
            detail = null;
            exactEvent = null;
            resetResultPaging();
            resetActivityRawPaging();
        }

        private void resetResultPaging() {
            page = 0;
            resultPageStarts.clear();
            resultPageStarts.add(null);
            resultHasNext = false;
            visibleActivities = List.of();
            visibleRows = List.of();
        }

        private void rememberResultNext(int currentPage, GuiLookupCursor next) {
            while (resultPageStarts.size() > currentPage + 1) {
                resultPageStarts.remove(resultPageStarts.size() - 1);
            }
            if (next != null) {
                resultPageStarts.add(next);
                resultHasNext = true;
            } else {
                resultHasNext = false;
            }
        }

        private void resetActivityRawPaging() {
            rawPage = 0;
            activityRawPageStarts.clear();
            activityRawPageStarts.add(null);
            activityRawHasNext = false;
        }

        private void rememberActivityRawNext(int currentPage, GuiLookupCursor next) {
            while (activityRawPageStarts.size() > currentPage + 1) {
                activityRawPageStarts.remove(activityRawPageStarts.size() - 1);
            }
            if (next != null) {
                activityRawPageStarts.add(next);
                activityRawHasNext = true;
            } else {
                activityRawHasNext = false;
            }
        }

        private void reset(int retentionDays, int maxRadius, int maxRollbackRadius) {
            lookupRequests.invalidate();
            detailRequests.invalidate();
            undoRequests.invalidate();
            radius = Math.min(15, maxRadius);
            timeIndex = retentionDays >= 1 ? 4 : 0;
            grouped = true;
            page = 0;
            rawPage = 0;
            baseQuery = null;
            totalRows = 0L;
            filteredTotalRows = 0L;
            maxGapMillis = 0L;
            maxDistance = 0;
            maxDurationMillis = 0L;
            maxSpan = 0;
            filterCatalog = LookupFilters.Catalog.empty();
            filters = LookupFilters.State.empty();
            filterCategory = null;
            filterPage = 0;
            visibleFilterOptions = List.of();
            resultPageStarts.clear();
            resultHasNext = false;
            visibleActivities = List.of();
            visibleRows = List.of();
            detail = null;
            activityRawPageStarts.clear();
            activityRawHasNext = false;
            exactEvent = null;
            exactReturnScreen = Screen.RESULTS;
            rollbackRadius = Math.min(15, maxRollbackRadius);
            rollbackTimeIndex = retentionDays >= 1 ? 4 : 0;
            rollbackForce = false;
            undoJobs = List.of();
            visibleUndoJobs = List.of();
            undoPage = 0;
            selectedUndoJob = null;
        }
    }
}
