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
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.CompletionException;
import java.util.logging.Level;

final class FragGuardCommand implements CommandExecutor, TabCompleter {
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")
            .withZone(ZoneId.systemDefault());

    private final FragGuardPlugin plugin;
    private final Database database;

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
            case "rollback", "rb" -> handleRollback(player, Arrays.copyOfRange(args, 1, args.length));
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
        database.rollbackTargetsAsync(worldName, centerX, centerZ, radius, targetTimestamp)
                .whenComplete((targets, throwable) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (throwable != null) {
                        plugin.getLogger().log(Level.WARNING, "FragGuard rollback query failed", unwrap(throwable));
                        player.sendMessage(color("&cRollback query failed. Check console for details."));
                        return;
                    }
                    startRollback(player, targets, radius, targetTimestamp);
                }));
    }

    private void startRollback(Player player, List<RollbackTarget> targets, int radius, long targetTimestamp) {
        int maxBlocks = Math.max(1, plugin.getConfig().getInt("rollback-max-blocks-per-command", 50_000));
        if (targets.isEmpty()) {
            player.sendMessage(color("&7No block changes found to rollback in that radius."));
            return;
        }
        if (targets.size() > maxBlocks) {
            player.sendMessage(color("&cRollback would affect " + targets.size() + " blocks, which is above the configured limit of " + maxBlocks + "."));
            player.sendMessage(color("&7Raise &frollback-max-blocks-per-command&7 in config.yml if you want to allow this."));
            return;
        }

        int blocksPerTick = Math.max(1, plugin.getConfig().getInt("rollback-blocks-per-tick", 500));
        boolean applyPhysics = plugin.getConfig().getBoolean("apply-physics-during-rollback", false);
        World world = Bukkit.getWorld(player.getWorld().getName());
        if (world == null) {
            player.sendMessage(color("&cWorld is not loaded anymore. Rollback cancelled."));
            return;
        }

        player.sendMessage(color("&eRollback started. &7Blocks to restore: &f" + targets.size()
                + " &7| Radius: &f" + radius
                + " &7| Target: &f" + DATE_FORMAT.format(Instant.ofEpochMilli(targetTimestamp))));

        new BukkitRunnable() {
            private int index = 0;
            private int changed = 0;

            @Override
            public void run() {
                int limit = Math.min(index + blocksPerTick, targets.size());
                while (index < limit) {
                    RollbackTarget target = targets.get(index++);
                    try {
                        World targetWorld = Bukkit.getWorld(target.worldName());
                        if (targetWorld == null) {
                            continue;
                        }
                        BlockData blockData = Bukkit.createBlockData(target.blockData());
                        Block block = targetWorld.getBlockAt(target.x(), target.y(), target.z());
                        if (!block.getBlockData().matches(blockData)) {
                            block.setBlockData(blockData, applyPhysics);
                            changed++;
                        }
                    } catch (IllegalArgumentException exception) {
                        plugin.getLogger().warning("Could not parse block data during rollback: " + target.blockData());
                    }
                }

                if (index >= targets.size()) {
                    cancel();
                    player.sendMessage(color("&aRollback complete. &7Restored &f" + changed + "&7 blocks."));
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
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
        player.sendMessage(color("&f/fg rollback r:30 t:2d 7h 15m &7- Restore area to that time ago."));
        player.sendMessage(color("&7Only server operators can use these commands."));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player player) || !player.isOp() || !player.hasPermission("fragguard.admin")) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            return partial(args[0], List.of("lookup", "rollback", "help"));
        }

        String subCommand = args[0].toLowerCase(Locale.ROOT);
        if (subCommand.equals("lookup") || subCommand.equals("inspect") || subCommand.equals("l")) {
            return partial(args[args.length - 1], List.of("r:15", "r:30", "r:50", "p:1"));
        }
        if (subCommand.equals("rollback") || subCommand.equals("rb")) {
            return partial(args[args.length - 1], List.of("r:15", "r:30", "r:50", "t:1h", "t:1d", "2d", "7h", "15m"));
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
}
