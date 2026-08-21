package org.pinnaclesmp.fragguard;

import io.papermc.paper.event.block.BlockBreakBlockEvent;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;

import java.util.LinkedHashMap;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

final class BlockChangeListener implements Listener {
    private static final String AIR_DATA = "minecraft:air";
    private static final String SYSTEM_UUID = "SYSTEM";
    private static final BlockFace[] PLAYER_BREAK_NEIGHBORS = {
            BlockFace.UP,
            BlockFace.DOWN,
            BlockFace.NORTH,
            BlockFace.SOUTH,
            BlockFace.EAST,
            BlockFace.WEST
    };

    private final FragGuardPlugin plugin;
    private final Database database;
    private final Map<UUID, PendingPlayerBreak> pendingPlayerBreaks = new LinkedHashMap<>();

    BlockChangeListener(FragGuardPlugin plugin, Database database) {
        this.plugin = plugin;
        this.database = database;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void onBlockPlace(BlockPlaceEvent event) {
        if (BlockLoggingSuppression.isSuppressed()) {
            return;
        }

        Player player = event.getPlayer();

        if (event instanceof BlockMultiPlaceEvent multiPlaceEvent) {
            for (BlockState replacedState : multiPlaceEvent.getReplacedBlockStates()) {
                logPlacement(replacedState, player);
            }
            return;
        }

        logPlacement(event.getBlockReplacedState(), player);
    }

    private void logPlacement(BlockState replacedState, Player player) {
        Block placedBlock = replacedState.getBlock();
        logNow(
                placedBlock,
                ChangeAction.PLACE,
                player.getUniqueId().toString(),
                player.getName(),
                replacedState.getBlockData().getAsString(),
                placedBlock.getBlockData().getAsString(),
                BlockEntitySnapshot.capture(replacedState),
                BlockEntitySnapshot.capture(placedBlock)
        );
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void onBlockBreak(BlockBreakEvent event) {
        if (BlockLoggingSuppression.isSuppressed()) {
            return;
        }

        Player player = event.getPlayer();
        UUID playerUuid = player.getUniqueId();
        PendingPlayerBreak pendingBreak = pendingPlayerBreaks.get(playerUuid);
        boolean shouldScheduleFlush = pendingBreak == null;

        if (pendingBreak == null) {
            pendingBreak = new PendingPlayerBreak(
                    System.currentTimeMillis(),
                    Bukkit.getCurrentTick(),
                    playerUuid.toString(),
                    player.getName()
            );
            pendingPlayerBreaks.put(playerUuid, pendingBreak);
        }

        Block brokenBlock = event.getBlock();
        captureBefore(pendingBreak.beforeStates, brokenBlock);
        for (BlockFace face : PLAYER_BREAK_NEIGHBORS) {
            captureBefore(pendingBreak.beforeStates, brokenBlock.getRelative(face));
        }

        if (shouldScheduleFlush) {
            PendingPlayerBreak scheduledBreak = pendingBreak;
            Bukkit.getScheduler().runTask(
                    plugin,
                    () -> flushPlayerBreak(playerUuid, scheduledBreak)
            );
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void onBlockPhysics(BlockPhysicsEvent event) {
        if (BlockLoggingSuppression.isSuppressed() || pendingPlayerBreaks.isEmpty()) {
            return;
        }

        Block affectedBlock = event.getBlock();
        BlockPosition affectedPosition = positionOf(affectedBlock);
        BlockPosition sourcePosition = positionOf(event.getSourceBlock());

        for (PendingPlayerBreak pendingBreak : pendingPlayerBreaks.values()) {
            if (pendingBreak.beforeStates.containsKey(sourcePosition)
                    || pendingBreak.beforeStates.containsKey(affectedPosition)) {
                captureBefore(pendingBreak.beforeStates, affectedBlock);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void onBlockBurn(BlockBurnEvent event) {
        if (BlockLoggingSuppression.isSuppressed()) {
            return;
        }
        if (!plugin.getConfig().getBoolean("log-fire-spread", true)) {
            return;
        }

        Block burnedBlock = event.getBlock();
        Map<BlockPosition, CapturedBlockState> beforeStates = new LinkedHashMap<>();
        captureBefore(beforeStates, burnedBlock);
        logAfterServerAppliesChange(beforeStates, ChangeAction.FIRE_BURN, "Fire Burn");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void onPlayerBucketEmpty(PlayerBucketEmptyEvent event) {
        if (BlockLoggingSuppression.isSuppressed()) {
            return;
        }
        if (!plugin.getConfig().getBoolean("log-liquid-flow", true)) {
            return;
        }

        Material bucket = event.getBucket();
        if (bucket != Material.WATER_BUCKET && bucket != Material.LAVA_BUCKET) {
            return;
        }

        Player player = event.getPlayer();
        Map<BlockPosition, CapturedBlockState> beforeStates = new LinkedHashMap<>();
        captureBefore(beforeStates, event.getBlock());
        captureBefore(beforeStates, event.getBlockClicked().getRelative(event.getBlockFace()));
        logAfterServerAppliesChange(
                beforeStates,
                ChangeAction.LIQUID_PLACE,
                player.getUniqueId().toString(),
                player.getName()
        );
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void onPlayerBucketFill(PlayerBucketFillEvent event) {
        if (BlockLoggingSuppression.isSuppressed()) {
            return;
        }
        if (!plugin.getConfig().getBoolean("log-liquid-flow", true)) {
            return;
        }

        Map<BlockPosition, CapturedBlockState> beforeStates = new LinkedHashMap<>();
        captureLiquidBefore(beforeStates, event.getBlock());
        captureLiquidBefore(beforeStates, event.getBlockClicked());
        captureLiquidBefore(beforeStates, event.getBlockClicked().getRelative(event.getBlockFace()));
        if (beforeStates.isEmpty()) {
            return;
        }

        Player player = event.getPlayer();
        logAfterServerAppliesChange(
                beforeStates,
                ChangeAction.LIQUID_REMOVE,
                player.getUniqueId().toString(),
                player.getName()
        );
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void onEntityExplode(EntityExplodeEvent event) {
        if (BlockLoggingSuppression.isSuppressed()) {
            return;
        }
        if (!plugin.getConfig().getBoolean("log-explosions", true)) {
            return;
        }

        Map<BlockPosition, CapturedBlockState> beforeStates = new LinkedHashMap<>();
        event.blockList().forEach(block -> captureBefore(beforeStates, block));

        Entity entity = event.getEntity();
        String actorName = "Explosion";
        if (entity != null) {
            actorName = "Explosion: " + readableEnum(entity.getType().name());
        }

        logAfterServerAppliesChange(beforeStates, ChangeAction.EXPLOSION, actorName);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void onBlockExplode(BlockExplodeEvent event) {
        if (BlockLoggingSuppression.isSuppressed()) {
            return;
        }
        if (!plugin.getConfig().getBoolean("log-explosions", true)) {
            return;
        }

        Map<BlockPosition, CapturedBlockState> beforeStates = new LinkedHashMap<>();
        event.blockList().forEach(block -> captureBefore(beforeStates, block));

        BlockState explodedState = event.getExplodedBlockState();
        if (explodedState != null && explodedState.getType() != Material.AIR) {
            beforeStates.putIfAbsent(positionOf(explodedState.getBlock()),
                    new CapturedBlockState(explodedState.getBlockData().getAsString(),
                            BlockEntitySnapshot.capture(explodedState)));
        }

        logAfterServerAppliesChange(beforeStates, ChangeAction.EXPLOSION, "Explosion: Block");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void onFireSpread(BlockSpreadEvent event) {
        if (BlockLoggingSuppression.isSuppressed()) {
            return;
        }
        if (!plugin.getConfig().getBoolean("log-fire-spread", true)) {
            return;
        }

        if (!isFire(event.getNewState().getType()) && !isFire(event.getSource().getType())) {
            return;
        }

        Block targetBlock = event.getBlock();
        logNow(
                targetBlock,
                ChangeAction.FIRE_SPREAD,
                SYSTEM_UUID,
                "Fire Spread",
                targetBlock.getBlockData().getAsString(),
                event.getNewState().getBlockData().getAsString(),
                BlockEntitySnapshot.capture(targetBlock),
                BlockEntitySnapshot.capture(event.getNewState())
        );
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void onLiquidFlow(BlockFromToEvent event) {
        if (BlockLoggingSuppression.isSuppressed()) {
            return;
        }
        if (!plugin.getConfig().getBoolean("log-liquid-flow", true)) {
            return;
        }

        Material sourceType = event.getBlock().getType();
        if (!isLiquid(sourceType)) {
            return;
        }

        Map<BlockPosition, CapturedBlockState> beforeStates = new LinkedHashMap<>();
        captureBefore(beforeStates, event.getToBlock());
        logAfterServerAppliesChange(beforeStates, ChangeAction.LIQUID_FLOW, readableEnum(sourceType.name()) + " Flow");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    void onBlockBrokenByBlock(BlockBreakBlockEvent event) {
        if (BlockLoggingSuppression.isSuppressed()) {
            return;
        }

        Block source = event.getSource();
        Material sourceType = source.getType();
        boolean liquidCause = isLiquid(sourceType);
        boolean pistonCause = isPiston(sourceType);
        if (!liquidCause && !pistonCause) {
            return;
        }
        if ((liquidCause && !plugin.getConfig().getBoolean("log-liquid-flow", true))
                || (pistonCause && !plugin.getConfig().getBoolean("log-pistons", true))) {
            return;
        }

        Block broken = event.getBlock();

        ChangeAction action = pistonCause ? ChangeAction.PISTON_BREAK : ChangeAction.LIQUID_BREAK;
        String actorName = pistonCause ? "Piston Break" : readableEnum(sourceType.name()) + " Break";

        logNow(
                broken,
                action,
                SYSTEM_UUID,
                actorName,
                broken.getBlockData().getAsString(),
                AIR_DATA,
                BlockEntitySnapshot.capture(broken),
                null
        );
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void onPistonExtend(BlockPistonExtendEvent event) {
        if (BlockLoggingSuppression.isSuppressed()) {
            return;
        }
        if (!plugin.getConfig().getBoolean("log-pistons", true)) {
            return;
        }

        Map<BlockPosition, CapturedBlockState> beforeStates = capturePistonAffectedBlocks(
                event.getBlock(), event.getDirection(), event.getBlocks());
        logAfterServerAppliesChange(beforeStates, ChangeAction.PISTON_EXTEND, "Piston Extend");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void onPistonRetract(BlockPistonRetractEvent event) {
        if (BlockLoggingSuppression.isSuppressed()) {
            return;
        }
        if (!plugin.getConfig().getBoolean("log-pistons", true)) {
            return;
        }

        Map<BlockPosition, CapturedBlockState> beforeStates = capturePistonAffectedBlocks(
                event.getBlock(), event.getDirection(), event.getBlocks());
        logAfterServerAppliesChange(beforeStates, ChangeAction.PISTON_RETRACT, "Piston Retract");
    }

    private Map<BlockPosition, CapturedBlockState> capturePistonAffectedBlocks(
            Block pistonBlock, org.bukkit.block.BlockFace direction, Iterable<Block> movedBlocks) {
        Map<BlockPosition, CapturedBlockState> beforeStates = new LinkedHashMap<>();

        captureBefore(beforeStates, pistonBlock);
        captureBefore(beforeStates, pistonBlock.getRelative(direction));
        captureBefore(beforeStates, pistonBlock.getRelative(direction, 2));

        for (Block movedBlock : movedBlocks) {
            captureBefore(beforeStates, movedBlock);
            captureBefore(beforeStates, movedBlock.getRelative(direction));
            captureBefore(beforeStates, movedBlock.getRelative(direction.getOppositeFace()));
        }

        return beforeStates;
    }

    private void logAfterServerAppliesChange(Map<BlockPosition, CapturedBlockState> beforeStates,
                                             ChangeAction action, String actorName) {
        logAfterServerAppliesChange(beforeStates, action, SYSTEM_UUID, actorName);
    }

    private void logAfterServerAppliesChange(Map<BlockPosition, CapturedBlockState> beforeStates,
                                             ChangeAction action, String actorUuid, String actorName) {
        if (BlockLoggingSuppression.isSuppressed() || beforeStates.isEmpty()) {
            return;
        }

        long happenedAt = System.currentTimeMillis();
        long serverTick = Bukkit.getCurrentTick();
        Bukkit.getScheduler().runTask(
                plugin,
                () -> writeCapturedChanges(
                        beforeStates,
                        happenedAt,
                        serverTick,
                        action,
                        actorUuid,
                        actorName
                )
        );
    }

    private void flushPlayerBreak(UUID playerUuid, PendingPlayerBreak pendingBreak) {
        if (!pendingPlayerBreaks.remove(playerUuid, pendingBreak)) {
            return;
        }

        writeCapturedChanges(
                pendingBreak.beforeStates,
                pendingBreak.happenedAt,
                pendingBreak.serverTick,
                ChangeAction.BREAK,
                pendingBreak.actorUuid,
                pendingBreak.actorName
        );
    }

    private void writeCapturedChanges(
            Map<BlockPosition, CapturedBlockState> beforeStates,
            long happenedAt,
            long serverTick,
            ChangeAction action,
            String actorUuid,
            String actorName
    ) {
        for (Map.Entry<BlockPosition, CapturedBlockState> entry : beforeStates.entrySet()) {
            BlockPosition position = entry.getKey();
            World world = Bukkit.getWorld(position.worldName());
            if (world == null) {
                continue;
            }

            CapturedBlockState before = entry.getValue();
            Block afterBlock = world.getBlockAt(
                    position.x(),
                    position.y(),
                    position.z()
            );
            String afterData = afterBlock.getBlockData().getAsString();
            byte[] afterEntityData = BlockEntitySnapshot.capture(afterBlock);
            if (before.blockData().equals(afterData)
                    && Arrays.equals(before.entityData(), afterEntityData)) {
                continue;
            }

            database.insertAsync(new BlockChange(
                    happenedAt,
                    serverTick,
                    actorUuid,
                    actorName,
                    position.worldName(),
                    position.x(),
                    position.y(),
                    position.z(),
                    action,
                    before.blockData(),
                    afterData,
                    before.entityData(),
                    afterEntityData
            ));
        }
    }

    private void logNow(Block block, ChangeAction action, String actorUuid, String actorName,
                        String beforeData, String afterData, byte[] beforeEntityData, byte[] afterEntityData) {
        if (BlockLoggingSuppression.isSuppressed()
                || (beforeData.equals(afterData) && Arrays.equals(beforeEntityData, afterEntityData))) {
            return;
        }

        database.insertAsync(new BlockChange(
                System.currentTimeMillis(),
                actorUuid,
                actorName,
                block.getWorld().getName(),
                block.getX(),
                block.getY(),
                block.getZ(),
                action,
                beforeData,
                afterData,
                beforeEntityData,
                afterEntityData
        ));
    }

    private void captureLiquidBefore(Map<BlockPosition, CapturedBlockState> beforeStates, Block block) {
        if (isLiquid(block.getType())) {
            captureBefore(beforeStates, block);
        }
    }

    private void captureBefore(Map<BlockPosition, CapturedBlockState> beforeStates, Block block) {
        beforeStates.putIfAbsent(
                positionOf(block),
                new CapturedBlockState(block.getBlockData().getAsString(), BlockEntitySnapshot.capture(block))
        );
    }

    private BlockPosition positionOf(Block block) {
        return new BlockPosition(
                block.getWorld().getName(),
                block.getX(),
                block.getY(),
                block.getZ()
        );
    }

    private boolean isFire(Material material) {
        return material == Material.FIRE || material == Material.SOUL_FIRE;
    }

    private boolean isLiquid(Material material) {
        return material == Material.WATER || material == Material.LAVA;
    }

    private boolean isPiston(Material material) {
        return material == Material.PISTON || material == Material.STICKY_PISTON || material == Material.PISTON_HEAD || material == Material.MOVING_PISTON;
    }

    private String readableEnum(String enumName) {
        String lower = enumName.toLowerCase(Locale.ROOT).replace('_', ' ');
        StringBuilder builder = new StringBuilder(lower.length());
        boolean capitalizeNext = true;
        for (char character : lower.toCharArray()) {
            if (Character.isWhitespace(character)) {
                builder.append(character);
                capitalizeNext = true;
            } else if (capitalizeNext) {
                builder.append(Character.toUpperCase(character));
                capitalizeNext = false;
            } else {
                builder.append(character);
            }
        }
        return builder.toString();
    }

    private static final class PendingPlayerBreak {
        private final long happenedAt;
        private final long serverTick;
        private final String actorUuid;
        private final String actorName;
        private final Map<BlockPosition, CapturedBlockState> beforeStates = new LinkedHashMap<>();

        private PendingPlayerBreak(
                long happenedAt,
                long serverTick,
                String actorUuid,
                String actorName
        ) {
            this.happenedAt = happenedAt;
            this.serverTick = serverTick;
            this.actorUuid = actorUuid;
            this.actorName = actorName;
        }
    }

    private record BlockPosition(String worldName, int x, int y, int z) {
    }

    private record CapturedBlockState(String blockData, byte[] entityData) {
    }
}
