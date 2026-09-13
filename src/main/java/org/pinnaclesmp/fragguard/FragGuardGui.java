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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;

final class FragGuardGui implements Listener {
    private final FragGuardPlugin plugin;
    private final Database database;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    FragGuardGui(FragGuardPlugin plugin, Database database) {
        this.plugin = plugin;
        this.database = database;
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
            case DETAIL -> clickDetail(player, session, slot);
            case ACTIVITY_RAW -> clickActivityRaw(player, session, slot);
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
        sessions.remove(event.getPlayer().getUniqueId());
    }

    private void openMain(Player player) {
        Session session = sessions.computeIfAbsent(player.getUniqueId(), ignored -> new Session());
        session.reset(plugin.getRetentionDays(), maxRadius());
        session.screen = Screen.MAIN;
        open(player, session, FragGuardGuiRenderer.mainMenu());
    }

    private void clickMain(Player player, Session session, int slot) {
        switch (slot) {
            case 10 -> openSetup(player, session);
            case 12 -> {
                player.closeInventory();
                player.sendMessage(color("&eUse &f/fg rollback r:<radius> t:<time>&e while rollback GUI controls are being built."));
            }
            case 14 -> {
                player.closeInventory();
                player.performCommand("fg status");
            }
            case 16 -> {
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
        int rowLimit = Math.max(250, plugin.getConfig().getInt("gui-lookup-max-rows", 5000));
        TimePreset preset = times().get(session.timeIndex);
        long cutoff = System.currentTimeMillis() - preset.millis();
        int centerX = player.getLocation().getBlockX();
        int centerZ = player.getLocation().getBlockZ();
        String world = player.getWorld().getName();

        player.closeInventory();
        player.sendMessage(color("&7Loading FragGuard lookup..."));
        database.lookupAsync(world, centerX, centerZ, session.radius, 1, rowLimit, plugin.getRetentionDays())
                .whenComplete((page, throwable) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    if (throwable != null) {
                        reportLookupFailure(player, throwable);
                        openSetup(player, session);
                        return;
                    }
                    boolean complete = page.totalRows() <= rowLimit;
                    if (!complete && !page.rows().isEmpty()) {
                        long oldestLoaded = page.rows().get(page.rows().size() - 1).happenedAt();
                        complete = oldestLoaded < cutoff;
                    }
                    if (!complete) {
                        player.sendMessage(color("&cThat window contains more than " + rowLimit + " relevant records."));
                        player.sendMessage(color("&7FragGuard will not show a partial GUI result as complete. Narrow the radius or time."));
                        openSetup(player, session);
                        return;
                    }
                    session.rows = page.rows().stream().filter(row -> row.happenedAt() >= cutoff).toList();
                    session.grouped = true;
                    session.page = 0;
                    session.detail = null;
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
        session.screen = Screen.RESULTS;
        List<LookupActivity> activities = LookupActivityGrouper.group(session.rows,
                Math.max(0L, plugin.getConfig().getLong("gui-activity-max-gap-millis", 2500L)),
                Math.max(0, plugin.getConfig().getInt("gui-activity-max-distance", 6)));
        FragGuardGuiRenderer.RenderedResults rendered = FragGuardGuiRenderer.results(
                session.rows, activities, session.grouped, session.page);
        session.page = rendered.page();
        session.visibleActivities = rendered.visibleActivities();
        open(player, session, rendered.inventory());
    }

    private void clickResults(Player player, Session session, int slot) {
        if (session.grouped && slot >= 9 && slot < 45) {
            int index = slot - 9;
            if (index < session.visibleActivities.size()) {
                session.detail = session.visibleActivities.get(index);
                openDetail(player, session);
            }
            return;
        }
        switch (slot) {
            case 4 -> {
                session.grouped = !session.grouped;
                session.page = 0;
                renderResults(player, session);
            }
            case 8 -> player.closeInventory();
            case 45 -> openSetup(player, session);
            case 48 -> {
                if (session.page > 0) {
                    session.page--;
                }
                renderResults(player, session);
            }
            case 50 -> {
                session.page++;
                renderResults(player, session);
            }
            default -> { }
        }
    }

    private void openDetail(Player player, Session session) {
        if (session.detail == null) {
            renderResults(player, session);
            return;
        }
        session.screen = Screen.DETAIL;
        open(player, session, FragGuardGuiRenderer.activityDetail(session.detail));
    }

    private void clickDetail(Player player, Session session, int slot) {
        if (slot == 18) {
            renderResults(player, session);
        } else if (slot == 22) {
            session.rawPage = 0;
            renderActivityRaw(player, session);
        }
    }

    private void renderActivityRaw(Player player, Session session) {
        if (session.detail == null) {
            renderResults(player, session);
            return;
        }
        session.screen = Screen.ACTIVITY_RAW;
        FragGuardGuiRenderer.RenderedRaw rendered = FragGuardGuiRenderer.activityRaw(session.detail, session.rawPage);
        session.rawPage = rendered.page();
        open(player, session, rendered.inventory());
    }

    private void clickActivityRaw(Player player, Session session, int slot) {
        if (slot == 45) {
            openDetail(player, session);
        } else if (slot == 48 && session.rawPage > 0) {
            session.rawPage--;
            renderActivityRaw(player, session);
        } else if (slot == 50) {
            session.rawPage++;
            renderActivityRaw(player, session);
        }
    }

    private void open(Player player, Session session, Inventory inventory) {
        session.inventory = inventory;
        player.openInventory(inventory);
    }

    private void cycleRadius(Session session, int delta) {
        List<Integer> radii = radii();
        int index = Math.max(0, radii.indexOf(session.radius));
        session.radius = radii.get(Math.floorMod(index + delta, radii.size()));
    }

    private List<Integer> radii() {
        int maximum = maxRadius();
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
                new TimePreset("15 minutes", 900_000L),
                new TimePreset("1 hour", 3_600_000L),
                new TimePreset("6 hours", 21_600_000L),
                new TimePreset("12 hours", 43_200_000L),
                new TimePreset("1 day", 86_400_000L),
                new TimePreset("2 days", 172_800_000L),
                new TimePreset("7 days", 604_800_000L),
                new TimePreset("30 days", 2_592_000_000L)));
        values.removeIf(value -> value.millis() > maximum);
        if (values.isEmpty() || values.get(values.size() - 1).millis() < maximum) {
            values.add(new TimePreset(plugin.getRetentionDays() + " days", maximum));
        }
        return values;
    }

    private int maxRadius() {
        return Math.max(1, plugin.getConfig().getInt("max-lookup-radius", 150));
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
        DETAIL,
        ACTIVITY_RAW
    }

    private record TimePreset(String label, long millis) {
    }

    private static final class Session {
        private Screen screen;
        private Inventory inventory;
        private int radius;
        private int timeIndex;
        private boolean grouped;
        private int page;
        private int rawPage;
        private List<LookupRow> rows = List.of();
        private List<LookupActivity> visibleActivities = List.of();
        private LookupActivity detail;

        private void reset(int retentionDays, int maxRadius) {
            radius = Math.min(15, maxRadius);
            timeIndex = retentionDays >= 1 ? 4 : 0;
            grouped = true;
            page = 0;
            rawPage = 0;
            rows = List.of();
            visibleActivities = List.of();
            detail = null;
        }
    }
}
