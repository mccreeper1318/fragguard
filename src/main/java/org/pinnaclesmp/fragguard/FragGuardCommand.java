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
import java.util.function.Consumer;
import java.util.logging.Level;

final class FragGuardCommand implements CommandExecutor, TabCompleter {
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")
            .withZone(ZoneId.systemDefault());
    private static final String OPERATION_QUEUE_FULL = "FragGuard's database operation queue is full.";
    private static final int MAX_FORCE_REVALIDATION_RETRIES = 8;
    private static final int MAX_AUDITED_CHANGES_PER_SLICE = 16;

    private final FragGuardPlugin plugin;
    private final Database database;
    private final Map<String, RollbackPreview> previews = new HashMap<>();
    private final Set<Long> executingJobs = new HashSet<>();
    private final Set<Long> pausedJobs = new HashSet<>();
    private final Map<Long, RollbackChunkPlan.ChunkKey> loadedJobChunks = new HashMap<>();
    private final Map<RollbackChunkPlan.ChunkKey, Integer> chunkTicketReferences = new HashMap<>();
    private final RollbackTickBudget rollbackTickBudget = new RollbackTickBudget();

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
        CompletableFuture<List<RollbackTarget>> query = database.rollbackTargetsAsync(
                worldName, centerX, centerZ, radius, targetTimestamp, snapshotTimestamp,
                maxBlocks, maximumRollbackSnapshotBytes());
        reportQueryProgress(player, query);
        query.whenComplete((targets, throwable) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (throwable != null) {
                reportRollbackQueryFailure(player, throwable);
                return;
            }
            previewRollback(player, targets, centerX, centerZ, radius, targetTimestamp,
                    snapshotTimestamp, force, maxBlocks);
        }));
    }

    private void reportRollbackQueryFailure(Player player, Throwable throwable) {
        Throwable cause = unwrap(throwable);
        if (cause instanceof TimeoutException) {
            player.sendMessage(color("&cRollback preview timed out. Reduce the radius or time range and try again."));
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

    private void previewRollback(Player player, List<RollbackTarget> targets, int centerX, int centerZ,
                                 int radius, long targetTimestamp, long snapshotTimestamp,
                                 boolean force, int maxBlocks) {
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
        previews.entrySet().removeIf(entry -> entry.getValue().expiresAt <= now
                || entry.getValue().actorUuid.equals(player.getUniqueId()));
        String token = UUID.randomUUID().toString().substring(0, 8);
        RollbackPreview preview = new RollbackPreview(token, player.getUniqueId(), player.getName(),
                player.getWorld().getName(), centerX, centerZ, radius, targetTimestamp, snapshotTimestamp,
                force, List.copyOf(targets), now + expirationSeconds * 1_000L);
        previews.put(token, preview);

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
                        preview.centerX, preview.centerZ, preview.radius, preview.targetTimestamp,
                        preview.snapshotTimestamp, preview.force, preview.targets)
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

        World world = Bukkit.getWorld(chunk.worldName());
        if (world == null) {
            failJob(job, operator, new IllegalStateException(
                    "World '" + chunk.worldName() + "' is not loaded."));
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
        World world = Bukkit.getWorld(chunk.worldName());
        if (world != null) {
            world.removePluginChunkTicket(chunk.x(), chunk.z(), plugin);
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
            for (RollbackJobChange change : batch) {
                if (!preparedBatch.isEmpty() && rollbackTickBudget.exhausted(System.nanoTime())) {
                    break;
                }
                World world = Bukkit.getWorld(change.worldName());
                if (world == null) {
                    throw new IllegalStateException("World '" + change.worldName() + "' is not loaded.");
                }
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
                    String pendingBeforeData = undo
                            ? Objects.requireNonNullElse(change.appliedData(), change.targetData())
                            : Objects.requireNonNull(change.beforeData(), "Missing prepared rollback state");
                    byte[] pendingBeforeEntityData = undo && change.appliedData() == null
                            ? change.targetEntityData()
                            : undo ? change.appliedEntityData() : change.beforeEntityData();
                    if (!matchesState(actualData, actualEntityData,
                            pendingBeforeData, pendingBeforeEntityData)) {
                        // The server stopped after mutating the world but before atomically confirming
                        // the audit and job progress. The observed result may differ from the requested
                        // state when physics normalized it, so recover any state that moved away from
                        // the durable pre-mutation snapshot.
                        results.put(change.sequence(), new RollbackStepResult(
                                change.sequence(), true, false, actualData, actualEntityData));
                        continue;
                    }
                }

                if (undo) {
                    if (matchesState(actualData, actualEntityData, normalizedDesired, desiredEntityData)) {
                        results.put(change.sequence(), new RollbackStepResult(change.sequence(), false, false));
                    } else if (!matchesState(actualData, actualEntityData,
                            Objects.requireNonNullElse(change.appliedData(), change.targetData()),
                            change.appliedData() == null
                                    ? change.targetEntityData()
                                    : change.appliedEntityData())) {
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
            failJob(job, operator, failure);
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
                failJob(job, operator, throwable);
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
        } finally {
            rollbackTickBudget.end(System.nanoTime());
        }

        persistPendingAudits(job, operator, undo, audits, auditIds -> applyPersistedCandidates(
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
            failJob(job, operator, new IllegalStateException(
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
                    if (desiredData.equals(resultingData)) {
                        BlockEntitySnapshot.restore(block, candidate.desiredEntityData());
                    } else {
                        BlockEntitySnapshot.restoreIfCompatible(block, candidate.desiredEntityData());
                    }
                } else {
                    BlockLoggingSuppression.runSuppressed(() -> {
                        if (!actualData.equals(desiredData)) {
                            block.setBlockData(candidate.desired(), false);
                        }
                        BlockEntitySnapshot.restore(block, candidate.desiredEntityData());
                    });
                }
                String appliedData = block.getBlockData().getAsString();
                byte[] appliedEntityData = BlockEntitySnapshot.capture(block);
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
            deleteRequiredAudits(job, operator, staleAuditIds, () ->
                    persistObservedCorrections(job, operator, observedCorrections,
                            () -> persistCompletedResultsBeforeFailure(
                                    job, operator, results, undo, finalFailure)));
            return;
        }

        deleteRequiredAudits(job, operator, staleAuditIds, () ->
                persistObservedCorrections(job, operator, observedCorrections, () -> {
                    if (forceRetries.isEmpty()) {
                        afterApplied.run();
                        return;
                    }
                    if (forceAttempt >= MAX_FORCE_REVALIDATION_RETRIES) {
                        failJob(job, operator, new IllegalStateException(
                                "Could not obtain a stable live block state after "
                                        + MAX_FORCE_REVALIDATION_RETRIES + " force revalidation attempts."));
                        return;
                    }
                    retryForcedChanges(job, operator, forceRetries, results, observedCorrections,
                            forceAttempt + 1, afterApplied);
                }));
    }

    private void persistCompletedResultsBeforeFailure(RollbackJob job, Player operator,
                                                      Map<Integer, RollbackStepResult> results,
                                                      boolean undo, RuntimeException failure) {
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
                RollbackJobChange change = changes.get(index);
                World world = Bukkit.getWorld(change.worldName());
                if (world == null) {
                    throw new IllegalStateException("World '" + change.worldName() + "' is not loaded.");
                }
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
            failJob(job, operator, failure);
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
                                () -> persistPendingAudits(job, operator, undo, audits, afterPersisted), 1L);
                        return;
                    }
                    failJob(job, operator, cause);
                }));
    }

    private void deleteRequiredAudits(RollbackJob job, Player operator, List<Long> auditIds,
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
                        () -> deleteRequiredAudits(job, operator, auditIds, afterDeleted), 1L);
                return;
            }
            failJob(job, operator, cause);
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
        pausedJobs.remove(job.id());
        releaseJobChunk(job.id());
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
        player.sendMessage(color("&f/fg rollback r:30 t:2d 7h 15m &7- Preview an area rollback."));
        player.sendMessage(color("&f/fg rollback r:30 t:2d force &7- Preview a rollback that may overwrite newer changes."));
        player.sendMessage(color("&f/fg rollback confirm <token> &7- Run a previewed rollback."));
        player.sendMessage(color("&f/fg undo <job-id> &7- Reverse blocks actually changed by a saved rollback job."));
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
            return partial(args[args.length - 1],
                    List.of("confirm", "r:15", "r:30", "r:50", "t:1h", "t:1d", "2d", "7h", "15m", "force"));
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

    private static boolean matchesState(String actualData, byte[] actualEntityData,
                                        String expectedData, byte[] expectedEntityData) {
        return actualData.equals(expectedData)
                && (expectedEntityData == null || Arrays.equals(actualEntityData, expectedEntityData));
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
            long expiresAt
    ) {
    }
}
