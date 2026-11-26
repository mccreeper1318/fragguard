package org.pinnaclesmp.fragguard;

import org.bukkit.Bukkit;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitRunnable;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.logging.Level;

final class FragGuardCommand implements CommandExecutor, TabCompleter, Listener {
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")
            .withZone(ZoneId.systemDefault());
    private static final LegacyComponentSerializer LEGACY_INPUT = LegacyComponentSerializer.legacyAmpersand();
    private static final LegacyComponentSerializer LEGACY_OUTPUT = LegacyComponentSerializer.legacySection();
    private static final PlainTextComponentSerializer PLAIN_TEXT = PlainTextComponentSerializer.plainText();
    private static final String OPERATION_QUEUE_FULL = "FragGuard's database operation queue is full.";
    private static final byte[] UNKNOWN_ENTITY_STATE = new byte[]{0};
    private static final int MAX_FORCE_REVALIDATION_RETRIES = 8;
    private static final int MAX_AUDITED_CHANGES_PER_SLICE = 16;

    private final FragGuardPlugin plugin;
    private final Database database;
    private final Map<String, RollbackPreview> previews = new HashMap<>();
    // Tracks queries until their main-thread completion, so cancellation/quit/reload
    // cannot resurrect a preview from an older asynchronous search.
    private final Map<UUID, UUID> pendingRollbackQueries = new HashMap<>();
    private final Map<UUID, UUID> lookupRequests = new HashMap<>();
    private final Map<UUID, CompletableFuture<DatabaseQuickDiagnostics.Report>>
            diagnosticRequests = new HashMap<>();
    private final Map<UUID, CompletableFuture<DatabaseDeepDiagnostics.Report>>
            deepDiagnosticRequests = new HashMap<>();
    private final Map<UUID, CompletableFuture<CompactionStoragePreflight.Report>>
            capacityRequests = new HashMap<>();
    private final Map<UUID, CompactionStoragePreflight.Report> capacityLastReports = new HashMap<>();
    // Display cache only. Never used for acceptance or reservation; full details
    // must remain available even when preflight was refused.
    private final Map<UUID, CompactionStoragePreflight.Report> capacityDisplayReports = new HashMap<>();
    private final RollbackAreaPreview areaPreview;
    private final Set<Long> executingJobs = new HashSet<>();
    private final Set<Long> pausedJobs = new HashSet<>();
    private final Map<Long, RollbackChunkPlan.ChunkKey> loadedJobChunks = new HashMap<>();
    private final Map<RollbackChunkPlan.ChunkKey, Integer> chunkTicketReferences = new HashMap<>();
    private final RollbackTickBudget rollbackTickBudget = new RollbackTickBudget();

    FragGuardCommand(FragGuardPlugin plugin, Database database) {
        this.plugin = plugin;
        this.database = database;
        this.areaPreview = new RollbackAreaPreview(plugin);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID actor = event.getPlayer().getUniqueId();
        pendingRollbackQueries.remove(actor);
        lookupRequests.remove(actor);
        CompletableFuture<DatabaseQuickDiagnostics.Report> diagnostics = diagnosticRequests.remove(actor);
        if (diagnostics != null) diagnostics.cancel(true);
        CompletableFuture<DatabaseDeepDiagnostics.Report> deep = deepDiagnosticRequests.remove(actor);
        // A finished request can still be awaiting its main-thread callback.
        // Do not cancel another operator's later scan on behalf of a finished one.
        if (deep != null && !deep.isDone() && plugin.getDeepDiagnostics() != null) {
            plugin.getDeepDiagnostics().cancel();
        }
        CompletableFuture<CompactionStoragePreflight.Report> capacity = capacityRequests.remove(actor);
        if (capacity != null) capacity.cancel(true);
        capacityLastReports.remove(actor);
        capacityDisplayReports.remove(actor);
        removePreviewsFor(actor);
    }

    void clearRollbackPreviews() {
        lookupRequests.clear();
        diagnosticRequests.values().forEach(pending -> pending.cancel(true));
        diagnosticRequests.clear();
        if (plugin.getDeepDiagnostics() != null) plugin.getDeepDiagnostics().cancel();
        deepDiagnosticRequests.clear();
        capacityRequests.values().forEach(pending -> pending.cancel(true));
        capacityRequests.clear();
        capacityLastReports.clear();
        capacityDisplayReports.clear();
        pendingRollbackQueries.clear();
        areaPreview.clear();
        previews.clear();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            // The console is privileged but has no location: only the safe,
            // location-independent STRICT automation/status commands work.
            if (sender instanceof org.bukkit.command.ConsoleCommandSender
                    && args.length >= 2 && args[0].equalsIgnoreCase("maintenance")) {
                String action = args[1].toLowerCase(Locale.ROOT);
                if ("status".equals(action)) {
                    MaintenanceCoordinator maintenance = plugin.getMaintenanceCoordinator();
                    sender.sendMessage(maintenance == null ? "FragGuard unavailable"
                            : "Maintenance: " + maintenance.snapshot().phase()
                                    + " — " + maintenance.snapshot().detail());
                    AutomaticMaintenanceWorkflow automatic = plugin.getAutomaticMaintenance();
                    if (automatic != null) sender.sendMessage(automatic.status());
                    return true;
                }
                if ("cancel".equals(action) && args.length == 2) {
                    AutomaticMaintenanceWorkflow automatic = plugin.getAutomaticMaintenance();
                    sender.sendMessage(automatic != null
                            && automatic.cancelPreparation(CONSOLE_MAINTENANCE_OPERATOR)
                            ? "Pre-countdown automatic maintenance cancelled safely."
                            : "No console pre-countdown maintenance to cancel. "
                                    + "In-progress stages require an in-game administrator.");
                    return true;
                }
                if ("start".equals(action)
                        && (args.length == 2
                            || args.length == 3 && (args[2].equalsIgnoreCase("strict")
                                || args[2].equalsIgnoreCase("acknowledge")))) {
                    requestAutomaticMaintenance(CONSOLE_MAINTENANCE_OPERATOR,
                            sender::sendMessage,
                            args.length == 3 && args[2].equalsIgnoreCase("acknowledge"));
                    return true;
                }
            }
            sender.sendMessage(color("&cThis FragGuard command requires an in-game OP. "
                    + "Console supports only /fg maintenance start [acknowledge] "
                    + "and /fg maintenance status."));
            return true;
        }

        if (!player.isOp() || !player.hasPermission("fragguard.admin")) {
            player.sendMessage(color("&cOnly server operators can use FragGuard commands."));
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendHelp(player);
            return true;
        }

        String subCommand = args[0].toLowerCase(Locale.ROOT);
        switch (subCommand) {
            case "lookup", "inspect", "l" -> handleLookup(player, Arrays.copyOfRange(args, 1, args.length));
            case "rollback", "rb" -> {
                if (args.length >= 2 && args[1].equalsIgnoreCase("confirm")) {
                    handleRollbackConfirmation(player, args);
                } else if (args.length >= 2 && args[1].equalsIgnoreCase("cancel")) {
                    handleRollbackCancel(player, args);
                } else {
                    handleRollback(player, Arrays.copyOfRange(args, 1, args.length));
                }
            }
            case "undo" -> handleUndo(player, args);
            case "diagnostics", "diag" -> handleDiagnostics(player, Arrays.copyOfRange(args, 1, args.length));
            case "maintenance" -> handleMaintenance(player, Arrays.copyOfRange(args, 1, args.length));
            case "status" -> {
                if (args.length == 2) {
                    sendRollbackJobStatus(player, args[1]);
                } else {
                    sendDatabaseStatus(player);
                }
            }
            default -> {
                player.sendMessage(color("&cUnknown FragGuard command. Use &f/fg help&c."));
                return true;
            }
        }

        return true;
    }

    private void handleLookup(Player player, String[] args) {
        if (PlayerLookupArguments.isPlayerScope(args)) {
            handlePlayerLookup(player, args);
            return;
        }
        OptionalInt radiusOptional;
        try {
            radiusOptional = RadiusParser.parse(args);
        } catch (IllegalArgumentException exception) {
            player.sendMessage(color("&c" + exception.getMessage()));
            return;
        }
        int radius = radiusOptional.orElse(15);
        int maxRadius = Math.max(1, plugin.getConfig().getInt("max-lookup-radius", 150));
        if (radius < 1 || radius > maxRadius) {
            player.sendMessage(color("&cLookup radius must be between 1 and " + maxRadius + "."));
            return;
        }

        int page = parsePage(args).orElse(1);
        int pageSize = Math.max(1, plugin.getConfig().getInt("lookup-page-size", 15));
        int centerX = player.getLocation().getBlockX();
        int centerZ = player.getLocation().getBlockZ();
        String worldName = player.getWorld().getName();

        UUID actor = player.getUniqueId();
        UUID request = UUID.randomUUID();
        lookupRequests.put(actor, request);
        player.sendMessage(color("&7Searching block logs in radius &f" + radius + "&7..."));
        database.lookupAsync(worldName, centerX, centerZ, radius, page, pageSize, plugin.getRetentionDays())
                .whenComplete((lookupPage, throwable) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!request.equals(lookupRequests.get(actor))) {
                        return; // A newer player or radius lookup replaced this request.
                    }
                    lookupRequests.remove(actor);
                    if (!player.isOnline()) {
                        return;
                    }
                    if (throwable != null) {
                        plugin.getLogger().log(Level.WARNING, "FragGuard lookup failed", unwrap(throwable));
                        player.sendMessage(color("&cFragGuard lookup failed. Check console for details."));
                        return;
                    }
                    sendLookupResults(player, lookupPage, radius);
                }));
    }

    private void handlePlayerLookup(Player player, String[] args) {
        PlayerLookupArguments scope;
        try {
            scope = PlayerLookupArguments.parse(args, plugin.getRetentionDays());
        } catch (IllegalArgumentException exception) {
            player.sendMessage(color("&c" + exception.getMessage()));
            player.sendMessage(color("&7Usage: &f/fg lookup u:<player> [w:<world>] [t:<time>] [p:<page>]"));
            return;
        }
        UUID actor = player.getUniqueId();
        UUID request = UUID.randomUUID();
        lookupRequests.put(actor, request);
        int pageSize = Math.min(200, Math.max(1, plugin.getConfig().getInt("lookup-page-size", 15)));
        player.sendMessage(color("&7Searching retained player history across "
                + (scope.worldName() == null ? "all worlds" : scope.worldName()) + "&7..."));
        database.lookupPlayerAsync(scope.playerArgument(), scope.worldName(),
                        scope.page(), pageSize, scope.durationMillis(), plugin.getRetentionDays())
                .whenComplete((result, failure) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!request.equals(lookupRequests.get(actor))) {
                        return; // A later request replaced this one, or the operator disconnected.
                    }
                    lookupRequests.remove(actor);
                    if (!player.isOnline()) {
                        return;
                    }
                    if (failure != null) {
                        Throwable cause = unwrap(failure);
                        if (cause instanceof HistoricalPlayerResolver.IdentityException) {
                            player.sendMessage(color("&c" + cause.getMessage()));
                        } else {
                            plugin.getLogger().log(Level.WARNING, "FragGuard player lookup failed", cause);
                            player.sendMessage(color("&cPlayer history lookup failed. Check console for details."));
                        }
                        return;
                    }
                    sendPlayerLookupResults(player, result, scope);
                }));
    }

    private void sendPlayerLookupResults(Player player, PlayerLookupPage result,
                                         PlayerLookupArguments scope) {
        player.sendMessage(color("&8&m------&r &bFragGuard Player Lookup &8&m------"));
        String uuid = result.player().uuid().toString();
        player.sendMessage(color("&7Player: &f" + result.player().latestName()
                + " &8(" + uuid + ")"));
        player.sendMessage(color("&7World: &f" + (scope.worldName() == null ? "All worlds" : scope.worldName())
                + " &7| Time: &f" + (scope.timeArgument().isEmpty() ? "Retained history" : scope.timeArgument())
                + " &7| Page: &f" + result.page() + "/" + result.totalPages()
                + " &7| Results: &f" + result.totalRows()));

        if (result.rows().isEmpty()) {
            player.sendMessage(color("&7No matching block changes in retained history."));
            return;
        }
        long now = System.currentTimeMillis();
        for (PlayerLookupRow row : result.rows()) {
            String transition = readableBlockData(row.beforeData())
                    + " &8→ &f" + readableBlockData(row.afterData());
            String action = row.action().displayPastTense();
            if (row.indirectlyAttributedTo(uuid)) {
                player.sendMessage(color("&b" + result.player().latestName() + " &7caused &e" + action
                        + " &f" + transition + " &8via &e" + row.actorName()
                        + (row.causalCauseType() == null ? "" : " &8[" + row.causalCauseType() + "]")));
            } else {
                player.sendMessage(color("&b" + row.actorName() + " &7" + action
                        + " &f" + transition));
            }
            player.sendMessage(color("&8" + row.worldName() + " &7at &f" + row.x() + " " + row.y()
                    + " " + row.z() + " &8(" + DurationParser.compactAge(now - row.happenedAt())
                    + "; " + DATE_FORMAT.format(Instant.ofEpochMilli(row.happenedAt())) + ")"));
        }
        if (result.page() < result.totalPages()) {
            player.sendMessage(color("&7Next page: &f" + scope.nextPageCommand(result.page() + 1)));
        }
    }

    private void sendLookupResults(Player player, LookupPage page, int radius) {
        player.sendMessage(color("&8&m------&r &bFragGuard Lookup &8&m------"));
        player.sendMessage(color("&7Radius: &f" + radius + " &7| Page: &f" + page.page() + "/" + page.totalPages()
                + " &7| Results: &f" + page.totalRows()));

        if (page.rows().isEmpty()) {
            player.sendMessage(color("&7No block change logs found here in the retained history."));
            return;
        }

        long now = System.currentTimeMillis();
        for (LookupRow row : page.rows()) {
            String blockText = blockTransition(row);
            String age = DurationParser.compactAge(now - row.happenedAt());
            player.sendMessage(color("&b" + row.actorName() + " &7" + row.action().displayPastTense() + " &f" + blockText
                    + " &8at &f" + row.x() + " " + row.y() + " " + row.z()
                    + " &8(" + age + ")"));
        }

        if (page.page() < page.totalPages()) {
            player.sendMessage(color("&7Next page: &f/fg lookup r:" + radius + " p:" + (page.page() + 1)));
        }
    }

    private String blockTransition(LookupRow row) {
        return blockTransition(row.beforeData(), row.afterData());
    }

    private String blockTransition(String beforeData, String afterData) {
        String before = readableBlockData(beforeData);
        String after = readableBlockData(afterData);

        if (beforeData.equals(afterData)) {
            return after;
        }
        if (isAir(beforeData)) {
            return after;
        }
        if (isAir(afterData)) {
            return before;
        }
        return before + " &8→ &f" + after;
    }

    private boolean isAir(String blockData) {
        return blockData.equals("minecraft:air") || blockData.equals("air");
    }

    private String readableBlockData(String blockData) {
        int bracketIndex = blockData.indexOf('[');
        String base = bracketIndex >= 0 ? blockData.substring(0, bracketIndex) : blockData;
        return base.replace("minecraft:", "");
    }

    private void handleRollback(Player player, String[] args) {
        if (PlayerRollbackArguments.isPlayerScope(args)) {
            handlePlayerRollback(player, args);
            return;
        }
        OptionalInt radiusOptional;
        try {
            radiusOptional = RadiusParser.parse(args);
        } catch (IllegalArgumentException exception) {
            player.sendMessage(color("&c" + exception.getMessage()));
            return;
        }
        if (radiusOptional.isEmpty()) {
            player.sendMessage(color("&cMissing radius. Example: &f/fg rollback r:30 t:2d 7h 15m"));
            return;
        }

        int radius = radiusOptional.getAsInt();
        int maxRadius = Math.max(1, plugin.getConfig().getInt("max-rollback-radius", 100));
        if (radius < 1 || radius > maxRadius) {
            player.sendMessage(color("&cRollback radius must be between 1 and " + maxRadius + "."));
            return;
        }

        List<String> timeTokens = parseTimeTokens(args);
        long maxHistoryMillis = plugin.getRetentionDays() * 24L * 60L * 60L * 1000L;
        long durationMillis;
        try {
            durationMillis = DurationParser.parseMillis(timeTokens, maxHistoryMillis);
        } catch (DurationParser.DurationLimitExceededException exception) {
            player.sendMessage(color("&cFragGuard only keeps " + plugin.getRetentionDays() + " days of history."));
            return;
        } catch (IllegalArgumentException exception) {
            player.sendMessage(color("&c" + exception.getMessage()));
            player.sendMessage(color("&7Example: &f/fg rollback r:30 t:2d 7h 15m"));
            return;
        }

        boolean force = Arrays.stream(args).anyMatch(this::isForceToken);
        long snapshotTimestamp = System.currentTimeMillis();
        long targetTimestamp = snapshotTimestamp - durationMillis;
        int centerX = player.getLocation().getBlockX();
        int centerZ = player.getLocation().getBlockZ();
        String worldName = player.getWorld().getName();

        player.sendMessage(color("&7Finding changes to rollback in radius &f" + radius + "&7 back to &f"
                + DATE_FORMAT.format(Instant.ofEpochMilli(targetTimestamp)) + "&7..."));
        int maxBlocks = Math.max(1, plugin.getConfig().getInt("rollback-max-blocks-per-command", 50_000));
        UUID actor = player.getUniqueId();
        UUID queryId = UUID.randomUUID();
        pendingRollbackQueries.put(actor, queryId);
        CompletableFuture<List<RollbackTarget>> query;
        try {
            query = database.rollbackTargetsAsync(
                    worldName, centerX, centerZ, radius, targetTimestamp, snapshotTimestamp,
                    maxBlocks, maximumRollbackSnapshotBytes());
        } catch (RuntimeException exception) {
            pendingRollbackQueries.remove(actor, queryId);
            throw exception;
        }
        reportQueryProgress(player, query, actor, queryId);
        query.whenComplete((targets, throwable) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!queryId.equals(pendingRollbackQueries.get(actor))) {
                return; // The operator cancelled, disconnected, or started a newer search.
            }
            pendingRollbackQueries.remove(actor);
            if (!player.isOnline()) {
                return;
            }
            if (throwable != null) {
                reportRollbackQueryFailure(player, throwable);
                return;
            }
            previewRollback(player, worldName, targets, centerX, centerZ, radius, targetTimestamp,
                    snapshotTimestamp, force, maxBlocks);
        }));
    }

    private void handlePlayerRollback(Player player, String[] args) {
        PlayerRollbackArguments scope;
        try {
            scope = PlayerRollbackArguments.parse(args, plugin.getRetentionDays());
        } catch (IllegalArgumentException exception) {
            player.sendMessage(color("&c" + exception.getMessage()));
            player.sendMessage(color("&7Usage: &f/fg rollback u:<player> t:<time> [w:<world>] [force]"));
            return;
        }
        if (scope.worldName() != null && plugin.getServer().getWorld(scope.worldName()) == null) {
            player.sendMessage(color("&cWorld '" + scope.worldName() + "' is not loaded."));
            return;
        }
        long snapshot = System.currentTimeMillis();
        long targetTime = snapshot - scope.durationMillis();
        int maxBlocks = Math.max(1, plugin.getConfig().getInt("rollback-max-blocks-per-command", 50_000));
        UUID operator = player.getUniqueId();
        UUID queryId = UUID.randomUUID();
        pendingRollbackQueries.put(operator, queryId);
        player.sendMessage(color("&7Planning player rollback for &f" + scope.playerArgument()
                + " &7in &f" + (scope.worldName() == null ? "all worlds" : scope.worldName()) + "&7..."));
        CompletableFuture<PlayerRollbackPlan> query;
        try {
            query = database.planPlayerRollbackAsync(scope.playerArgument(), scope.worldName(),
                    targetTime, snapshot, maxBlocks, maximumRollbackSnapshotBytes(),
                    plugin.getRetentionDays(), scope.force());
        } catch (RuntimeException exception) {
            pendingRollbackQueries.remove(operator, queryId);
            throw exception;
        }
        reportQueryProgress(player, query, operator, queryId);
        query.whenComplete((plan, failure) -> onServerThread(() -> {
            if (!queryId.equals(pendingRollbackQueries.get(operator))) {
                return;
            }
            pendingRollbackQueries.remove(operator);
            if (!player.isOnline()) {
                return;
            }
            if (failure != null) {
                reportRollbackQueryFailure(player, failure);
                return;
            }
            previewPlayerRollback(player, scope, plan);
        }));
    }

    /**
     * GUI preview registration reuses the exact command token/expiration/cancel
     * lifecycle instead of creating a second source of confirmation authority.
     */
    String registerPlayerGuiPreview(Player operator, PlayerRollbackArguments scope,
                                    PlayerRollbackPlan plan) {
        if (!operator.isOp() || !operator.hasPermission("fragguard.admin")) {
            throw new IllegalStateException("Only operators can preview player rollbacks.");
        }
        previewPlayerRollback(operator, scope, plan);
        return previews.values().stream()
                .filter(preview -> preview.actorUuid().equals(operator.getUniqueId())
                        && preview.isPlayerScope() && preview.playerPlan() == plan)
                .map(RollbackPreview::token)
                .findFirst().orElse(null);
    }

    private void previewPlayerRollback(Player operator, PlayerRollbackArguments scope,
                                       PlayerRollbackPlan plan) {
        if (plan.targets().isEmpty()) {
            operator.sendMessage(color("&7No safe block changes for &f" + plan.player().latestName()
                    + "&7. Historical conflicts: &f" + plan.historicalConflicts()
                    + "&7 | Unavailable: &f" + plan.unavailable()));
            return;
        }
        int affectedChunks = RollbackChunkPlan.countTargetChunks(plan.targets());
        int maxChunks = Math.max(1, plugin.getConfig().getInt("rollback-max-chunks-per-command", 256));
        if (affectedChunks > maxChunks) {
            operator.sendMessage(color("&cPlayer rollback touches " + affectedChunks
                    + " chunks, exceeding the configured limit of " + maxChunks + "."));
            return;
        }
        int affectedWorlds = (int) plan.targets().stream().map(RollbackTarget::worldUuid).distinct().count();
        long now = System.currentTimeMillis();
        long timeoutSeconds = Math.max(5, plugin.getConfig().getInt(
                "rollback-confirmation-timeout-seconds", 60));
        removeExpiredPreviews(now);
        removePreviewsFor(operator.getUniqueId());
        String token = UUID.randomUUID().toString().substring(0, 8);
        RollbackPreview preview = new RollbackPreview(token, operator.getUniqueId(), operator.getName(),
                scope.worldName() == null ? "*" : scope.worldName(), 0, 0, 0,
                plan.targetTimestamp(), plan.snapshotTimestamp(), scope.force(),
                List.copyOf(plan.targets()), now + timeoutSeconds * 1000L,
                plan, scope.worldName());
        previews.put(token, preview);
        schedulePreviewExpiration(preview, now);

        operator.sendMessage(color("&8&m------&r &eFragGuard Player Rollback Preview &8&m------"));
        operator.sendMessage(color("&7Target: &f" + plan.player().latestName()
                + " &8(" + plan.player().uuid() + ")"));
        operator.sendMessage(color("&7World scope: &f" + (scope.worldName() == null ? "All" : scope.worldName())
                + " &7| Worlds affected: &f" + affectedWorlds));
        operator.sendMessage(color("&7Reversible blocks: &f" + plan.reversibleCoordinates()
                + " &7| Chunks: &f" + affectedChunks));
        operator.sendMessage(color("&7Historical conflicts: &e" + plan.historicalConflicts()
                + " &7| No-ops: &f" + plan.noOps() + " &7| Unavailable: &f" + plan.unavailable()));
        operator.sendMessage(color("&7Since: &f" + DATE_FORMAT.format(Instant.ofEpochMilli(plan.targetTimestamp()))
                + " &7| Snapshot: &f" + DATE_FORMAT.format(Instant.ofEpochMilli(plan.snapshotTimestamp()))));
        operator.sendMessage(color(scope.force()
                ? "&cForce live-state mode; historical ownership protection still applies."
                : "&aLive conflicts will be skipped; historical ownership is always protected."));
        operator.sendMessage(color("&7This is a scattered player rollback: no single radius outline is drawn."));
        operator.sendMessage(color("&eNo blocks have changed. Confirm within " + timeoutSeconds
                + " seconds: &f/fg rollback confirm " + token));
        operator.sendMessage(color("&7Cancel with: &f/fg rollback cancel"));
    }

    private void reportRollbackQueryFailure(Player player, Throwable throwable) {
        Throwable cause = unwrap(throwable);
        if (cause instanceof TimeoutException) {
            player.sendMessage(color("&cRollback preview timed out. Reduce the radius or time range and try again."));
        } else if (cause instanceof Database.PlayerRollbackLimitExceededException
                || cause instanceof HistoricalPlayerResolver.IdentityException
                || cause instanceof IllegalArgumentException) {
            player.sendMessage(color("&c" + cause.getMessage()));
        } else if (cause instanceof Database.RollbackSnapshotLimitExceededException limit) {
            player.sendMessage(color("&cRollback preview exceeds the configured block-entity snapshot limit of "
                    + limit.maximumBytes() + " bytes."));
            player.sendMessage(color("&7Reduce the radius or time range, or raise "
                    + "&frollback-max-snapshot-bytes-per-command&7 in config.yml."));
        } else {
            plugin.getLogger().log(Level.WARNING, "FragGuard rollback query failed", cause);
            player.sendMessage(color("&cRollback query failed. Check console for details."));
        }
    }

    private void previewRollback(Player player, String worldName, List<RollbackTarget> targets,
                                 int centerX, int centerZ, int radius, long targetTimestamp,
                                 long snapshotTimestamp, boolean force, int maxBlocks) {
        if (!player.isOnline()) {
            removePreviewsFor(player.getUniqueId());
            return;
        }
        if (targets.isEmpty()) {
            player.sendMessage(color("&7No block changes found to rollback in that radius."));
            return;
        }
        if (targets.size() > maxBlocks) {
            player.sendMessage(color("&cRollback would affect more than " + maxBlocks
                    + " blocks, which exceeds the configured limit."));
            player.sendMessage(color("&7Raise &frollback-max-blocks-per-command&7 in config.yml if you want to allow this."));
            return;
        }

        int affectedChunks = RollbackChunkPlan.countTargetChunks(targets);
        int maxChunks = Math.max(1, plugin.getConfig().getInt("rollback-max-chunks-per-command", 256));
        if (affectedChunks > maxChunks) {
            player.sendMessage(color("&cRollback would affect " + affectedChunks
                    + " chunks, which exceeds the configured limit of " + maxChunks + "."));
            player.sendMessage(color("&7Reduce the radius or raise &frollback-max-chunks-per-command"
                    + "&7 in config.yml if you want to allow this."));
            return;
        }

        long expirationSeconds = Math.max(5, plugin.getConfig().getInt("rollback-confirmation-timeout-seconds", 60));
        long now = System.currentTimeMillis();
        removeExpiredPreviews(now);
        removePreviewsFor(player.getUniqueId());
        String token = UUID.randomUUID().toString().substring(0, 8);
        RollbackPreview preview = new RollbackPreview(token, player.getUniqueId(), player.getName(),
                worldName, centerX, centerZ, radius, targetTimestamp, snapshotTimestamp,
                force, List.copyOf(targets), now + expirationSeconds * 1_000L);
        previews.put(token, preview);
        schedulePreviewExpiration(preview, now);
        areaPreview.show(player, preview.token, preview.worldName,
                preview.centerX, preview.centerZ, preview.radius, preview.expiresAt);

        player.sendMessage(color("&8&m------&r &eFragGuard Rollback Preview &8&m------"));
        player.sendMessage(color("&7Affected blocks: &f" + targets.size() + " &7| Chunks: &f" + affectedChunks
                + " &7| Radius: &f" + radius));
        player.sendMessage(color("&7Target time: &f" + DATE_FORMAT.format(Instant.ofEpochMilli(targetTimestamp))));
        player.sendMessage(color("&7Snapshot: &f" + DATE_FORMAT.format(Instant.ofEpochMilli(snapshotTimestamp))));
        if (force) {
            player.sendMessage(color("&cFORCE mode: newer conflicting block states will be overwritten after revalidation."));
        } else {
            player.sendMessage(color("&aConflict protection: newer block changes will be skipped and reported."));
        }
        if (RollbackPreviewVisualSettings.from(plugin.getConfig()).enabled()) {
            player.sendMessage(color("&bThe rollback boundary is visible only to you. "
                    + "&7Perimeter, vertical extent, and center use the configured colors."));
        } else {
            player.sendMessage(color("&7Rollback visualization is disabled in config.yml."));
        }
        player.sendMessage(color("&eNo blocks have been changed. Confirm within " + expirationSeconds
                + " seconds: &f/fg rollback confirm " + token));
        player.sendMessage(color("&7To dismiss this preview: &f/fg rollback cancel"));
    }

    private void schedulePreviewExpiration(RollbackPreview preview, long now) {
        long remainingMillis = Math.max(1L, preview.expiresAt - now);
        long delayTicks = Math.max(1L, (remainingMillis + 49L) / 50L);
        plugin.getServer().getScheduler().runTaskLater(plugin,
                () -> expirePreview(preview, System.currentTimeMillis()), delayTicks);
    }

    private void expirePreview(RollbackPreview preview, long now) {
        if (previews.get(preview.token) != preview) {
            return;
        }
        if (now < preview.expiresAt) {
            schedulePreviewExpiration(preview, now);
            return;
        }
        previews.remove(preview.token);
        areaPreview.stop(preview.actorUuid, preview.token);
    }

    private void removeExpiredPreviews(long now) {
        previews.entrySet().removeIf(entry -> {
            RollbackPreview preview = entry.getValue();
            if (preview.expiresAt > now) {
                return false;
            }
            areaPreview.stop(preview.actorUuid, preview.token);
            return true;
        });
    }

    private void removePreviewsFor(UUID actorUuid) {
        previews.entrySet().removeIf(entry -> {
            RollbackPreview preview = entry.getValue();
            if (!preview.actorUuid.equals(actorUuid)) {
                return false;
            }
            areaPreview.stop(actorUuid, preview.token);
            return true;
        });
    }

    private void handleRollbackCancel(Player player, String[] args) {
        if (args.length != 2) {
            player.sendMessage(color("&cUsage: &f/fg rollback cancel"));
            return;
        }
        UUID actor = player.getUniqueId();
        boolean pending = pendingRollbackQueries.remove(actor) != null;
        boolean active = previews.values().stream().anyMatch(preview -> preview.actorUuid.equals(actor));
        removePreviewsFor(actor);
        if (pending || active) {
            player.sendMessage(color("&aRollback preview cancelled. &7No rollback job was started."));
        } else {
            player.sendMessage(color("&7You have no active rollback preview to cancel."));
        }
    }

    private void handleRollbackConfirmation(Player player, String[] args) {
        if (args.length != 3) {
            player.sendMessage(color("&cUsage: &f/fg rollback confirm <token>"));
            return;
        }

        RollbackPreview preview = previews.get(args[2]);
        if (preview == null) {
            player.sendMessage(color("&cThat rollback confirmation token is invalid or has expired."));
            return;
        }
        long now = System.currentTimeMillis();
        if (preview.expiresAt <= now) {
            expirePreview(preview, now);
            player.sendMessage(color("&cThat rollback confirmation token is invalid or has expired."));
            return;
        }
        if (!preview.actorUuid.equals(player.getUniqueId())) {
            player.sendMessage(color("&cThat rollback confirmation belongs to another operator."));
            return;
        }
        if (!preview.isPlayerScope() && !preview.worldName.equals(player.getWorld().getName())) {
            player.sendMessage(color("&cReturn to the previewed world before confirming this rollback."));
            return;
        }

        // Confirmation invalidates any in-flight replacement search as well.
        pendingRollbackQueries.remove(preview.actorUuid);
        if (previews.get(preview.token) == preview) {
            previews.remove(preview.token);
            areaPreview.stop(preview.actorUuid, preview.token);
        }
        // Multi-world previews are bound to their stable world identities,
        // not whichever name another world may acquire before confirmation.
        if (preview.isPlayerScope()) {
            for (RollbackTarget target : preview.targets) {
                World currentWorld;
                try {
                    currentWorld = plugin.getServer().getWorld(UUID.fromString(target.worldUuid()));
                } catch (IllegalArgumentException exception) {
                    currentWorld = null;
                }
                if (currentWorld == null || !currentWorld.getName().equals(target.worldName())) {
                    player.sendMessage(color("&cA previewed world was renamed or unloaded. Re-run the player rollback."));
                    return;
                }
            }
        }
        player.sendMessage(color("&7Saving rollback job and undo information..."));
        CompletableFuture<RollbackJob> saving = preview.isPlayerScope()
                ? database.createPlayerRollbackJobAsync(preview.actorUuid.toString(),
                        preview.actorName, preview.playerPlan, preview.playerWorldScope, preview.force)
                : database.createRollbackJobAsync(preview.actorUuid.toString(), preview.actorName, preview.worldName,
                        preview.centerX, preview.centerZ, preview.radius, preview.targetTimestamp,
                        preview.snapshotTimestamp, preview.force, preview.targets);
        saving.whenComplete((job, throwable) -> onServerThread(() -> {
                    if (throwable != null) {
                        reportJobError(player, "Could not start rollback", throwable);
                        return;
                    }
                    player.sendMessage(color("&eRollback job #" + job.id() + " started. &7Blocks: &f"
                            + job.totalBlocks() + " &7| Undo: &f/fg undo " + job.id()));
                    executeJob(job, player, false);
                }));
    }

    private void handleUndo(Player player, String[] args) {
        if (args.length != 2) {
            player.sendMessage(color("&cUsage: &f/fg undo <job-id>"));
            return;
        }
        long jobId;
        try {
            jobId = Long.parseLong(args[1]);
            if (jobId < 1) {
                throw new NumberFormatException("Job IDs must be positive");
            }
        } catch (NumberFormatException exception) {
            player.sendMessage(color("&cEnter a valid rollback job ID."));
            return;
        }

        database.beginUndoAsync(jobId).whenComplete((job, throwable) -> onServerThread(() -> {
            if (throwable != null) {
                reportJobError(player, "Could not undo rollback", throwable);
                return;
            }
            player.sendMessage(color("&eUndoing rollback job #" + job.id() + "..."));
            executeJob(job, player, true);
        }));
    }

    /** Main-thread guard: never close SQLite writes while a world job callback is live. */
    boolean hasExecutingRollbackJobs() {
        return !executingJobs.isEmpty();
    }

    void resumeInterruptedJobs() {
        database.loadResumableJobsAsync().whenComplete((jobs, throwable) -> onServerThread(() -> {
            // A new maintenance transition can happen while the async read
            // is running. Re-defer instead of starting unaudited world edits.
            if (plugin.maintenanceBlocksNewJobs()) {
                plugin.deferRollbackRecovery();
                return;
            }
            if (throwable != null) {
                plugin.deferRollbackRecovery();
                plugin.getLogger().log(Level.SEVERE, "Could not recover interrupted FragGuard rollback jobs", unwrap(throwable));
                return;
            }
            for (RollbackJob job : jobs) {
                boolean undo = job.status().equals("UNDOING");
                plugin.getLogger().warning("Resuming interrupted " + (undo ? "undo" : "rollback")
                        + " job #" + job.id() + ".");
                Player operator;
                try {
                    operator = Bukkit.getPlayer(UUID.fromString(job.actorUuid()));
                } catch (IllegalArgumentException exception) {
                    operator = null;
                }
                executeJob(job, operator, undo);
            }
        }));
    }

    private void executeJob(RollbackJob job, Player operator, boolean undo) {
        if (!executingJobs.add(job.id())) {
            return;
        }
        database.loadRollbackChangesAsync(job.id(), undo, maximumRollbackSnapshotBytes())
                .thenApply(RollbackChunkPlan::group)
                .whenComplete((plan, throwable) -> onServerThread(() -> {
                    if (throwable != null) {
                        failJob(job, operator, throwable);
                        return;
                    }
                    int maxChunks = Math.max(1,
                            plugin.getConfig().getInt("rollback-max-chunks-per-command", 256));
                    if (plan.chunkCount() > maxChunks) {
                        failJob(job, operator, new IllegalStateException(
                                "Rollback spans " + plan.chunkCount() + " chunks, exceeding the configured "
                                        + "rollback-max-chunks-per-command limit of " + maxChunks + "."));
                        return;
                    }
                    runJobBatch(job, operator, plan.changes(), 0, undo, -1);
                }));
    }

    private void runJobBatch(RollbackJob job, Player operator, List<RollbackJobChange> changes,
                             int index, boolean undo, int previousProgress) {
        if (index >= changes.size()) {
            releaseJobChunk(job.id());
            pausedJobs.remove(job.id());
            database.completeRollbackJobAsync(job.id(), undo).whenComplete((completedJob, throwable) -> onServerThread(() -> {
                executingJobs.remove(job.id());
                if (throwable != null) {
                    failJob(job, operator, throwable);
                    return;
                }
                if (undo && !completedJob.status().equals("UNDONE")) {
                    String detail = Objects.requireNonNullElse(completedJob.lastError(),
                            "Undo left unresolved conflicts; run /fg undo " + job.id() + " to retry.");
                    message(operator, "&eUndo job #" + job.id() + " is incomplete. &7" + detail);
                    return;
                }
                String conflictText = !undo && completedJob.conflictBlocks() > 0
                        ? " &eSkipped " + completedJob.conflictBlocks() + " conflicting block(s)."
                        : "";
                message(operator, "&a" + (undo ? "Undo" : "Rollback") + " job #" + job.id()
                        + " complete. &7Processed &f" + changes.size() + "&7 blocks."
                        + conflictText + (undo ? "" : " &7Reverse it with &f/fg undo " + job.id()));
            }));
            return;
        }

        RollbackChunkPlan.ChunkKey chunk = RollbackChunkPlan.ChunkKey.from(changes.get(index));
        RollbackChunkPlan.ChunkKey previousChunk = loadedJobChunks.get(job.id());
        if (previousChunk != null && !previousChunk.equals(chunk)) {
            releaseJobChunk(job.id());
        }

        Runnable retry = () -> runJobBatch(job, operator, changes, index, undo, previousProgress);
        if (pauseForLowTps(job, operator, undo, retry)) {
            return;
        }

        int blocksPerTick = Math.max(1, plugin.getConfig().getInt("rollback-blocks-per-tick", 500));
        int end = index;
        while (end < changes.size() && end - index < blocksPerTick
                && chunk.equals(RollbackChunkPlan.ChunkKey.from(changes.get(end)))) {
            end++;
        }

        int nextIndex = end;
        List<RollbackJobChange> batch = List.copyOf(changes.subList(index, end));
        ensureChunkLoaded(job, operator, chunk, () ->
                applyPreparedBatch(job, operator, changes, batch, nextIndex, undo, previousProgress));
    }

    private void ensureChunkLoaded(RollbackJob job, Player operator, RollbackChunkPlan.ChunkKey chunk,
                                   Runnable afterLoaded) {
        if (!executingJobs.contains(job.id())) {
            return;
        }

        World world = resolveChunkWorld(chunk);
        if (world == null) {
            failJob(job, operator, new IllegalStateException(
                    "World '" + chunk.worldName() + "' is not loaded or its UUID has changed."));
            return;
        }

        RollbackChunkPlan.ChunkKey previous = loadedJobChunks.get(job.id());
        if (chunk.equals(previous) && world.isChunkLoaded(chunk.x(), chunk.z())) {
            afterLoaded.run();
            return;
        }
        if (previous != null) {
            releaseJobChunk(job.id());
        }

        if (world.isChunkLoaded(chunk.x(), chunk.z())) {
            retainJobChunk(job, operator, world, chunk, afterLoaded);
            return;
        }

        world.getChunkAtAsync(chunk.x(), chunk.z(), false)
                .whenComplete((loaded, throwable) -> {
                    // Paper completes chunk-load futures on the main server thread.
                    if (!executingJobs.contains(job.id())) {
                        return;
                    }
                    if (throwable != null) {
                        failJob(job, operator, throwable);
                        return;
                    }
                    if (loaded == null) {
                        failJob(job, operator, new IllegalStateException(
                                "Chunk " + chunk.x() + ", " + chunk.z() + " in world '"
                                        + chunk.worldName() + "' does not exist; rollback will not generate terrain."));
                        return;
                    }
                    if (!world.isChunkLoaded(chunk.x(), chunk.z())) {
                        ensureChunkLoaded(job, operator, chunk, afterLoaded);
                        return;
                    }
                    retainJobChunk(job, operator, world, chunk, afterLoaded);
                });
    }

    private void retainJobChunk(RollbackJob job, Player operator, World world,
                                RollbackChunkPlan.ChunkKey chunk, Runnable afterLoaded) {
        try {
            int references = chunkTicketReferences.getOrDefault(chunk, 0);
            if (references == 0) {
                world.addPluginChunkTicket(chunk.x(), chunk.z(), plugin);
            }
            chunkTicketReferences.put(chunk, references + 1);
            loadedJobChunks.put(job.id(), chunk);
            afterLoaded.run();
        } catch (RuntimeException exception) {
            failJob(job, operator, exception);
        }
    }

    private void releaseJobChunk(long jobId) {
        RollbackChunkPlan.ChunkKey chunk = loadedJobChunks.remove(jobId);
        if (chunk == null) {
            return;
        }

        int references = chunkTicketReferences.getOrDefault(chunk, 0) - 1;
        if (references > 0) {
            chunkTicketReferences.put(chunk, references);
            return;
        }

        chunkTicketReferences.remove(chunk);
        World world = resolveChunkWorld(chunk);
        if (world != null) {
            world.removePluginChunkTicket(chunk.x(), chunk.z(), plugin);
        }
    }

    private World resolveChunkWorld(RollbackChunkPlan.ChunkKey chunk) {
        World byName = Bukkit.getWorld(chunk.worldName());
        if (chunk.worldUuid() == null || chunk.worldUuid().isBlank()) {
            return byName; // Legacy unit tests and older jobs with no UUID.
        }
        if (byName != null && (byName.getUID() == null
                || chunk.worldUuid().equals(byName.getUID().toString()))) {
            return byName;
        }
        try {
            return Bukkit.getWorld(UUID.fromString(chunk.worldUuid()));
        } catch (IllegalArgumentException invalidWorldId) {
            return null;
        }
    }

    private boolean pauseForLowTps(RollbackJob job, Player operator, boolean undo, Runnable resume) {
        double minimumTps = Math.max(0.0,
                Math.min(20.0, plugin.getConfig().getDouble("rollback-minimum-tps", 18.0)));
        double[] recentTps = plugin.getServer().getTPS();
        double currentTps = recentTps.length == 0 ? 20.0 : recentTps[0];
        if (minimumTps > 0.0 && currentTps < minimumTps) {
            if (pausedJobs.add(job.id())) {
                message(operator, "&e" + (undo ? "Undo" : "Rollback") + " job #" + job.id()
                        + " paused while server TPS is &f" + String.format(Locale.ROOT, "%.1f", currentTps)
                        + "&e (minimum &f" + String.format(Locale.ROOT, "%.1f", minimumTps) + "&e)." );
            }
            retryJobLater(job, resume, 20L);
            return true;
        }

        if (pausedJobs.remove(job.id())) {
            message(operator, "&a" + (undo ? "Undo" : "Rollback") + " job #" + job.id()
                    + " resumed after server TPS recovered.");
        }
        return false;
    }

    private boolean beginWorkSlice(RollbackJob job, Player operator, boolean undo, Runnable retry) {
        if (pauseForLowTps(job, operator, undo, retry)) {
            return false;
        }

        if (!rollbackTickBudget.begin(plugin.getServer().getCurrentTick(),
                System.nanoTime(), maximumWorkNanos())) {
            retryJobLater(job, retry, 1L);
            return false;
        }
        return true;
    }

    private long maximumWorkNanos() {
        double maximumMillis = Math.max(0.1,
                Math.min(50.0, plugin.getConfig().getDouble("rollback-max-millis-per-tick", 4.0)));
        return Math.max(1L, (long) (maximumMillis * 1_000_000.0));
    }

    private long maximumRollbackSnapshotBytes() {
        return Math.max(1L, plugin.getConfig().getLong(
                "rollback-max-snapshot-bytes-per-command", Database.DEFAULT_MAX_ROLLBACK_SNAPSHOT_BYTES));
    }

    private void retryJobLater(RollbackJob job, Runnable retry, long ticks) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (executingJobs.contains(job.id())) {
                retry.run();
            }
        }, ticks);
    }

    private void applyPreparedBatch(RollbackJob job, Player operator, List<RollbackJobChange> changes,
                                     List<RollbackJobChange> batch, int nextIndex, boolean undo,
                                     int previousProgress) {
        Runnable retry = () -> applyPreparedBatch(job, operator, changes, batch,
                nextIndex, undo, previousProgress);
        if (!beginWorkSlice(job, operator, undo, retry)) {
            return;
        }

        List<RollbackJobChange> preparedBatch = new ArrayList<>();
        Map<Integer, RollbackStepResult> results = new HashMap<>();
        List<PreparedWorldChange> candidates = new ArrayList<>();
        RuntimeException failure = null;
        try {
            for (RollbackJobChange queued : batch) {
                if (!preparedBatch.isEmpty() && rollbackTickBudget.exhausted(System.nanoTime())) {
                    break;
                }
                World world = resolveChunkWorld(RollbackChunkPlan.ChunkKey.from(queued));
                if (world == null) {
                    throw new IllegalStateException("World '" + queued.worldName()
                            + "' is not loaded or its saved UUID no longer matches.");
                }
                RollbackJobChange change = queued.withWorldName(world.getName());
                String desiredData = undo ? change.beforeData() : change.targetData();
                BlockData desired = Bukkit.createBlockData(Objects.requireNonNull(desiredData, "Missing saved block state"));
                Block block = world.getBlockAt(change.x(), change.y(), change.z());
                String actualData = block.getBlockData().getAsString();
                byte[] actualEntityData = BlockEntitySnapshot.capture(block);
                byte[] desiredEntityData = undo ? change.beforeEntityData() : change.targetEntityData();
                String normalizedDesired = desired.getAsString();
                preparedBatch.add(change);

                if (change.pendingAuditId() != null) {
                    if (change.pendingAuditUndo() != undo) {
                        throw new IllegalStateException("Rollback job #" + job.id() + " change "
                                + change.sequence() + " has a pending audit for the wrong operation.");
                    }
                    if (undo && change.appliedData() == null) {
                        // Legacy rows do not have a durable record of the physics-normalized rollback result.
                        // A pending undo audit therefore cannot prove whether its world mutation happened
                        // before a crash. Fail this coordinate closed and let a later /fg undo retry from a
                        // fresh live snapshot instead of treating target_data as the pre-mutation state.
                        results.put(change.sequence(), new RollbackStepResult(change.sequence(), false, true));
                        continue;
                    }
                    String pendingBeforeData = undo
                            ? Objects.requireNonNull(change.appliedData(), "Missing observed rollback state")
                            : Objects.requireNonNull(change.beforeData(), "Missing prepared rollback state");
                    byte[] pendingBeforeEntityData = undo ? change.appliedEntityData() : change.beforeEntityData();
                    if (!matchesState(actualData, actualEntityData,
                            pendingBeforeData, pendingBeforeEntityData)) {
                        // A pending audit proves that FragGuard intended a mutation, but it cannot prove
                        // that FragGuard caused the live state now present after a crash. Another player,
                        // plugin, physics/startup processing, or a later edit could have moved the block.
                        // Fail closed so an unrelated live state is never claimed as FragGuard's applied
                        // result and later overwritten by /fg undo.
                        results.put(change.sequence(), new RollbackStepResult(change.sequence(), false, true));
                        continue;
                    }
                }

                if (undo) {
                    if (matchesState(actualData, actualEntityData, normalizedDesired, desiredEntityData)) {
                        results.put(change.sequence(), new RollbackStepResult(change.sequence(), false, false));
                    } else if (change.appliedData() != null
                            && ((change.appliedEntityData() == null && actualEntityData != null)
                            || !matchesState(actualData, actualEntityData,
                                    change.appliedData(), change.appliedEntityData()))) {
                        // A known applied block state paired with a missing entity snapshot means the
                        // post-mutation capture failed. Do not let null act as a wildcard over inventories,
                        // signs, or other supported block-entity data during undo conflict checking.
                        results.put(change.sequence(), new RollbackStepResult(change.sequence(), false, true));
                    } else {
                        candidates.add(new PreparedWorldChange(change, block, desired, actualData,
                                actualEntityData, desiredEntityData));
                    }
                    continue;
                }

                String expectedData = Objects.requireNonNullElse(change.expectedData(), actualData);
                if (matchesState(actualData, actualEntityData, normalizedDesired, desiredEntityData)) {
                    results.put(change.sequence(), new RollbackStepResult(change.sequence(), false, false));
                } else if (!job.force()
                        && !matchesState(actualData, actualEntityData, expectedData, change.expectedEntityData())) {
                    results.put(change.sequence(), new RollbackStepResult(change.sequence(), false, true));
                } else {
                    candidates.add(new PreparedWorldChange(change, block, desired, actualData,
                            actualEntityData, desiredEntityData));
                }
            }
        } catch (RuntimeException exception) {
            failure = exception;
        } finally {
            rollbackTickBudget.end(System.nanoTime());
        }
        if (failure != null) {
            persistCompletedResultsBeforeFailure(job, operator, results, undo, failure);
            return;
        }

        int preparedNextIndex = nextIndex - batch.size() + preparedBatch.size();
        List<BlockChange> observedCorrections = new ArrayList<>();
        persistAndApplyCandidates(job, operator, candidates, results, observedCorrections,
                undo, false, 0, () -> {
                    List<RollbackStepResult> orderedResults = preparedBatch.stream()
                            .map(change -> results.getOrDefault(change.sequence(),
                                    new RollbackStepResult(change.sequence(), false, false)))
                            .toList();
                    persistBatchResults(job, operator, changes, orderedResults,
                            preparedNextIndex, undo, previousProgress);
                });
    }

    private void persistAndApplyCandidates(RollbackJob job, Player operator,
                                           List<PreparedWorldChange> candidates,
                                           Map<Integer, RollbackStepResult> results,
                                           List<BlockChange> observedCorrections,
                                           boolean undo, boolean replaceBeforeData,
                                           int forceAttempt, Runnable afterApplied) {
        persistAndApplyCandidateSlice(job, operator, candidates, results, observedCorrections,
                undo, replaceBeforeData, forceAttempt, afterApplied, 0);
    }

    private void persistAndApplyCandidateSlice(RollbackJob job, Player operator,
                                               List<PreparedWorldChange> candidates,
                                               Map<Integer, RollbackStepResult> results,
                                               List<BlockChange> observedCorrections,
                                               boolean undo, boolean replaceBeforeData,
                                               int forceAttempt, Runnable afterApplied,
                                               int startIndex) {
        if (startIndex >= candidates.size()) {
            afterApplied.run();
            return;
        }

        Runnable retry = () -> persistAndApplyCandidateSlice(job, operator, candidates,
                results, observedCorrections, undo, replaceBeforeData,
                forceAttempt, afterApplied, startIndex);
        if (!beginWorkSlice(job, operator, undo, retry)) {
            return;
        }

        int endIndex;
        List<PreparedWorldChange> slice;
        try {
            endIndex = Math.min(startIndex + MAX_AUDITED_CHANGES_PER_SLICE, candidates.size());
            slice = List.copyOf(candidates.subList(startIndex, endIndex));
        } finally {
            rollbackTickBudget.end(System.nanoTime());
        }

        CompletableFuture<Void> prepared;
        if (undo) {
            prepared = CompletableFuture.completedFuture(null);
        } else {
            List<RollbackJobChange> preparedChanges = slice.stream()
                    .map(candidate -> candidate.change().withBeforeState(
                            candidate.beforeData(), candidate.beforeEntityData()))
                    .toList();
            prepared = replaceBeforeData
                    ? database.replaceRollbackBeforeDataAsync(job.id(), preparedChanges)
                    : database.prepareRollbackBatchAsync(job.id(), preparedChanges);
        }

        prepared.whenComplete((ignored, throwable) -> onServerThread(() -> {
            if (throwable != null) {
                persistCompletedResultsBeforeFailure(job, operator, results, undo, throwable);
                return;
            }

            persistPreparedCandidateSlice(job, operator, candidates, slice, results,
                    observedCorrections, undo, replaceBeforeData, forceAttempt,
                    afterApplied, endIndex);
        }));
    }

    private void persistPreparedCandidateSlice(RollbackJob job, Player operator,
                                               List<PreparedWorldChange> candidates,
                                               List<PreparedWorldChange> slice,
                                               Map<Integer, RollbackStepResult> results,
                                               List<BlockChange> observedCorrections,
                                               boolean undo, boolean replaceBeforeData,
                                               int forceAttempt, Runnable afterApplied,
                                               int nextIndex) {
        Runnable retry = () -> persistPreparedCandidateSlice(job, operator, candidates,
                slice, results, observedCorrections, undo, replaceBeforeData,
                forceAttempt, afterApplied, nextIndex);
        if (!beginWorkSlice(job, operator, undo, retry)) {
            return;
        }

        List<RollbackPendingAudit> audits;
        try {
            audits = slice.stream()
                    .map(candidate -> new RollbackPendingAudit(
                            candidate.change().sequence(),
                            RollbackAudit.create(
                                    job,
                                    candidate.block(),
                                    candidate.beforeData(),
                                    candidate.desired().getAsString(),
                                    candidate.beforeEntityData(),
                                    candidate.desiredEntityData(),
                                    undo
                            )))
                    .toList();
        } catch (RuntimeException exception) {
            persistCompletedResultsBeforeFailure(job, operator, results, undo, exception);
            return;
        } finally {
            rollbackTickBudget.end(System.nanoTime());
        }

        persistPendingAudits(job, operator, undo, audits, results, auditIds -> applyPersistedCandidates(
                job, operator, slice, auditIds, results, observedCorrections,
                undo, forceAttempt, () -> persistAndApplyCandidateSlice(
                        job, operator, candidates, results, observedCorrections,
                        undo, replaceBeforeData, forceAttempt, afterApplied, nextIndex)));
    }

    private void applyPersistedCandidates(RollbackJob job, Player operator,
                                          List<PreparedWorldChange> candidates,
                                          List<Long> auditIds,
                                          Map<Integer, RollbackStepResult> results,
                                          List<BlockChange> observedCorrections,
                                          boolean undo, int forceAttempt,
                                          Runnable afterApplied) {
        if (auditIds.size() != candidates.size()) {
            persistCompletedResultsBeforeFailure(job, operator, results, undo,
                    new IllegalStateException(
                            "Rollback audit persistence returned an unexpected number of record IDs."));
            return;
        }

        // An acknowledged audit must never survive a budget/TPS pause before its block is mutated.
        rollbackTickBudget.beginCommitted(plugin.getServer().getCurrentTick(),
                System.nanoTime(), maximumWorkNanos());
        boolean applyPhysics = plugin.getConfig().getBoolean("apply-physics-during-rollback", false);
        List<Long> staleAuditIds = new ArrayList<>();
        List<RollbackJobChange> forceRetries = new ArrayList<>();
        int index = 0;
        RuntimeException failure = null;
        try {
            while (index < candidates.size()) {
                PreparedWorldChange candidate = candidates.get(index);
                long auditId = auditIds.get(index);
                Block block = candidate.block();
                String actualData = block.getBlockData().getAsString();
                byte[] actualEntityData = BlockEntitySnapshot.capture(block);
                if (!matchesState(actualData, actualEntityData,
                        candidate.beforeData(), candidate.beforeEntityData())) {
                    staleAuditIds.add(auditId);
                    if (!undo && job.force()) {
                        forceRetries.add(candidate.change());
                    } else {
                        results.put(candidate.change().sequence(),
                                new RollbackStepResult(candidate.change().sequence(), false, true));
                    }
                    index++;
                    continue;
                }

                String desiredData = candidate.desired().getAsString();
                if (applyPhysics) {
                    if (!actualData.equals(desiredData)) {
                        block.setBlockData(candidate.desired(), true);
                    }
                    String resultingData = block.getBlockData().getAsString();
                    try {
                        if (desiredData.equals(resultingData)) {
                            BlockEntitySnapshot.restore(block, candidate.desiredEntityData());
                        } else {
                            BlockEntitySnapshot.restoreIfCompatible(block, candidate.desiredEntityData());
                        }
                    } catch (RuntimeException exception) {
                        if (recordRestoreFailureIfMutationObserved(candidate, block, actualData,
                                actualEntityData, results, exception)) {
                            index++;
                        }
                        throw exception;
                    }
                } else {
                    if (!actualData.equals(desiredData)) {
                        BlockLoggingSuppression.runSuppressed(() -> block.setBlockData(candidate.desired(), false));
                    }
                    try {
                        BlockLoggingSuppression.runSuppressed(
                                () -> BlockEntitySnapshot.restore(block, candidate.desiredEntityData()));
                    } catch (RuntimeException exception) {
                        if (recordRestoreFailureIfMutationObserved(candidate, block, actualData,
                                actualEntityData, results, exception)) {
                            index++;
                        }
                        throw exception;
                    }
                }
                String appliedData = block.getBlockData().getAsString();
                byte[] appliedEntityData;
                try {
                    appliedEntityData = BlockEntitySnapshot.capture(block);
                } catch (RuntimeException exception) {
                    // The world mutation and entity restore have completed. Confirm the prepared
                    // audit with the observable block state before failing so this change remains
                    // visible and recoverable even when its post-mutation snapshot cannot be read.
                    recordUnknownAppliedEntityState(candidate, block, results);
                    index++;
                    throw exception;
                }
                results.put(candidate.change().sequence(),
                        new RollbackStepResult(candidate.change().sequence(), true, false,
                                appliedData, appliedEntityData));
                index++;
            }
        } catch (RuntimeException exception) {
            failure = exception;
        } finally {
            rollbackTickBudget.end(System.nanoTime());
        }

        if (failure != null) {
            for (int remaining = index; remaining < candidates.size(); remaining++) {
                staleAuditIds.add(auditIds.get(remaining));
            }
            RuntimeException finalFailure = failure;
            deleteRequiredAudits(job, operator, staleAuditIds, results, undo, () ->
                    persistObservedCorrections(job, operator, observedCorrections,
                            () -> persistCompletedResultsBeforeFailure(
                                    job, operator, results, undo, finalFailure)));
            return;
        }

        deleteRequiredAudits(job, operator, staleAuditIds, results, undo, () ->
                persistObservedCorrections(job, operator, observedCorrections, () -> {
                    if (forceRetries.isEmpty()) {
                        afterApplied.run();
                        return;
                    }
                    if (forceAttempt >= MAX_FORCE_REVALIDATION_RETRIES) {
                        persistCompletedResultsBeforeFailure(job, operator, results, false,
                                new IllegalStateException(
                                        "Could not obtain a stable live block state after "
                                                + MAX_FORCE_REVALIDATION_RETRIES
                                                + " force revalidation attempts."));
                        return;
                    }
                    retryForcedChanges(job, operator, forceRetries, results, observedCorrections,
                            forceAttempt + 1, afterApplied);
                }));
    }

    private boolean recordRestoreFailureIfMutationObserved(PreparedWorldChange candidate, Block block,
                                                            String beforeData, byte[] beforeEntityData,
                                                            Map<Integer, RollbackStepResult> results,
                                                            RuntimeException restoreFailure) {
        String liveData = block.getBlockData().getAsString();
        if (!liveData.equals(beforeData)) {
            recordUnknownAppliedEntityState(candidate, block, results);
            return true;
        }

        byte[] liveEntityData;
        try {
            liveEntityData = BlockEntitySnapshot.capture(block);
        } catch (RuntimeException captureFailure) {
            restoreFailure.addSuppressed(captureFailure);
            return false;
        }
        if (!Arrays.equals(liveEntityData, beforeEntityData)) {
            recordUnknownAppliedEntityState(candidate, block, results);
            return true;
        }
        return false;
    }

    private void recordUnknownAppliedEntityState(PreparedWorldChange candidate, Block block,
                                                 Map<Integer, RollbackStepResult> results) {
        results.put(candidate.change().sequence(),
                new RollbackStepResult(candidate.change().sequence(), true, false,
                        block.getBlockData().getAsString(), UNKNOWN_ENTITY_STATE.clone()));
    }

    private void persistCompletedResultsBeforeFailure(RollbackJob job, Player operator,
                                                      Map<Integer, RollbackStepResult> results,
                                                      boolean undo, Throwable failure) {
        List<RollbackStepResult> completedResults = List.copyOf(results.values());
        if (completedResults.isEmpty()) {
            failJob(job, operator, failure);
            return;
        }

        CompletableFuture<Void> persisted = undo
                ? database.markUndoBatchAppliedAsync(job.id(), completedResults)
                : database.markRollbackBatchAppliedAsync(job.id(), completedResults);
        persisted.whenComplete((ignored, throwable) -> onServerThread(() -> {
            if (throwable != null) {
                Throwable persistenceFailure = unwrap(throwable);
                if (persistenceFailure instanceof IllegalStateException
                        && OPERATION_QUEUE_FULL.equals(persistenceFailure.getMessage())) {
                    Bukkit.getScheduler().runTaskLater(plugin,
                            () -> persistCompletedResultsBeforeFailure(
                                    job, operator, results, undo, failure), 1L);
                    return;
                }
                persistenceFailure.addSuppressed(failure);
                failJob(job, operator, persistenceFailure);
                return;
            }
            failJob(job, operator, failure);
        }));
    }

    private void persistObservedCorrections(RollbackJob job, Player operator,
                                            List<BlockChange> observedCorrections,
                                            Runnable afterPersisted) {
        if (observedCorrections.isEmpty()) {
            afterPersisted.run();
            return;
        }

        List<BlockChange> sliceCorrections = List.copyOf(observedCorrections);
        persistRequiredAudits(job, operator, sliceCorrections, ignored -> {
            observedCorrections.subList(0, sliceCorrections.size()).clear();
            afterPersisted.run();
        });
    }

    private void retryForcedChanges(RollbackJob job, Player operator,
                                    List<RollbackJobChange> changes,
                                    Map<Integer, RollbackStepResult> results,
                                    List<BlockChange> observedCorrections,
                                    int forceAttempt, Runnable afterApplied) {
        retryForcedChangesSlice(job, operator, changes, results, observedCorrections,
                forceAttempt, afterApplied, 0, new ArrayList<>());
    }

    private void retryForcedChangesSlice(RollbackJob job, Player operator,
                                         List<RollbackJobChange> changes,
                                         Map<Integer, RollbackStepResult> results,
                                         List<BlockChange> observedCorrections,
                                         int forceAttempt, Runnable afterApplied,
                                         int startIndex, List<PreparedWorldChange> retryCandidates) {
        Runnable retry = () -> retryForcedChangesSlice(job, operator, changes, results,
                observedCorrections, forceAttempt, afterApplied, startIndex, retryCandidates);
        if (!beginWorkSlice(job, operator, false, retry)) {
            return;
        }

        int index = startIndex;
        RuntimeException failure = null;
        try {
            while (index < changes.size()) {
                if (index > startIndex && rollbackTickBudget.exhausted(System.nanoTime())) {
                    break;
                }
                RollbackJobChange queued = changes.get(index);
                World world = resolveChunkWorld(RollbackChunkPlan.ChunkKey.from(queued));
                if (world == null) {
                    throw new IllegalStateException("World '" + queued.worldName()
                            + "' is not loaded or its saved UUID no longer matches.");
                }
                RollbackJobChange change = queued.withWorldName(world.getName());
                BlockData desired = Bukkit.createBlockData(
                        Objects.requireNonNull(change.targetData(), "Missing saved block state"));
                Block block = world.getBlockAt(change.x(), change.y(), change.z());
                String actualData = block.getBlockData().getAsString();
                byte[] actualEntityData = BlockEntitySnapshot.capture(block);
                if (matchesState(actualData, actualEntityData,
                        desired.getAsString(), change.targetEntityData())) {
                    // The stale force attempt never mutated this coordinate; another actor completed it.
                    results.put(change.sequence(), new RollbackStepResult(change.sequence(), false, true));
                } else {
                    retryCandidates.add(new PreparedWorldChange(change, block, desired, actualData,
                            actualEntityData, change.targetEntityData()));
                }
                index++;
            }
        } catch (RuntimeException exception) {
            failure = exception;
        } finally {
            rollbackTickBudget.end(System.nanoTime());
        }
        if (failure != null) {
            persistCompletedResultsBeforeFailure(job, operator, results, false, failure);
            return;
        }

        if (index < changes.size()) {
            int nextIndex = index;
            retryJobLater(job, () -> retryForcedChangesSlice(job, operator, changes, results,
                    observedCorrections, forceAttempt, afterApplied, nextIndex, retryCandidates), 1L);
            return;
        }

        persistAndApplyCandidates(job, operator, retryCandidates, results, observedCorrections,
                false, true, forceAttempt, afterApplied);
    }

    private void persistRequiredAudits(RollbackJob job, Player operator, List<BlockChange> audits,
                                       Consumer<List<Long>> afterPersisted) {
        if (audits.isEmpty()) {
            afterPersisted.accept(List.of());
            return;
        }

        database.insertRequiredAsync(audits).whenComplete((ids, throwable) -> onServerThread(() -> {
            if (throwable == null) {
                afterPersisted.accept(ids);
                return;
            }

            Throwable cause = unwrap(throwable);
            if (cause instanceof IllegalStateException && OPERATION_QUEUE_FULL.equals(cause.getMessage())) {
                Bukkit.getScheduler().runTaskLater(plugin,
                        () -> persistRequiredAudits(job, operator, audits, afterPersisted), 1L);
                return;
            }
            failJob(job, operator, cause);
        }));
    }

    private void persistPendingAudits(RollbackJob job, Player operator, boolean undo,
                                      List<RollbackPendingAudit> audits,
                                      Map<Integer, RollbackStepResult> results,
                                      Consumer<List<Long>> afterPersisted) {
        if (audits.isEmpty()) {
            afterPersisted.accept(List.of());
            return;
        }

        database.insertPendingRollbackAuditsAsync(job.id(), undo, audits)
                .whenComplete((ids, throwable) -> onServerThread(() -> {
                    if (throwable == null) {
                        afterPersisted.accept(ids);
                        return;
                    }

                    Throwable cause = unwrap(throwable);
                    if (cause instanceof IllegalStateException
                            && OPERATION_QUEUE_FULL.equals(cause.getMessage())) {
                        Bukkit.getScheduler().runTaskLater(plugin,
                                () -> persistPendingAudits(job, operator, undo, audits, results, afterPersisted), 1L);
                        return;
                    }
                    persistCompletedResultsBeforeFailure(job, operator, results, undo, cause);
                }));
    }

    private void deleteRequiredAudits(RollbackJob job, Player operator, List<Long> auditIds,
                                      Map<Integer, RollbackStepResult> results, boolean undo,
                                      Runnable afterDeleted) {
        if (auditIds.isEmpty()) {
            afterDeleted.run();
            return;
        }
        database.deleteRequiredAsync(auditIds).whenComplete((ignored, throwable) -> onServerThread(() -> {
            if (throwable == null) {
                afterDeleted.run();
                return;
            }
            Throwable cause = unwrap(throwable);
            if (cause instanceof IllegalStateException && OPERATION_QUEUE_FULL.equals(cause.getMessage())) {
                Bukkit.getScheduler().runTaskLater(plugin,
                        () -> deleteRequiredAudits(job, operator, auditIds, results, undo, afterDeleted), 1L);
                return;
            }
            persistCompletedResultsBeforeFailure(job, operator, results, undo, cause);
        }));
    }

    private void persistBatchResults(RollbackJob job, Player operator, List<RollbackJobChange> changes,
                                     List<RollbackStepResult> results, int nextIndex, boolean undo,
                                     int previousProgress) {
        CompletableFuture<Void> persisted = undo
                ? database.markUndoBatchAppliedAsync(job.id(), results)
                : database.markRollbackBatchAppliedAsync(job.id(), results);
        persisted.whenComplete((ignored, throwable) -> onServerThread(() -> {
            if (throwable != null) {
                Throwable cause = unwrap(throwable);
                if (cause instanceof IllegalStateException && OPERATION_QUEUE_FULL.equals(cause.getMessage())) {
                    Bukkit.getScheduler().runTaskLater(plugin,
                            () -> persistBatchResults(job, operator, changes, results,
                                    nextIndex, undo, previousProgress), 1L);
                    return;
                }
                Map<Integer, RollbackStepResult> completedResults = new HashMap<>();
                for (RollbackStepResult result : results) {
                    completedResults.put(result.sequence(), result);
                }
                persistCompletedResultsBeforeFailure(job, operator, completedResults, undo, cause);
                return;
            }
            int progress = (int) ((nextIndex * 100L) / Math.max(1, changes.size()));
            int milestone = progress / 10;
            if (milestone > previousProgress && progress < 100) {
                message(operator, "&7" + (undo ? "Undo" : "Rollback") + " job #" + job.id()
                        + ": &f" + progress + "% &7(" + nextIndex + "/" + changes.size() + ")");
            }
            int recordedProgress = Math.max(previousProgress, milestone);
            Bukkit.getScheduler().runTaskLater(plugin,
                    () -> runJobBatch(job, operator, changes, nextIndex, undo, recordedProgress), 1L);
        }));
    }

    private void failJob(RollbackJob job, Player operator, Throwable throwable) {
        executingJobs.remove(job.id());
        pausedJobs.remove(job.id());
        releaseJobChunk(job.id());
        Throwable cause = unwrap(throwable);
        String reason = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
        plugin.getLogger().log(Level.SEVERE, "FragGuard rollback job #" + job.id() + " failed", cause);
        message(operator, "&cRollback job #" + job.id() + " failed: " + reason
                + " &7Saved changes can be reversed with &f/fg undo " + job.id());
        persistFailedJobState(job, reason);
    }

    private void persistFailedJobState(RollbackJob job, String reason) {
        database.failRollbackJobAsync(job.id(), reason).whenComplete((ignored, throwable) -> {
            if (throwable == null) {
                return;
            }
            Throwable failure = unwrap(throwable);
            if (failure instanceof IllegalStateException && OPERATION_QUEUE_FULL.equals(failure.getMessage())) {
                Bukkit.getScheduler().runTaskLater(plugin,
                        () -> persistFailedJobState(job, reason), 1L);
                return;
            }
            plugin.getLogger().log(Level.SEVERE,
                    "Could not persist failure of rollback job #" + job.id(), failure);
        });
    }

    private void reportJobError(Player player, String prefix, Throwable throwable) {
        Throwable cause = unwrap(throwable);
        if (cause instanceof IllegalStateException) {
            player.sendMessage(color("&c" + prefix + ": " + cause.getMessage()));
            return;
        }
        plugin.getLogger().log(Level.WARNING, prefix, cause);
        player.sendMessage(color("&c" + prefix + ". Check console for details."));
    }

    private void reportQueryProgress(Player player, CompletableFuture<?> query,
                                     UUID actor, UUID queryId) {
        new BukkitRunnable() {
            private int elapsedSeconds;

            @Override
            public void run() {
                if (query.isDone() || !player.isOnline()
                        || !queryId.equals(pendingRollbackQueries.get(actor))) {
                    cancel();
                    return;
                }
                elapsedSeconds += 2;
                DatabaseHealth health = database.health();
                player.sendMessage(color("&7Rollback preview is still searching (" + elapsedSeconds
                        + "s; queued writes: " + health.queuedWrites() + ")."));
            }
        }.runTaskTimer(plugin, 40L, 40L);
    }

    private void sendRollbackJobStatus(Player player, String rawJobId) {
        long id;
        try {
            id = Long.parseLong(rawJobId);
            if (id <= 0) {
                throw new NumberFormatException("Invalid job number");
            }
        } catch (NumberFormatException ignored) {
            player.sendMessage(color("&cUsage: /fg status <job-id>"));
            return;
        }
        database.loadRollbackJobAsync(id).whenComplete((job, failure) -> onServerThread(() -> {
            if (failure != null) {
                reportJobError(player, "Could not read rollback job", failure);
                return;
            }
            player.sendMessage(color("&8&m------&r &eFragGuard Rollback #" + job.id() + " &8&m------"));
            player.sendMessage(color("&7Type: &f" + (job.isPlayerScope() ? "Player" : "Radius")
                    + " &7| Status: &f" + job.status()));
            player.sendMessage(color("&7Operator: &f" + job.actorName()));
            if (job.isPlayerScope()) {
                player.sendMessage(color("&7Target player: &f" + job.targetPlayerName()
                        + " &8(" + job.targetPlayerUuid() + ")"));
                player.sendMessage(color("&7World scope: &f"
                        + (job.worldScopeName() == null ? "All worlds" : job.worldScopeName())
                        + " &7| Worlds affected: &f" + job.affectedWorlds()));
                player.sendMessage(color("&7Chunks: &f" + job.affectedChunks()
                        + " &7| Historical conflicts protected: &e" + job.historicalConflicts()));
            } else {
                player.sendMessage(color("&7World: &f" + job.worldName()
                        + " &7| Radius: &f" + job.radius()));
            }
            player.sendMessage(color("&7Time since: &f"
                    + DATE_FORMAT.format(Instant.ofEpochMilli(job.targetTimestamp()))));
            player.sendMessage(color("&7Processed: &f" + job.processedBlocks() + "/" + job.totalBlocks()
                    + " &7| Applied: &f" + job.appliedBlocks()
                    + " &7| Live conflicts: &f" + job.conflictBlocks()));
            if (job.lastError() != null && !job.lastError().isBlank()) {
                player.sendMessage(color("&cLast error: " + job.lastError()));
            }
        }));
    }

    private void handleDiagnostics(Player player, String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("preflight")) {
            if (args.length == 1) {
                handleCapacityPreflight(player, false);
            } else if (args.length == 2 && args[1].equalsIgnoreCase("acknowledge")) {
                handleCapacityPreflight(player, true);
            } else if (args.length == 2 && args[1].equalsIgnoreCase("details")) {
                CompactionStoragePreflight.Report viewed =
                        capacityDisplayReports.get(player.getUniqueId());
                if (viewed == null) {
                    player.sendMessage(color("&eNo preflight report to expand. "
                            + "Run &f/fg diagnostics preflight&e first."));
                } else {
                    MaintenanceChatOutput.preflightDetails(player::sendMessage, viewed);
                }
            } else {
                player.sendMessage(color("&cUsage: &f/fg diagnostics preflight [acknowledge|details]"));
            }
            return;
        }
        if (args.length == 1 && (args[0].equalsIgnoreCase("deep")
                || args[0].equalsIgnoreCase("progress") || args[0].equalsIgnoreCase("cancel"))) {
            handleDeepDiagnostics(player, args[0].toLowerCase(Locale.ROOT));
            return;
        }
        DatabaseQuickDiagnostics.Mode mode;
        if (args.length == 0 || (args.length == 1 && args[0].equalsIgnoreCase("overview"))) {
            mode = DatabaseQuickDiagnostics.Mode.OVERVIEW;
        } else if (args.length == 1 && args[0].equalsIgnoreCase("counts")) {
            mode = DatabaseQuickDiagnostics.Mode.COUNTS;
        } else if (args.length == 1 && args[0].equalsIgnoreCase("check")) {
            mode = DatabaseQuickDiagnostics.Mode.QUICK_CHECK;
        } else {
            player.sendMessage(color("&cUsage: &f/fg diagnostics [overview|counts|check|deep|progress|cancel|preflight]"));
            return;
        }

        DatabaseQuickDiagnostics diagnostics = plugin.getQuickDiagnostics();
        if (diagnostics == null) {
            player.sendMessage(color("&cFragGuard diagnostics are not available."));
            return;
        }
        UUID actor = player.getUniqueId();
        CompletableFuture<DatabaseQuickDiagnostics.Report> prior = diagnosticRequests.remove(actor);
        if (prior != null) prior.cancel(true);
        int seconds = Math.max(1, plugin.getConfig().getInt(
                mode == DatabaseQuickDiagnostics.Mode.QUICK_CHECK
                        ? "diagnostics-quick-check-timeout-seconds"
                        : "diagnostics-query-timeout-seconds",
                mode == DatabaseQuickDiagnostics.Mode.QUICK_CHECK ? 45 : 15));
        player.sendMessage(color(mode == DatabaseQuickDiagnostics.Mode.OVERVIEW
                ? "&7Reading database overview (read-only)..."
                : "&7Running read-only database diagnostics. This may take a while on large history databases..."));
        CompletableFuture<DatabaseQuickDiagnostics.Report> request = diagnostics.run(
                mode, database.health(), plugin.getRetentionDays(),
                plugin.getRollbackJobRetentionDays(), seconds);
        diagnosticRequests.put(actor, request);
        request.whenComplete((report, failure) -> onServerThread(() -> {
            if (diagnosticRequests.get(actor) != request) return;
            diagnosticRequests.remove(actor);
            if (!player.isOnline() || !player.isOp() || !player.hasPermission("fragguard.admin")) return;
            if (failure != null) {
                Throwable cause = unwrap(failure);
                player.sendMessage(Component.text("FragGuard diagnostics failed: "
                        + (cause.getMessage() == null ? cause.getClass().getSimpleName()
                        : cause.getMessage()), NamedTextColor.RED));
                return;
            }
            ReclaimableSpaceMonitor monitor = plugin.getReclaimableSpaceMonitor();
            if (monitor != null) monitor.accept(report, System.currentTimeMillis(), false);
            // Keep admin-only paths and warnings, but distinguish sections,
            // values and verified results from explanatory text.
            MaintenanceChatOutput.diagnostics(player::sendMessage, report);
        }));
    }

    private void handleCapacityPreflight(Player player, boolean acknowledgeUnknown) {
        CompactionStoragePreflight preflight = plugin.getCompactionPreflight();
        if (preflight == null) {
            player.sendMessage(color("&cFragGuard compaction preflight is unavailable."));
            return;
        }
        UUID operator = player.getUniqueId();
        if (capacityRequests.containsKey(operator)) {
            player.sendMessage(color("&eYour storage preflight is already running."));
            return;
        }
        if (acknowledgeUnknown) {
            CompactionStoragePreflight.Report last = capacityLastReports.get(operator);
            if (last == null
                    || last.decision() != CompactionStoragePreflight.Decision.ACKNOWLEDGEMENT_REQUIRED
                    || java.time.Duration.between(last.snapshot().measuredAt(), Instant.now()).toSeconds() > 120) {
                player.sendMessage(color("&cRun &f/fg diagnostics preflight&c first, review its "
                        + "unknown capacity warnings, then explicitly acknowledge within 2 minutes."));
                return;
            }
        } else {
            // Each new inspection invalidates older acknowledgement context.
            capacityLastReports.remove(operator);
            capacityDisplayReports.remove(operator);
        }
        long accountMiB = plugin.getConfig().getLong("maintenance-compaction-account-free-mib", -1L);
        long nativeTempMiB = plugin.getConfig().getLong("maintenance-compaction-sqlite-temp-free-mib", -1L);
        long safetyMiB = plugin.getConfig().getLong("maintenance-compaction-safety-mib", 256L);
        if (accountMiB < -1L || nativeTempMiB < -1L || safetyMiB < 0L) {
            player.sendMessage(color("&cInvalid compaction storage config: use -1 for unknown "
                    + "capacity, 0 for known zero, and nonnegative MiB for confirmed free space."));
            return;
        }
        CompactionStoragePreflight.Evidence evidence =
                new CompactionStoragePreflight.Evidence(
                        accountMiB == -1L ? null : MigrationStoragePolicy.mibToBytes(accountMiB),
                        nativeTempMiB == -1L ? null : MigrationStoragePolicy.mibToBytes(nativeTempMiB),
                        safetyMiB, acknowledgeUnknown);
        player.sendMessage(color("&7Checking compaction disk/quota/temp headroom on a read-only "
                + "SQLite connection..."));
        CompletableFuture<CompactionStoragePreflight.Report> request = preflight.run(evidence);
        if (request.isCompletedExceptionally()) {
            player.sendMessage(color("&cAnother storage preflight is already running, "
                    + "or the preflight worker has stopped."));
            return;
        }
        capacityRequests.put(operator, request);
        request.whenComplete((report, failure) -> onServerThread(() -> {
            if (capacityRequests.get(operator) != request) return;
            capacityRequests.remove(operator);
            if (!player.isOnline() || !player.isOp()
                    || !player.hasPermission("fragguard.admin")) return;
            if (failure != null) {
                plugin.getLogger().log(Level.WARNING, "FragGuard compaction preflight failed", unwrap(failure));
                player.sendMessage(color("&cStorage preflight failed. No files were changed; "
                        + "check console for details."));
                return;
            }
            // Keep the operator's explicitly inspected, successful preflight
            // only for the short handoff into a maintenance compaction. The
            // worker separately enforces a fresh (<30s) reservation check.
            if (report.decision() == CompactionStoragePreflight.Decision.ACKNOWLEDGEMENT_REQUIRED
                    || report.mayReserve()) {
                capacityLastReports.put(operator, report);
            } else {
                capacityLastReports.remove(operator);
            }
            capacityDisplayReports.put(operator, report);
            MaintenanceChatOutput.preflightSummary(player::sendMessage, report);
            if (report.decision() == CompactionStoragePreflight.Decision.ACKNOWLEDGEMENT_REQUIRED) {
                player.sendMessage(color("&eKnown capacity checks passed, but quota/temp evidence "
                        + "is unknown. To explicitly accept those unknowns, use "
                        + "&f/fg diagnostics preflight acknowledge&e within 2 minutes."));
            } else if (report.mayReserve()) {
                player.sendMessage(color("&aStorage estimate accepted. This does not start "
                        + "compaction or guarantee SQLite won't run out of space."));
            } else {
                player.sendMessage(color("&cCompaction capacity preflight refused. "
                        + "Fix the reported shortages/unsafe candidates before proceeding."));
            }
        }));
    }

    private static final UUID CONSOLE_MAINTENANCE_OPERATOR =
            UUID.fromString("6da19f8f-e679-4bb1-8e54-9917dcf7c51f");

    private volatile OnlineDatabaseCompaction.Result latestCompaction;
    private final java.util.concurrent.atomic.AtomicBoolean maintenanceTransitionPending =
            new java.util.concurrent.atomic.AtomicBoolean();

    private void handleMaintenance(Player player, String[] args) {
        MaintenanceCoordinator coordinator = plugin.getMaintenanceCoordinator();
        OnlineDatabaseCompaction compaction = plugin.getOnlineCompaction();
        if (coordinator == null || compaction == null) {
            player.sendMessage(color("&cFragGuard maintenance controls are unavailable."));
            return;
        }
        if (args.length == 0 || (args.length == 1 && args[0].equalsIgnoreCase("menu"))) {
            FragGuardGui gui = plugin.getFragGuardGui();
            if (gui != null) gui.openMaintenance(player);
            else player.sendMessage(color("&cMaintenance menu is unavailable."));
            return;
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        MaintenanceSnapshot state = coordinator.snapshot();
        switch (action) {
            case "status" -> {
                if (args.length > 2 || (args.length == 2 && !args[1].equalsIgnoreCase("full"))) {
                    maintenanceUsage(player);
                    return;
                }
                if (args.length == 2) {
                    showMaintenanceStatus(player, state, compaction.progress());
                } else {
                    MaintenanceChatOutput.statusSummary(player::sendMessage, state);
                }
                AutomaticMaintenanceWorkflow automatic = plugin.getAutomaticMaintenance();
                if (automatic != null && automatic.controlsOperation(state.operationId())) {
                    MaintenanceChatOutput.value(player::sendMessage,
                            "Automatic workflow", automatic.status());
                }
                if (database.maintenanceWritesPaused()) {
                    MaintenanceChatOutput.danger(player::sendMessage,
                            "BLOCK LOGGING PAUSED: world edits now are NOT recorded or rollbackable.");
                    MaintenanceChatOutput.value(player::sendMessage, "Intentionally unlogged",
                            Long.toString(database.intentionallyUnloggedDuringMaintenance()));
                    if (database.loggingPausedAtMillis() > 0L) {
                        MaintenanceChatOutput.value(player::sendMessage, "Paused since",
                                java.time.Instant.ofEpochMilli(database.loggingPausedAtMillis()).toString());
                    }
                    if (!database.maintenanceFullyFrozen()) {
                        MaintenanceChatOutput.notice(player::sendMessage,
                                "Database barrier not complete. Do not compact.");
                    }
                }
            }
            case "help" -> maintenanceUsage(player);
            case "preview" -> {
                if (args.length != 1) { maintenanceUsage(player); return; }
                handleCapacityPreflight(player, false);
            }
            case "start" -> {
                // The normal STRICT path is now one action. Snapshot remains
                // an advanced separately consented MANUAL workflow.
                boolean autoStrict = args.length == 1
                        || args.length == 2 && (args[1].equalsIgnoreCase("strict")
                            || args[1].equalsIgnoreCase("acknowledge"))
                        || args.length == 3 && args[1].equalsIgnoreCase("strict")
                            && args[2].equalsIgnoreCase("acknowledge");
                if (autoStrict) {
                    if (!plugin.maintenanceControlsReady()) {
                        player.sendMessage(color("&cMaintenance protections are unavailable."));
                        return;
                    }
                    boolean consent = args[args.length - 1].equalsIgnoreCase("acknowledge");
                    requestAutomaticMaintenance(player.getUniqueId(),
                            message -> {
                                if (player.isOnline() && player.isOp()
                                        && player.hasPermission("fragguard.admin")) {
                                    player.sendMessage(Component.text(message,
                                            NamedTextColor.YELLOW));
                                }
                            }, consent);
                    return;
                }
                if (args.length > 3) { maintenanceUsage(player); return; }
                String mode = args.length >= 2 ? args[1] : "strict";
                MaintenancePolicy policy;
                try {
                    policy = MaintenanceUiActions.requestedPolicy(mode);
                } catch (IllegalArgumentException incorrect) {
                    maintenanceUsage(player);
                    return;
                }
                if (policy == MaintenancePolicy.MAINTENANCE_SNAPSHOT
                        && (args.length != 3 || !args[2].equalsIgnoreCase("acknowledge"))) {
                    player.sendMessage(color("&cSnapshot mode can discard saved history, rollback/undo "
                            + "records and retention changes after SQLite's actual copy. "
                            + "Worlds do NOT roll back. To opt in explicitly, run "
                            + "&f/fg maintenance start snapshot acknowledge&c."));
                    return;
                }
                if (policy == MaintenancePolicy.STRICT
                        && args.length == 3) { maintenanceUsage(player); return; }
                if (!MaintenanceUiActions.canSchedule(state)) {
                    player.sendMessage(color("&cExisting maintenance must finish or be safely cancelled first."));
                    return;
                }
                if (!plugin.maintenanceControlsReady()) {
                    player.sendMessage(color("&cMaintenance admission/countdown protection is not running."));
                    return;
                }
                long now = System.currentTimeMillis();
                long seconds = MaintenanceUiActions.countdownSeconds(
                        plugin.getConfig().getInt("maintenance-countdown-seconds", 600));
                if (now > Long.MAX_VALUE - seconds * 1_000L) {
                    player.sendMessage(color("&cSystem clock is outside the scheduling range."));
                    return;
                }
                if (!maintenanceTransitionPending.compareAndSet(false, true)) {
                    player.sendMessage(color("&eA maintenance state change is already being saved."));
                    return;
                }
                coordinator.schedule(player.getUniqueId(), now, now + seconds * 1_000L, policy)
                        .whenComplete((saved, error) -> onServerThread(() -> {
                            maintenanceTransitionPending.set(false);
                            if (error != null || saved == null) {
                                player.sendMessage(color("&cCould not schedule maintenance. No countdown started."));
                                plugin.getLogger().log(Level.WARNING,
                                        "Unable to persist maintenance countdown", error);
                                return;
                            }
                            MaintenanceChatOutput.title(player::sendMessage, "Maintenance Scheduled");
                            MaintenanceChatOutput.value(player::sendMessage, "Policy", policy.name(),
                                    policy == MaintenancePolicy.STRICT
                                            ? NamedTextColor.GREEN : NamedTextColor.RED);
                            MaintenanceChatOutput.value(player::sendMessage,
                                    "Countdown", seconds + " seconds");
                            MaintenanceChatOutput.notice(player::sendMessage,
                                    "Yellow Boss Bar and warnings shown to players; login closes at expiry.");
                            if (policy == MaintenancePolicy.MAINTENANCE_SNAPSHOT) {
                                MaintenanceChatOutput.danger(player::sendMessage,
                                        "Snapshot mode is NOT approved. Independent explicit data-loss"
                                        + " consent is needed after verification.");
                            }
                        }));
            }
            case "cancel" -> {
                AutomaticMaintenanceWorkflow automatic = plugin.getAutomaticMaintenance();
                if (automatic != null && (state.phase() == MaintenancePhase.IDLE
                        || state.phase() == MaintenancePhase.CANCELLED)
                        && automatic.cancelPreparation(player.getUniqueId())) {
                    player.sendMessage(color("&aPre-countdown storage check cancelled. "
                            + "No history freeze or SQLite replacement started."));
                    return;
                }
                if (automatic != null && automatic.countdownScheduling(player.getUniqueId())) {
                    player.sendMessage(color("&eThe countdown is being saved. "
                            + "Retry /fg maintenance cancel when status shows COUNTDOWN; "
                            + "no cancellation has been confirmed yet."));
                    return;
                }
                cancelMaintenance(player, state, coordinator, compaction);
            }
            case "compact" -> {
                if (args.length != 1) { maintenanceUsage(player); return; }
                AutomaticMaintenanceWorkflow automatic = plugin.getAutomaticMaintenance();
                if (automatic != null && automatic.controlsOperation(state.operationId())) {
                    player.sendMessage(color("&eAutomatic STRICT compaction is already handled; "
                            + "use /fg maintenance status."));
                    return;
                }
                startMaintenanceCompaction(player, state, compaction);
            }
            case "verify" -> {
                if (args.length != 1) { maintenanceUsage(player); return; }
                AutomaticMaintenanceWorkflow automatic = plugin.getAutomaticMaintenance();
                if (automatic != null && automatic.controlsOperation(state.operationId())) {
                    player.sendMessage(color("&eFull verification is running automatically; "
                            + "use /fg maintenance status."));
                    return;
                }
                if (!MaintenanceUiActions.canVerify(state)) {
                    player.sendMessage(color("&cA compaction candidate in VERIFYING without "
                            + "a verified manifest is required."));
                    return;
                }
                OnlineDatabaseCompaction.Result result = latestCompaction;
                if (result == null || !state.candidateFile().equals(
                        result.candidate().getFileName().toString())) {
                    player.sendMessage(color("&cNo candidate from the current running server "
                            + "is available to verify. Do not reconstruct a candidate or "
                            + "bypass snapshot evidence after a restart."));
                    return;
                }
                CompactionCandidateVerifier verifier = plugin.getCandidateVerifier();
                if (verifier == null) {
                    player.sendMessage(color("&cDatabase candidate verifier is unavailable."));
                    return;
                }
                CompletableFuture<CompactionCandidateVerifier.Result> task = verifier.verify(result);
                if (task.isCompletedExceptionally()) {
                    player.sendMessage(color("&cVerification is already running or unavailable."));
                    return;
                }
                MaintenanceChatOutput.title(player::sendMessage, "Candidate Verification");
                MaintenanceChatOutput.notice(player::sendMessage,
                        "Checking SQLite integrity, history and undo in background.");
                MaintenanceChatOutput.notice(player::sendMessage,
                        "Read-only: source files will NOT be replaced.");
                task.whenComplete((verified, error) -> onServerThread(() -> {
                    if (error != null) {
                        plugin.getLogger().log(Level.WARNING,
                                "FragGuard candidate verification failed", unwrap(error));
                        if (player.isOnline()) {
                            MaintenanceChatOutput.danger(player::sendMessage,
                                    "Verification FAILED: replacement blocked; original preserved.");
                            MaintenanceChatOutput.notice(player::sendMessage,
                                    "Review console and /fg maintenance status full.");
                        }
                    } else if (player.isOnline()) {
                        MaintenanceChatOutput.title(player::sendMessage, "Candidate Verified");
                        MaintenanceChatOutput.value(player::sendMessage,
                                "Candidate", verified.candidate().getFileName().toString(),
                                NamedTextColor.GREEN);
                        MaintenanceChatOutput.action(player::sendMessage,
                                "Next: /fg maintenance approve (policy approval still required)");
                    }
                }));
            }
            case "approve" -> {
                AutomaticMaintenanceWorkflow automatic = plugin.getAutomaticMaintenance();
                if (automatic != null && automatic.controlsOperation(state.operationId())) {
                    player.sendMessage(color("&eSTRICT approval is automatic; "
                            + "use /fg maintenance status."));
                    return;
                }
                approveMaintenancePolicy(player, args, state);
            }
            case "instructions" -> {
                if (args.length > 2 || (args.length == 2 && !args[1].equalsIgnoreCase("full"))) {
                    maintenanceUsage(player);
                    return;
                }
                boolean strictPending = state.phase() == MaintenancePhase.VERIFYING
                        && state.policy() == MaintenancePolicy.STRICT
                        && !state.manifestFile().isBlank();
                if (state.phase() != MaintenancePhase.READY_TO_SWAP && !strictPending) {
                    player.sendMessage(color("&cThe candidate is not verified and approved. "
                            + "Do not rename any SQLite files."));
                    return;
                }
                try {
                    MaintenanceChatOutput.instructions(player::sendMessage, state,
                            AutomaticCompactionActivation.operatorInstructions(state),
                            args.length == 2, strictPending);
                } catch (RuntimeException problem) {
                    player.sendMessage(color("&cCould not resolve safe candidate filenames."));
                }
            }
            default -> maintenanceUsage(player);
        }
    }

    private void requestAutomaticMaintenance(UUID operator, java.util.function.Consumer<String> notify,
                                             boolean acknowledge) {
        AutomaticMaintenanceWorkflow automatic = plugin.getAutomaticMaintenance();
        if (automatic == null || !plugin.maintenanceControlsReady()) {
            notify.accept("Automatic maintenance is unavailable or the admission gate is offline.");
            return;
        }
        automatic.start(operator, notify, acknowledge)
                .whenComplete((result, error) -> onServerThread(() -> {
                    if (error != null || result == null) {
                        notify.accept("Could not safely initiate STRICT maintenance; "
                                + "no unverified SQLite replacement is allowed.");
                        plugin.getLogger().log(Level.WARNING,
                                "Automatic maintenance could not start", error);
                        return;
                    }
                    notify.accept(result.detail());
                    if (result.type() == AutomaticMaintenanceWorkflow.ResultType.NEEDS_CAPACITY_ACK) {
                        notify.accept("Only one explicit acknowledgment is needed: "
                                + "/fg maintenance start acknowledge");
                    }
                }));
    }

    private void maintenanceUsage(Player player) {
        player.sendMessage(color("&bMaintenance commands:"));
        player.sendMessage(color("&f/fg maintenance &7- Open in-game database maintenance menu."));
        player.sendMessage(color("&f/fg maintenance start [strict] &7- Automatically complete SAFE STRICT maintenance."));
        player.sendMessage(color("&f/fg maintenance start acknowledge &7- One-time unknown-space consent before countdown."));
        player.sendMessage(color("&f/fg maintenance start snapshot acknowledge &7- Opt in to snapshot scheduling."));
        player.sendMessage(color("&f/fg maintenance status [full] &7- Summary or complete evidence."));
        player.sendMessage(color("&f/fg maintenance preview &7- Read-only storage preflight."));
        player.sendMessage(color("&f/fg maintenance compact &7- Make one SQLite candidate while ACTIVE."));
        player.sendMessage(color("&f/fg maintenance verify &7- Full read-only candidate validation."));
        player.sendMessage(color("&f/fg maintenance approve &7- Prepare STRICT approval at clean shutdown."));
        player.sendMessage(color("&f/fg maintenance approve snapshot-acknowledge &7- Explicitly accept snapshot loss."));
        player.sendMessage(color("&f/fg maintenance instructions [full] &7- Automatic activation and emergency recovery guidance."));
        player.sendMessage(color("&f/fg maintenance cancel &7- Cancel only safe pre-swap stages."));
    }

    private void showMaintenanceStatus(Player player, MaintenanceSnapshot state,
                                       OnlineDatabaseCompaction.Progress copying) {
        MaintenanceChatOutput.title(player::sendMessage, "Maintenance · Full Status");
        MaintenanceChatOutput.section(player::sendMessage, "Stage and policy");
        MaintenanceChatOutput.value(player::sendMessage, "Stage", state.phase().name(),
                state.phase() == MaintenancePhase.FAILED ? NamedTextColor.RED : NamedTextColor.AQUA);
        MaintenanceChatOutput.value(player::sendMessage, "Policy", state.policy().name(),
                state.policy() == MaintenancePolicy.STRICT
                        ? NamedTextColor.GREEN : NamedTextColor.RED);
        MaintenanceChatOutput.value(player::sendMessage, "Operation",
                state.operationId().isBlank() ? "none" : state.operationId());
        if (state.phase() != MaintenancePhase.IDLE
                && state.phase() != MaintenancePhase.COUNTDOWN
                && state.phase() != MaintenancePhase.CANCELLED) {
            try {
                var freeze = FrozenMaintenanceProof.read(
                        plugin.getDataFolder().toPath(), state);
                MaintenanceChatOutput.value(player::sendMessage, "Frozen operation",
                        freeze.operationId());
                MaintenanceChatOutput.value(player::sendMessage, "SQL/history generation",
                        freeze.sqlGeneration() + " / " + freeze.historyGeneration());
                MaintenanceChatOutput.value(player::sendMessage, "Drain evidence",
                        "SQLite sealed, retention drained; source proof "
                                + freeze.sha256().substring(0, 12));
            } catch (java.io.IOException missing) {
                MaintenanceChatOutput.danger(player::sendMessage,
                        "Frozen source proof missing or invalid: do NOT compact or approve.");
            }
        }
        if (state.phase() == MaintenancePhase.COUNTDOWN) {
            long seconds = Math.max(0L,
                    (state.countdownEndsAtMillis() - System.currentTimeMillis() + 999L) / 1000L);
            player.sendMessage(Component.text("Time remaining: " + seconds + " seconds ("
                    + MaintenanceUiActions.countdownPercent(state, System.currentTimeMillis())
                    + "% of countdown remaining)", NamedTextColor.YELLOW));
        }
        if (copying.running()) {
            player.sendMessage(Component.text("Compaction: " + copying.stage()
                    + " | Current size: " + MigrationStoragePolicy.formatBytes(
                        Math.max(0L, copying.candidateBytes()))
                    + " (total is unknown; no estimated percent)",
                    NamedTextColor.GRAY));
        }
        CompactionCandidateVerifier verifier = plugin.getCandidateVerifier();
        if (verifier != null && verifier.progress().running()) {
            var progress = verifier.progress();
            player.sendMessage(Component.text("Verification: " + progress.stage()
                    + " | " + progress.recordsScanned() + " records inspected (total unknown)",
                    NamedTextColor.GRAY));
        }
        if (!state.candidateFile().isBlank()) {
            player.sendMessage(Component.text("Candidate: " + state.candidateFile(),
                    NamedTextColor.GRAY));
            try {
                java.nio.file.Path root = plugin.getDataFolder().toPath();
                java.nio.file.Path original = root.resolve("fragguard.db");
                java.nio.file.Path candidate = root.resolve("compaction-candidates")
                        .resolve(state.candidateFile());
                if (java.nio.file.Files.isRegularFile(original)
                        && java.nio.file.Files.isRegularFile(candidate)) {
                    long before = java.nio.file.Files.size(original);
                    long after = java.nio.file.Files.size(candidate);
                    player.sendMessage(Component.text("Original main DB "
                            + MigrationStoragePolicy.formatBytes(before)
                            + " | Candidate " + MigrationStoragePolicy.formatBytes(after)
                            + " | Difference " + MigrationStoragePolicy.formatBytes(
                                Math.abs(before - after))
                            + (after <= before ? " smaller" : " larger")
                            + " (excludes live WAL/SHM)", NamedTextColor.GRAY));
                }
            } catch (java.io.IOException exception) {
                player.sendMessage(color("&7Size comparison unavailable; files remain unchanged."));
            }
        }
        player.sendMessage(color(state.manifestFile().isBlank()
                ? "&eCandidate: not yet fully verified."
                : "&aCandidate integrity manifest: &f" + state.manifestFile()));
        player.sendMessage(Component.text(state.detail(), NamedTextColor.GRAY));
        if (state.phase() == MaintenancePhase.READY_TO_SWAP) {
            player.sendMessage(color("&eFragGuard will automatically activate the verified DB "
                    + "on the next safe Paper startup. Do not move SQLite files."));
        } else if (state.phase() == MaintenancePhase.FAILED
                || state.phase() == MaintenancePhase.ACTIVATING) {
            player.sendMessage(color("&cKeep source, backup, candidate, WAL/SHM and manifests. "
                    + "Do not delete or mix SQLite files."));
        }
        if (state.policy() == MaintenancePolicy.MAINTENANCE_SNAPSHOT
                && state.phase() != MaintenancePhase.IDLE) {
            player.sendMessage(color("&cSnapshot cutoff is within the actual VACUUM "
                    + "invocation-to-completion window, NOT a precise countdown time. "
                    + "Post-snapshot history/undo/retention may be lost; worlds never roll back."));
        }
    }

    private void cancelMaintenance(Player player, MaintenanceSnapshot state,
                                   MaintenanceCoordinator coordinator,
                                   OnlineDatabaseCompaction compaction) {
        if (!MaintenanceUiActions.canCancel(state)) {
            player.sendMessage(color("&cCancellation is not available at this stage. "
                    + "READY/ACTIVATING requires preserving recovery evidence."));
            return;
        }
        // Workers persist CANCELLED themselves, but until that promise is
        // complete the SQL write gate remains frozen and regular players must
        // not be admitted. This also covers cancelling during VACUUM/verify.
        if (state.phase() == MaintenancePhase.COMPACTING && compaction.progress().running()) {
            if (!maintenanceTransitionPending.compareAndSet(false, true)) {
                player.sendMessage(color("&eA maintenance transition is already pending."));
                return;
            }
            plugin.beginMaintenanceCancellation();
            if (compaction.cancel()) {
                player.sendMessage(color("&eStopping compaction and preserving both databases. "
                        + "Logging will resume after cancellation is durable."));
                compaction.whenStopped().whenComplete((ignored, error) ->
                        onServerThread(() -> completeCancelledMaintenance(player, coordinator)));
            } else {
                maintenanceTransitionPending.set(false);
                plugin.failedMaintenanceCancellation();
                player.sendMessage(color("&cCompaction cancellation could not be confirmed; "
                        + "logging remains paused."));
            }
            return;
        }
        if (state.phase() == MaintenancePhase.VERIFYING) {
            CompactionCandidateVerifier verifier = plugin.getCandidateVerifier();
            if (verifier != null && verifier.progress().running()) {
                if (!maintenanceTransitionPending.compareAndSet(false, true)) {
                    player.sendMessage(color("&eA maintenance transition is already pending."));
                    return;
                }
                plugin.beginMaintenanceCancellation();
                if (verifier.cancel()) {
                    player.sendMessage(color("&eStopping verification; no SQLite files will "
                            + "be replaced. Logging resumes after durable cancellation."));
                    verifier.whenStopped().whenComplete((ignored, error) ->
                            onServerThread(() -> completeCancelledMaintenance(player, coordinator)));
                } else {
                    maintenanceTransitionPending.set(false);
                    plugin.failedMaintenanceCancellation();
                    player.sendMessage(color("&cVerification cancellation could not be "
                            + "confirmed; logging remains paused."));
                }
                return;
            }
        }
        if (state.phase() == MaintenancePhase.COMPACTING) {
            player.sendMessage(color("&cCompaction worker is already stopping or unavailable. "
                    + "Status may change; try again after checking."));
            return;
        }
        CompactionCandidateVerifier runningVerifier = plugin.getCandidateVerifier();
        if (state.phase() == MaintenancePhase.FAILED
                && (compaction.progress().running()
                    || (runningVerifier != null && runningVerifier.progress().running()))) {
            player.sendMessage(color("&cMaintenance worker still stopping. "
                    + "Wait for it to finish before cancelling FAILED state."));
            return;
        }
        if (!maintenanceTransitionPending.compareAndSet(false, true)) {
            player.sendMessage(color("&eA maintenance transition is already pending."));
            return;
        }
        plugin.beginMaintenanceCancellation();
        coordinator.cancel("Cancelled explicitly by administrator "
                + player.getUniqueId() + "; original database preserved")
                .whenComplete((saved, error) -> onServerThread(() -> {
                    if (error != null) {
                        maintenanceTransitionPending.set(false);
                        plugin.failedMaintenanceCancellation();
                        player.sendMessage(color("&cCould not persist cancellation. "
                                + "Player access and logging remain restricted."));
                        plugin.getLogger().log(Level.SEVERE, "Maintenance cancellation failed", error);
                        return;
                    }
                    completeCancelledMaintenance(player, coordinator);
                }));
    }

    private void completeCancelledMaintenance(Player player,
                                              MaintenanceCoordinator coordinator) {
        if (coordinator.snapshot().phase() != MaintenancePhase.CANCELLED) {
            maintenanceTransitionPending.set(false);
            plugin.failedMaintenanceCancellation();
            player.sendMessage(color("&cMaintenance worker did not confirm cancellation. "
                    + "Player access and logging remain restricted; review status/console."));
            return;
        }
        // The durable cancellation must not admit regular players before the
        // single SQLite worker turns query_only OFF and reopens write admission.
        database.resumeWritesAfterCancellationAsync()
                .whenComplete((resumed, resumeError) -> onServerThread(() -> {
            maintenanceTransitionPending.set(false);
            if (resumeError != null) {
                plugin.failedMaintenanceCancellation();
                player.sendMessage(color("&cCancellation saved, but SQLite "
                        + "could not resume logging. Player access remains "
                        + "blocked. Review console before restarting."));
                plugin.getLogger().log(Level.SEVERE,
                        "Could not safely resume logging after maintenance", resumeError);
                return;
            }
            CompactionReplacementPolicy policy = plugin.getReplacementPolicy();
            if (policy != null) {
                policy.releaseStrictProofAfterCancel().whenComplete((released, releaseError) -> {
                    if (releaseError != null) {
                        plugin.getLogger().log(Level.FINE,
                                "Stale STRICT watcher cleanup deferred until worker finishes",
                                unwrap(releaseError));
                    }
                });
            }
            plugin.completeMaintenanceCancellation();
            // Restricted startup deferred persisted RUNNING/UNDOING jobs.
            // SQLite is writable and new-job admission is safe now; rescan
            // and restart only work that is not already running in this JVM.
            plugin.resumeDeferredRollbackRecoveryAfterCancellation();
            MaintenanceChatOutput.title(player::sendMessage, "Maintenance Cancelled");
            MaintenanceChatOutput.action(player::sendMessage,
                    "FragGuard history logging RESUMED; original and candidates preserved.");
        }));
    }

    private void approveMaintenancePolicy(Player player, String[] args,
                                         MaintenanceSnapshot state) {
        if (!MaintenanceUiActions.canApprove(state)) {
            player.sendMessage(color("&cFull candidate verification must complete first."));
            return;
        }
        CompactionReplacementPolicy policy = plugin.getReplacementPolicy();
        if (policy == null) {
            player.sendMessage(color("&cDatabase replacement policy service is unavailable."));
            return;
        }
        if (state.policy() == MaintenancePolicy.MAINTENANCE_SNAPSHOT
                && (args.length != 2
                    || !args[1].equalsIgnoreCase("snapshot-acknowledge"))) {
            player.sendMessage(color("&cSNAPSHOT REPLACEMENT WARNING: The actual SQLite "
                    + "snapshot is captured between VACUUM invocation and completion, not "
                    + "the maintenance countdown. Replacing the DB may permanently lose "
                    + "ALL FragGuard history, rollback/undo and retention updates after "
                    + "that snapshot. The Minecraft world does NOT roll back. "
                    + "To explicitly accept: &f/fg maintenance approve snapshot-acknowledge"));
            return;
        }
        if (state.policy() == MaintenancePolicy.STRICT && args.length != 1) {
            maintenanceUsage(player);
            return;
        }
        if (!maintenanceTransitionPending.compareAndSet(false, true)) {
            player.sendMessage(color("&eA maintenance operation is already pending."));
            return;
        }
        String acknowledgement = state.policy() == MaintenancePolicy.MAINTENANCE_SNAPSHOT
                ? CompactionReplacementPolicy.SNAPSHOT_ACKNOWLEDGEMENT : "";
        if (state.policy() == MaintenancePolicy.MAINTENANCE_SNAPSHOT) {
            MaintenanceChatOutput.title(player::sendMessage, "Snapshot Fingerprint Precheck");
            MaintenanceChatOutput.notice(player::sendMessage,
                    "Hashing original DB and verified copy while Paper is ONLINE.");
            MaintenanceChatOutput.notice(player::sendMessage,
                    "This may take several minutes for large databases.");
            MaintenanceChatOutput.danger(player::sendMessage,
                    "DO NOT STOP Paper until Snapshot approval succeeds.");
            MaintenanceChatOutput.notice(player::sendMessage,
                    "Any SQLite write after approval invalidates the shutdown receipt.");
        }
        if (state.policy() == MaintenancePolicy.STRICT) {
            MaintenanceChatOutput.title(player::sendMessage, "STRICT Full Precheck Started");
            MaintenanceChatOutput.notice(player::sendMessage,
                    "Comparing ALL source history, undo and indexes to the verified copy.");
            MaintenanceChatOutput.notice(player::sendMessage,
                    "This runs while Paper stays ONLINE; it may take several minutes.");
            MaintenanceChatOutput.danger(player::sendMessage,
                    "DO NOT STOP Paper until STRICT says the precheck passed.");
            MaintenanceChatOutput.notice(player::sendMessage,
                    "Any history change during or after this scan rejects STRICT approval.");
        }
        policy.prepare(player.getUniqueId(), acknowledgement)
                .whenComplete((decision, error) -> onServerThread(() -> {
                    maintenanceTransitionPending.set(false);
                    if (error != null || decision == null) {
                        player.sendMessage(color("&cReplacement policy not approved. Original "
                                + "database remains active and unchanged; see server console."));
                        plugin.getLogger().log(Level.WARNING,
                                "Failed to prepare maintenance replacement policy", error);
                        return;
                    }
                    MaintenanceChatOutput.title(player::sendMessage, "Replacement Policy");
                    if (decision.approved()) {
                        MaintenanceChatOutput.value(player::sendMessage,
                                "Snapshot policy", "APPROVED; file fingerprints completed",
                                NamedTextColor.YELLOW);
                        MaintenanceChatOutput.danger(player::sendMessage,
                                "Post-copy history, rollback/undo and retention CAN BE LOST.");
                        MaintenanceChatOutput.notice(player::sendMessage,
                                "Do not change FragGuard history after this approval.");
                        MaintenanceChatOutput.notice(player::sendMessage,
                                "A later SQLite write prevents the clean shutdown receipt.");
                        MaintenanceChatOutput.danger(player::sendMessage,
                                "Minecraft worlds are NOT rolled back.");
                        MaintenanceChatOutput.notice(player::sendMessage,
                                "FragGuard is stopping Paper cleanly and will activate the database "
                                + "automatically on its next start. Do NOT move SQLite files.");
                    } else {
                        MaintenanceChatOutput.value(player::sendMessage,
                                "STRICT precheck", "PASSED (all retained records match)",
                                NamedTextColor.GREEN);
                        MaintenanceChatOutput.value(player::sendMessage,
                                "STRICT policy", "PENDING clean shutdown attestation",
                                NamedTextColor.YELLOW);
                        MaintenanceChatOutput.notice(player::sendMessage,
                                "FragGuard will stop Paper cleanly, checkpoint SQLite and "
                                + "activate the candidate automatically on the next startup.");
                        MaintenanceChatOutput.notice(player::sendMessage,
                                "Any database change after the full precheck invalidates approval.");
                        MaintenanceChatOutput.danger(player::sendMessage,
                                "DO NOT move database files. If your host does not auto-restart, "
                                + "press its normal Start button once.");
                    }
                    MaintenanceChatOutput.action(player::sendMessage,
                            "Instructions: /fg maintenance instructions");
                    // The expensive STRICT proof is already complete while
                    // the server is live; only a bounded clean-stop receipt
                    // will be issued in onDisable(). Always request a normal
                    // Paper stop from the main thread, never an external script.
                    plugin.scheduleAutomaticCompactionStop(decision.operationId(),
                            decision.policy());
                }));
    }

    private void startMaintenanceCompaction(Player player, MaintenanceSnapshot state,
                                            OnlineDatabaseCompaction compaction) {
        if (!MaintenanceUiActions.canCompact(state)) {
            player.sendMessage(color("&cCompaction requires ACTIVE maintenance with "
                    + "the admission gate closed. Use /fg maintenance start first."));
            return;
        }
        UUID operator = player.getUniqueId();
        CompactionStoragePreflight.Report approved = capacityLastReports.get(operator);
        long ageMs = approved == null ? Long.MAX_VALUE
                : java.time.Duration.between(approved.snapshot().measuredAt(),
                    Instant.now()).toMillis();
        if (approved == null || !approved.mayReserve() || ageMs < 0 || ageMs > 25_000L) {
            capacityLastReports.remove(operator);
            player.sendMessage(color("&cA fresh approved storage preflight (within 25 seconds) "
                    + "is required. Run /fg diagnostics preflight, and acknowledge "
                    + "unknown headroom only after inspecting the report."));
            return;
        }
        capacityLastReports.remove(operator);
        CompletableFuture<OnlineDatabaseCompaction.Result> future = compaction.start(approved);
        if (future.isCompletedExceptionally()) {
            player.sendMessage(color("&cCompaction already running or blocked by state/preflight."));
            return;
        }
        latestCompaction = null;
        MaintenanceChatOutput.title(player::sendMessage, "SQLite Compaction Started");
        MaintenanceChatOutput.notice(player::sendMessage,
                "Creating a separate compacted candidate; original and WAL/SHM preserved.");
        MaintenanceChatOutput.notice(player::sendMessage,
                "No automatic replacement or guessed completion percentage.");
        future.whenComplete((result, failure) -> onServerThread(() -> {
            if (failure != null) {
                plugin.getLogger().log(Level.WARNING,
                        "FragGuard online SQLite compaction failed; source retained", unwrap(failure));
                if (player.isOnline()) player.sendMessage(color("&cCompaction failed or was cancelled. "
                        + "Preserve files and inspect maintenance status."));
                return;
            }
            latestCompaction = result; // Only the current process' actual snapshot evidence.
            if (player.isOnline()) {
                MaintenanceChatOutput.title(player::sendMessage, "Compaction Copy Complete");
                MaintenanceChatOutput.value(player::sendMessage, "Candidate",
                        result.candidate().getFileName().toString(), NamedTextColor.GREEN);
                MaintenanceChatOutput.value(player::sendMessage, "Size",
                        MigrationStoragePolicy.formatBytes(result.candidateBytes()));
                MaintenanceChatOutput.action(player::sendMessage,
                        "Next: /fg maintenance verify (full integrity/history/undo validation)");
            }
        }));
    }

    private void handleDeepDiagnostics(Player player, String action) {
        DatabaseDeepDiagnostics deep = plugin.getDeepDiagnostics();
        if (deep == null) {
            player.sendMessage(color("&cFragGuard deep diagnostics are unavailable."));
            return;
        }
        if ("progress".equals(action)) {
            DatabaseDeepDiagnostics.Progress progress = deep.progress();
            player.sendMessage(Component.text("Deep diagnostics: "
                    + (progress.running() ? "RUNNING" : "IDLE")
                    + " | " + progress.phase() + " | " + progress.recordsScanned()
                    + " records read", NamedTextColor.GRAY));
            if (!"none".equals(progress.latestResult())) {
                player.sendMessage(Component.text("Latest report: " + progress.latestResult(),
                        NamedTextColor.GRAY));
            }
            return;
        }
        if ("cancel".equals(action)) {
            if (deep.cancel()) {
                player.sendMessage(color("&eDeep diagnostics cancellation requested. "
                        + "Any saved output will be marked PARTIAL."));
            } else {
                player.sendMessage(color("&7No deep diagnostics scan is running."));
            }
            return;
        }
        UUID actor = player.getUniqueId();
        // The single global scan is also tracked by its initiating operator.
        // A duplicate command must never replace the pending completion hook:
        // doing so drops the final report and loses disconnect cancellation.
        if (deepDiagnosticRequests.containsKey(actor)) {
            player.sendMessage(color("&eYour deep diagnostics request is already being "
                    + "processed. Use &f/fg diagnostics progress&e or "
                    + "&f/fg diagnostics cancel&e."));
            return;
        }
        int seconds = Math.max(1, Math.min(21_600,
                plugin.getConfig().getInt("diagnostics-deep-timeout-seconds", 3600)));
        CompletableFuture<DatabaseDeepDiagnostics.Report> request = deep.run(seconds);
        if (request.isCompletedExceptionally()) {
            // run() immediately rejects concurrent or stopped scans. Never
            // register a rejected future as the scan owner.
            player.sendMessage(color("&eA deep diagnostics scan is already running, "
                    + "or diagnostics are unavailable. Use &f/fg diagnostics progress&e."));
            return;
        }
        deepDiagnosticRequests.put(actor, request);
        player.sendMessage(color("&7Starting read-only deep database validation. This can take "
                + "a long time on large histories; use &f/fg diagnostics progress&7 or "
                + "&f/fg diagnostics cancel&7."));
        request.whenComplete((report, failure) -> onServerThread(() -> {
            if (deepDiagnosticRequests.get(actor) != request) return;
            deepDiagnosticRequests.remove(actor);
            if (!player.isOnline() || !player.isOp() || !player.hasPermission("fragguard.admin")) {
                return;
            }
            if (failure != null) {
                Throwable cause = unwrap(failure);
                plugin.getLogger().log(Level.WARNING, "FragGuard deep diagnostics interrupted", cause);
                player.sendMessage(color("&cDeep validation stopped or failed ("
                        + cause.getClass().getSimpleName()
                        + "). Use /fg diagnostics progress and check diagnostic-reports "
                        + "for an available PARTIAL report; it must not be treated as verified."));
                return;
            }
            MaintenanceChatOutput.deepDiagnostics(player::sendMessage, report);
        }));
    }

    private void sendDatabaseStatus(Player player) {
        DatabaseHealth health = database.health();
        player.sendMessage(color("&8&m------&r &bFragGuard Storage &8&m------"));
        player.sendMessage(color("&7Health: " + (health.healthy() ? "&aOK" : "&cUNHEALTHY")
                + " &7| Write queue: &f" + health.queuedWrites() + "/" + health.writeCapacity()
                + " &7| Operations: &f" + health.queuedOperations() + "/" + health.operationCapacity()));
        player.sendMessage(color("&7Coalesced writes: &f" + health.coalescedWrites()
                + " &7| Dropped writes: " + (health.droppedWrites() > 0 ? "&c" : "&f") + health.droppedWrites()));
        if (!health.lastError().isBlank()) {
            player.sendMessage(color("&cLast storage error: " + health.lastError()));
        }
    }

    private void message(Player player, String text) {
        if (player != null && player.isOnline()) {
            player.sendMessage(color(text));
        } else {
            plugin.getLogger().info(PLAIN_TEXT.serialize(LEGACY_INPUT.deserialize(text)));
        }
    }

    private void onServerThread(Runnable action) {
        Bukkit.getScheduler().runTask(plugin, action);
    }

    private OptionalInt parsePage(String[] args) {
        for (String arg : args) {
            String lower = arg.toLowerCase(Locale.ROOT);
            if (lower.startsWith("p:") || lower.startsWith("page:")) {
                String value = arg.substring(arg.indexOf(':') + 1);
                try {
                    return OptionalInt.of(Math.max(1, Integer.parseInt(value)));
                } catch (NumberFormatException ignored) {
                    return OptionalInt.of(1);
                }
            }
        }
        return OptionalInt.of(1);
    }

    private List<String> parseTimeTokens(String[] args) {
        List<String> tokens = new ArrayList<>();
        boolean collecting = false;

        for (String arg : args) {
            String lower = arg.toLowerCase(Locale.ROOT);
            if (isForceToken(arg)) {
                continue;
            }
            if (lower.startsWith("t:") || lower.startsWith("time:")) {
                String first = arg.substring(arg.indexOf(':') + 1);
                if (!first.isBlank()) {
                    tokens.add(first);
                }
                collecting = true;
                continue;
            }

            if (collecting) {
                if (lower.startsWith("r:") || lower.startsWith("radius:")
                        || lower.startsWith("p:") || lower.startsWith("page:")) {
                    continue;
                }
                tokens.add(arg);
            }
        }

        return tokens;
    }

    private boolean isForceToken(String arg) {
        return arg.equalsIgnoreCase("force") || arg.equalsIgnoreCase("--force");
    }

    private Throwable unwrap(Throwable throwable) {
        if (throwable instanceof CompletionException && throwable.getCause() != null) {
            return throwable.getCause();
        }
        return throwable;
    }

    private void sendHelp(Player player) {
        player.sendMessage(color("&8&m------&r &bFragGuard &8&m------"));
        player.sendMessage(color("&f/fg lookup r:30 &7- Show block-change logs in a full-height radius."));
        player.sendMessage(color("&f/fg lookup r:30 p:2 &7- View another lookup page."));
        player.sendMessage(color("&f/fg lookup u:Builder p:2 &7- View a player's retained history in all worlds."));
        player.sendMessage(color("&f/fg lookup u:Builder w:world_nether t:6h &7- Filter player by world/time."));
        player.sendMessage(color("&f/fg rollback r:30 t:2d 7h 15m &7- Preview an area rollback."));
        player.sendMessage(color("&f/fg rollback r:30 t:2d force &7- Preview a rollback that may overwrite newer changes."));
        player.sendMessage(color("&f/fg rollback u:Builder t:1h &7- Preview player history across all worlds."));
        player.sendMessage(color("&f/fg rollback u:Builder t:1h w:world_nether &7- Restrict to one world."));
        player.sendMessage(color("&f/fg status <job-id> &7- Inspect saved rollback job scope and progress."));
        player.sendMessage(color("&f/fg rollback confirm <token> &7- Run a previewed rollback."));
        player.sendMessage(color("&f/fg rollback cancel &7- Dismiss your rollback preview."));
        player.sendMessage(color("&f/fg undo <job-id> &7- Reverse blocks actually changed by a saved rollback job."));
        player.sendMessage(color("&f/fg status &7- Show database queue and storage health."));
        player.sendMessage(color("&f/fg diagnostics &7- Read-only database size, schema and health."));
        player.sendMessage(color("&f/fg diagnostics counts &7- Scan exact history and rollback job counts."));
        player.sendMessage(color("&f/fg diagnostics check &7- Run SQLite quick integrity check."));
        player.sendMessage(color("&f/fg diagnostics deep &7- Full read-only integrity/history/hash validation."));
        player.sendMessage(color("&f/fg diagnostics progress &7- Deep scan progress and latest report."));
        player.sendMessage(color("&f/fg diagnostics cancel &7- Cancel deep scan (partial report)."));
        player.sendMessage(color("&f/fg diagnostics preflight &7- Estimate compaction disk/quota/temp needs."));
        player.sendMessage(color("&f/fg diagnostics preflight acknowledge &7- Accept unknown headroom, not shortages."));
        player.sendMessage(color("&f/fg diagnostics preflight details &7- View full previous capacity report."));
        player.sendMessage(color("&f/fg maintenance &7- Open Database Maintenance GUI."));
        player.sendMessage(color("&f/fg maintenance start [strict] &7- Begin maintenance countdown."));
        player.sendMessage(color("&f/fg maintenance start snapshot acknowledge &7- Opt in to snapshot mode."));
        player.sendMessage(color("&f/fg maintenance status [full] &7- Summary or complete status."));
        player.sendMessage(color("&f/fg maintenance compact &7- During ACTIVE maintenance, start an unverified SQLite copy."));
        player.sendMessage(color("&f/fg maintenance verify &7- Check candidate history/undo integrity."));
        player.sendMessage(color("&f/fg maintenance approve &7- Prepare STRICT post-shutdown approval."));
        player.sendMessage(color("&f/fg maintenance approve snapshot-acknowledge &7- Accept data-loss warning."));
        player.sendMessage(color("&f/fg maintenance instructions &7- View stopped-server file steps."));
        player.sendMessage(color("&f/fg maintenance cancel &7- Cancel a safe pre-activation stage."));
        player.sendMessage(color("&7Only server operators can use these commands."));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player player) || !player.isOp() || !player.hasPermission("fragguard.admin")) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            return partial(args[0], List.of("lookup", "rollback", "undo", "status", "diagnostics", "maintenance", "help"));
        }

        String subCommand = args[0].toLowerCase(Locale.ROOT);
        if (subCommand.equals("maintenance")) {
            if (args.length == 2) {
                return partial(args[1], List.of("menu", "start", "status", "preview", "compact",
                        "verify", "approve", "instructions", "cancel", "help"));
            }
            if (args.length == 3 && args[1].equalsIgnoreCase("start")) {
                return partial(args[2], List.of("strict", "snapshot"));
            }
            if (args.length == 4 && args[1].equalsIgnoreCase("start")
                    && args[2].equalsIgnoreCase("snapshot")) {
                return partial(args[3], List.of("acknowledge"));
            }
            if (args.length == 3 && args[1].equalsIgnoreCase("approve")) {
                return partial(args[2], List.of("snapshot-acknowledge"));
            }
            if (args.length == 3 && (args[1].equalsIgnoreCase("status")
                    || args[1].equalsIgnoreCase("instructions"))) {
                return partial(args[2], List.of("full"));
            }
            return Collections.emptyList();
        }
        if (subCommand.equals("diagnostics") || subCommand.equals("diag")) {
            if (args.length == 2) {
                return partial(args[1], List.of("overview", "counts", "check", "deep",
                        "progress", "cancel", "preflight"));
            }
            if (args.length == 3 && args[1].equalsIgnoreCase("preflight")) {
                return partial(args[2], List.of("acknowledge", "details"));
            }
            return Collections.emptyList();
        }
        if (subCommand.equals("lookup") || subCommand.equals("inspect") || subCommand.equals("l")) {
            boolean playerScope = PlayerLookupArguments.isPlayerScope(
                    Arrays.copyOfRange(args, 1, args.length));
            List<String> suggestions = new ArrayList<>(playerScope
                    ? List.of("u:", "p:1", "p:2", "t:1h", "t:1d", "t:2d")
                    : List.of("r:15", "r:30", "r:50", "p:1", "u:"));
            if (playerScope) {
                for (World world : plugin.getServer().getWorlds()) {
                    suggestions.add("w:" + world.getName());
                }
            }
            return partial(args[args.length - 1], suggestions);
        }
        if (subCommand.equals("rollback") || subCommand.equals("rb")) {
            if (args.length == 3 && args[1].equalsIgnoreCase("confirm")) {
                long now = System.currentTimeMillis();
                removeExpiredPreviews(now);
                return partial(args[2], previews.values().stream()
                        .filter(preview -> preview.actorUuid.equals(player.getUniqueId()))
                        .filter(preview -> preview.expiresAt > now)
                        .map(RollbackPreview::token)
                        .toList());
            }
            return partial(args[args.length - 1],
                    List.of("confirm", "cancel", "r:15", "r:30", "r:50", "u:", "w:", "t:1h", "t:1d", "2d", "7h", "15m", "force"));
        }
        return Collections.emptyList();
    }

    private List<String> partial(String current, List<String> options) {
        String lower = Objects.requireNonNullElse(current, "").toLowerCase(Locale.ROOT);
        return options.stream()
                .filter(option -> option.toLowerCase(Locale.ROOT).startsWith(lower))
                .toList();
    }

    private String color(String message) {
        return LEGACY_OUTPUT.serialize(LEGACY_INPUT.deserialize(message));
    }

    static boolean matchesState(String actualData, byte[] actualEntityData,
                                String expectedData, byte[] expectedEntityData) {
        return actualData.equals(expectedData)
                && !Arrays.equals(expectedEntityData, UNKNOWN_ENTITY_STATE)
                && (expectedEntityData == null
                ? actualEntityData == null
                : actualEntityData != null && BlockEntitySnapshot.equivalent(actualEntityData, expectedEntityData));
    }

    private record PreparedWorldChange(
            RollbackJobChange change,
            Block block,
            BlockData desired,
            String beforeData,
            byte[] beforeEntityData,
            byte[] desiredEntityData
    ) {
        private PreparedWorldChange(RollbackJobChange change, Block block, BlockData desired, String beforeData) {
            this(change, block, desired, beforeData, null, null);
        }
    }

    private record RollbackPreview(
            String token,
            UUID actorUuid,
            String actorName,
            String worldName,
            int centerX,
            int centerZ,
            int radius,
            long targetTimestamp,
            long snapshotTimestamp,
            boolean force,
            List<RollbackTarget> targets,
            long expiresAt,
            PlayerRollbackPlan playerPlan,
            String playerWorldScope
    ) {
        RollbackPreview(String token, UUID actorUuid, String actorName, String worldName,
                        int centerX, int centerZ, int radius, long targetTimestamp,
                        long snapshotTimestamp, boolean force, List<RollbackTarget> targets,
                        long expiresAt) {
            this(token, actorUuid, actorName, worldName, centerX, centerZ, radius,
                    targetTimestamp, snapshotTimestamp, force, targets, expiresAt, null, null);
        }

        boolean isPlayerScope() {
            return playerPlan != null;
        }
    }
}
