package org.pinnaclesmp.fragguard;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
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
import java.util.logging.Level;

final class FragGuardCommand implements CommandExecutor, TabCompleter {
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")
            .withZone(ZoneId.systemDefault());

    private final FragGuardPlugin plugin;
    private final Database database;
    private final Map<String, RollbackPreview> previews = new HashMap<>();
    private final Set<Long> executingJobs = new HashSet<>();

    FragGuardCommand(FragGuardPlugin plugin, Database database) {
        this.plugin = plugin;
        this.database = database;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(color("&cFragGuard commands must be run in-game because they use your current location."));
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
                } else {
                    handleRollback(player, Arrays.copyOfRange(args, 1, args.length));
                }
            }
            case "undo" -> handleUndo(player, args);
            case "status" -> sendDatabaseStatus(player);
            default -> {
                player.sendMessage(color("&cUnknown FragGuard command. Use &f/fg help&c."));
                return true;
            }
        }

        return true;
    }

    private void handleLookup(Player player, String[] args) {
        int radius = parseRadius(args).orElse(15);
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

        player.sendMessage(color("&7Searching block logs in radius &f" + radius + "&7..."));
        database.lookupAsync(worldName, centerX, centerZ, radius, page, pageSize, plugin.getRetentionDays())
                .whenComplete((lookupPage, throwable) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (throwable != null) {
                        plugin.getLogger().log(Level.WARNING, "FragGuard lookup failed", unwrap(throwable));
                        player.sendMessage(color("&cFragGuard lookup failed. Check console for details."));
                        return;
                    }
                    sendLookupResults(player, lookupPage, radius);
                }));
    }

    private void sendLookupResults(Player player, LookupPage page, int radius) {
        player.sendMessage(color("&8&m------&r &bFragGuard Lookup &8&m------"));
        player.sendMessage(color("&7Radius: &f" + radius + " &7| Page: &f" + page.page() + "/" + page.totalPages() + " &7| Results: &f" + page.totalRows()));

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
        String before = readableBlockData(row.beforeData());
        String after = readableBlockData(row.afterData());

        if (row.beforeData().equals(row.afterData())) {
            return after;
        }
        if (isAir(row.beforeData())) {
            return after;
        }
        if (isAir(row.afterData())) {
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
        OptionalInt radiusOptional = parseRadius(args);
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
        long durationMillis;
        try {
            durationMillis = DurationParser.parseMillis(timeTokens);
        } catch (IllegalArgumentException exception) {
            player.sendMessage(color("&c" + exception.getMessage()));
            player.sendMessage(color("&7Example: &f/fg rollback r:30 t:2d 7h 15m"));
            return;
        }

        long maxHistoryMillis = plugin.getRetentionDays() * 24L * 60L * 60L * 1000L;
        if (durationMillis > maxHistoryMillis) {
            player.sendMessage(color("&cFragGuard only keeps " + plugin.getRetentionDays() + " days of history."));
            return;
        }

        long targetTimestamp = System.currentTimeMillis() - durationMillis;
        int centerX = player.getLocation().getBlockX();
        int centerZ = player.getLocation().getBlockZ();
        String worldName = player.getWorld().getName();

        player.sendMessage(color("&7Finding changes to rollback in radius &f" + radius + "&7 back to &f" + DATE_FORMAT.format(Instant.ofEpochMilli(targetTimestamp)) + "&7..."));
        int maxBlocks = Math.max(1, plugin.getConfig().getInt("rollback-max-blocks-per-command", 50_000));
        CompletableFuture<List<RollbackTarget>> query = database.rollbackTargetsAsync(
                worldName, centerX, centerZ, radius, targetTimestamp, maxBlocks);
        reportQueryProgress(player, query);
        query
                .whenComplete((targets, throwable) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (throwable != null) {
                        Throwable cause = unwrap(throwable);
                        if (cause instanceof TimeoutException) {
                            player.sendMessage(color("&cRollback preview timed out. Reduce the radius or time range and try again."));
                        } else {
                            plugin.getLogger().log(Level.WARNING, "FragGuard rollback query failed", cause);
                            player.sendMessage(color("&cRollback query failed. Check console for details."));
                        }
                        return;
                    }
                    previewRollback(player, targets, centerX, centerZ, radius, targetTimestamp, maxBlocks);
                }));
    }

    private void previewRollback(Player player, List<RollbackTarget> targets, int centerX, int centerZ,
                                 int radius, long targetTimestamp, int maxBlocks) {
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

        long expirationSeconds = Math.max(5, plugin.getConfig().getInt("rollback-confirmation-timeout-seconds", 60));
        long now = System.currentTimeMillis();
        previews.entrySet().removeIf(entry -> entry.getValue().expiresAt <= now
                || entry.getValue().actorUuid.equals(player.getUniqueId()));
        String token = UUID.randomUUID().toString().substring(0, 8);
        RollbackPreview preview = new RollbackPreview(token, player.getUniqueId(), player.getName(),
                player.getWorld().getName(), centerX, centerZ, radius, targetTimestamp,
                List.copyOf(targets), now + expirationSeconds * 1_000L);
        previews.put(token, preview);

        long affectedChunks = targets.stream()
                .map(target -> (((long) target.x() >> 4) << 32) ^ ((target.z() >> 4) & 0xffffffffL))
                .distinct()
                .count();
        player.sendMessage(color("&8&m------&r &eFragGuard Rollback Preview &8&m------"));
        player.sendMessage(color("&7Affected blocks: &f" + targets.size() + " &7| Chunks: &f" + affectedChunks
                + " &7| Radius: &f" + radius));
        player.sendMessage(color("&7Target time: &f" + DATE_FORMAT.format(Instant.ofEpochMilli(targetTimestamp))));
        player.sendMessage(color("&eNo blocks have been changed. Confirm within " + expirationSeconds
                + " seconds: &f/fg rollback confirm " + token));
    }

    private void handleRollbackConfirmation(Player player, String[] args) {
        if (args.length != 3) {
            player.sendMessage(color("&cUsage: &f/fg rollback confirm <token>"));
            return;
        }

        RollbackPreview preview = previews.get(args[2]);
        if (preview == null || preview.expiresAt < System.currentTimeMillis()) {
            previews.remove(args[2]);
            player.sendMessage(color("&cThat rollback confirmation token is invalid or has expired."));
            return;
        }
        if (!preview.actorUuid.equals(player.getUniqueId())) {
            player.sendMessage(color("&cThat rollback confirmation belongs to another operator."));
            return;
        }
        if (!preview.worldName.equals(player.getWorld().getName())) {
            player.sendMessage(color("&cReturn to the previewed world before confirming this rollback."));
            return;
        }

        previews.remove(preview.token);
        player.sendMessage(color("&7Saving rollback job and undo information..."));
        database.createRollbackJobAsync(preview.actorUuid.toString(), preview.actorName, preview.worldName,
                        preview.centerX, preview.centerZ, preview.radius, preview.targetTimestamp, preview.targets)
                .whenComplete((job, throwable) -> onServerThread(() -> {
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

    void resumeInterruptedJobs() {
        database.loadResumableJobsAsync().whenComplete((jobs, throwable) -> onServerThread(() -> {
            if (throwable != null) {
                plugin.getLogger().log(Level.SEVERE, "Could not recover interrupted FragGuard rollback jobs", unwrap(throwable));
                return;
            }
            for (RollbackJob job : jobs) {
                boolean undo = job.status().equals("UNDOING");
                plugin.getLogger().warning("Resuming interrupted " + (undo ? "undo" : "rollback") + " job #" + job.id() + ".");
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
        database.loadRollbackChangesAsync(job.id(), undo)
                .whenComplete((changes, throwable) -> onServerThread(() -> {
                    if (throwable != null) {
                        failJob(job, operator, throwable);
                        return;
                    }
                    runJobBatch(job, operator, changes, 0, undo, -1);
                }));
    }

    private void runJobBatch(RollbackJob job, Player operator, List<RollbackJobChange> changes,
                             int index, boolean undo, int previousProgress) {
        if (index >= changes.size()) {
            database.completeRollbackJobAsync(job.id(), undo).whenComplete((ignored, throwable) -> onServerThread(() -> {
                executingJobs.remove(job.id());
                if (throwable != null) {
                    failJob(job, operator, throwable);
                    return;
                }
                message(operator, "&a" + (undo ? "Undo" : "Rollback") + " job #" + job.id()
                        + " complete. &7Processed &f" + changes.size() + "&7 blocks."
                        + (undo ? "" : " &7Reverse it with &f/fg undo " + job.id()));
            }));
            return;
        }

        int blocksPerTick = Math.max(1, plugin.getConfig().getInt("rollback-blocks-per-tick", 500));
        int end = Math.min(index + blocksPerTick, changes.size());
        List<RollbackJobChange> batch = new ArrayList<>(end - index);
        try {
            for (int current = index; current < end; current++) {
                RollbackJobChange change = changes.get(current);
                World world = Bukkit.getWorld(change.worldName());
                if (world == null) {
                    throw new IllegalStateException("World '" + change.worldName() + "' is not loaded.");
                }
                if (!undo && change.beforeData() == null) {
                    Block block = world.getBlockAt(change.x(), change.y(), change.z());
                    change = change.withBeforeData(block.getBlockData().getAsString());
                }
                batch.add(change);
            }
        } catch (RuntimeException exception) {
            failJob(job, operator, exception);
            return;
        }

        CompletableFuture<Void> prepared = undo ? CompletableFuture.completedFuture(null)
                : database.prepareRollbackBatchAsync(job.id(), batch);
        int nextIndex = end;
        prepared.whenComplete((ignored, throwable) -> onServerThread(() -> {
            if (throwable != null) {
                failJob(job, operator, throwable);
                return;
            }
            applyPreparedBatch(job, operator, changes, batch, nextIndex, undo, previousProgress);
        }));
    }

    private void applyPreparedBatch(RollbackJob job, Player operator, List<RollbackJobChange> changes,
                                     List<RollbackJobChange> batch, int nextIndex, boolean undo,
                                     int previousProgress) {
        boolean applyPhysics = plugin.getConfig().getBoolean("apply-physics-during-rollback", false);
        List<RollbackStepResult> results = new ArrayList<>(batch.size());
        try {
            for (RollbackJobChange change : batch) {
                World world = Bukkit.getWorld(change.worldName());
                if (world == null) {
                    throw new IllegalStateException("World '" + change.worldName() + "' is not loaded.");
                }
                String desiredData = undo ? change.beforeData() : change.targetData();
                BlockData desired = Bukkit.createBlockData(Objects.requireNonNull(desiredData, "Missing saved block state"));
                Block block = world.getBlockAt(change.x(), change.y(), change.z());
                BlockData currentData = block.getBlockData();
                boolean changed = !currentData.matches(desired);
                if (changed) {
                    database.insertAsync(RollbackAudit.create(
                            job,
                            block,
                            currentData.getAsString(),
                            desired.getAsString(),
                            undo
                    ));
                    BlockLoggingSuppression.runSuppressed(() -> block.setBlockData(desired, applyPhysics));
                }
                results.add(new RollbackStepResult(change.sequence(), changed));
            }
        } catch (RuntimeException exception) {
            failJob(job, operator, exception);
            return;
        }

        CompletableFuture<Void> persisted = undo
                ? database.markUndoBatchAppliedAsync(job.id(), results)
                : database.markRollbackBatchAppliedAsync(job.id(), results);
        persisted.whenComplete((ignored, throwable) -> onServerThread(() -> {
            if (throwable != null) {
                failJob(job, operator, throwable);
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
        Throwable cause = unwrap(throwable);
        String reason = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
        plugin.getLogger().log(Level.SEVERE, "FragGuard rollback job #" + job.id() + " failed", cause);
        message(operator, "&cRollback job #" + job.id() + " failed: " + reason
                + " &7Saved changes can be reversed with &f/fg undo " + job.id());
        database.failRollbackJobAsync(job.id(), reason).exceptionally(failure -> {
            plugin.getLogger().log(Level.SEVERE, "Could not persist failure of rollback job #" + job.id(), unwrap(failure));
            return null;
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

    private void reportQueryProgress(Player player, CompletableFuture<?> query) {
        new BukkitRunnable() {
            private int elapsedSeconds;

            @Override
            public void run() {
                if (query.isDone() || !player.isOnline()) {
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
            plugin.getLogger().info(ChatColor.stripColor(color(text)));
        }
    }

    private void onServerThread(Runnable action) {
        Bukkit.getScheduler().runTask(plugin, action);
    }

    private OptionalInt parseRadius(String[] args) {
        for (String arg : args) {
            String lower = arg.toLowerCase(Locale.ROOT);
            if (lower.startsWith("r:") || lower.startsWith("radius:")) {
                String value = arg.substring(arg.indexOf(':') + 1);
                try {
                    return OptionalInt.of(Integer.parseInt(value));
                } catch (NumberFormatException ignored) {
                    return OptionalInt.empty();
                }
            }
        }
        return OptionalInt.empty();
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
            if (lower.startsWith("t:") || lower.startsWith("time:")) {
                String first = arg.substring(arg.indexOf(':') + 1);
                if (!first.isBlank()) {
                    tokens.add(first);
                }
                collecting = true;
                continue;
            }

            if (collecting) {
                if (lower.startsWith("r:") || lower.startsWith("radius:") || lower.startsWith("p:") || lower.startsWith("page:")) {
                    continue;
                }
                tokens.add(arg);
            }
        }

        return tokens;
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
        player.sendMessage(color("&f/fg rollback r:30 t:2d 7h 15m &7- Preview an area rollback."));
        player.sendMessage(color("&f/fg rollback confirm <token> &7- Run a previewed rollback."));
        player.sendMessage(color("&f/fg undo <job-id> &7- Reverse a saved rollback job."));
        player.sendMessage(color("&f/fg status &7- Show database queue and storage health."));
        player.sendMessage(color("&7Only server operators can use these commands."));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player player) || !player.isOp() || !player.hasPermission("fragguard.admin")) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            return partial(args[0], List.of("lookup", "rollback", "undo", "status", "help"));
        }

        String subCommand = args[0].toLowerCase(Locale.ROOT);
        if (subCommand.equals("lookup") || subCommand.equals("inspect") || subCommand.equals("l")) {
            return partial(args[args.length - 1], List.of("r:15", "r:30", "r:50", "p:1"));
        }
        if (subCommand.equals("rollback") || subCommand.equals("rb")) {
            if (args.length == 3 && args[1].equalsIgnoreCase("confirm")) {
                return partial(args[2], previews.values().stream()
                        .filter(preview -> preview.actorUuid.equals(player.getUniqueId()))
                        .map(RollbackPreview::token)
                        .toList());
            }
            return partial(args[args.length - 1], List.of("confirm", "r:15", "r:30", "r:50", "t:1h", "t:1d", "2d", "7h", "15m"));
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
        return ChatColor.translateAlternateColorCodes('&', message);
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
            List<RollbackTarget> targets,
            long expiresAt
    ) {
    }
}
