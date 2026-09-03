package org.pinnaclesmp.fragguard;

import io.papermc.paper.event.block.BlockBreakBlockEvent;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Lectern;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.type.Bed;
import org.bukkit.block.data.type.Door;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.EntityBlockFormEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.block.SpongeAbsorbEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerTakeLecternBookEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.inventory.InventoryHolder;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
    private final FertilizationDeduplicator fertilizationDeduplicator = new FertilizationDeduplicator();

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
    void onBlockIgnite(BlockIgniteEvent event) {
        if (BlockLoggingSuppression.isSuppressed()) {
            return;
        }
        if (!plugin.getConfig().getBoolean("log-fire-spread", true)) {
            return;
        }
        if (event.getCause() == BlockIgniteEvent.IgniteCause.SPREAD) {
            return;
        }

        Actor actor = actorForEntity(
                event.getIgnitingEntity(),
                "Fire Ignite: " + readableEnum(event.getCause().name())
        );
        logBlockAfterServerAppliesChange(
                event.getBlock(),
                ChangeAction.FIRE_IGNITE,
                actor.uuid(),
                actor.name()
        );
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
    void onSpongeAbsorb(SpongeAbsorbEvent event) {
        if (BlockLoggingSuppression.isSuppressed()) {
            return;
        }
        if (!plugin.getConfig().getBoolean("log-liquid-flow", true)) {
            return;
        }

        Map<BlockPosition, CapturedBlockState> beforeStates = new LinkedHashMap<>();
        captureBefore(beforeStates, event.getBlock());
        for (BlockState changedState : event.getBlocks()) {
            captureBefore(beforeStates, changedState.getBlock());
        }
        logAfterServerAppliesChange(beforeStates, ChangeAction.SPONGE_ABSORB, "Sponge Absorb");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void onBlockDispense(BlockDispenseEvent event) {
        if (BlockLoggingSuppression.isSuppressed()) {
            return;
        }
        if (!plugin.getConfig().getBoolean("log-liquid-flow", true)) {
            return;
        }

        Material itemType = event.getItem().getType();
        boolean removingLiquid = itemType == Material.BUCKET;
        if (!removingLiquid && !isPlaceableBucket(itemType)) {
            return;
        }

        Block dispenser = event.getBlock();
        if (!(dispenser.getBlockData() instanceof Directional directional)) {
            return;
        }

        ChangeAction action = removingLiquid
                ? ChangeAction.DISPENSER_LIQUID_REMOVE
                : ChangeAction.DISPENSER_LIQUID_PLACE;
        String actorName = removingLiquid
                ? "Dispenser Bucket Removal"
                : "Dispenser: " + readableEnum(itemType.name());
        logBlockAfterServerAppliesChange(
                dispenser.getRelative(directional.getFacing()),
                action,
                SYSTEM_UUID,
                actorName
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

        Actor actor = actorForEntity(event.getEntity(), "Explosion");
        logAfterServerAppliesChange(
                beforeStates,
                ChangeAction.EXPLOSION,
                actor.uuid(),
                actor.name()
        );
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

        boolean fireSpread = isFire(event.getNewState().getType()) || isFire(event.getSource().getType());
        if (fireSpread && !plugin.getConfig().getBoolean("log-fire-spread", true)) {
            return;
        }

        logBlockAfterServerAppliesChange(
                event.getBlock(),
                fireSpread ? ChangeAction.FIRE_SPREAD : ChangeAction.BLOCK_SPREAD,
                SYSTEM_UUID,
                fireSpread ? "Fire Spread" : "Natural Block Spread"
        );
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void onBlockGrow(BlockGrowEvent event) {
        if (BlockLoggingSuppression.isSuppressed() || event instanceof BlockFormEvent) {
            return;
        }
        logBlockAfterServerAppliesChange(event.getBlock(), ChangeAction.BLOCK_GROW, "Natural Growth");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void onBlockFade(BlockFadeEvent event) {
        if (BlockLoggingSuppression.isSuppressed()) {
            return;
        }
        logBlockAfterServerAppliesChange(event.getBlock(), ChangeAction.BLOCK_FADE, "Block Fade");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void onBlockForm(BlockFormEvent event) {
        if (BlockLoggingSuppression.isSuppressed()
                || event instanceof BlockSpreadEvent
                || event instanceof EntityBlockFormEvent) {
            return;
        }
        logBlockAfterServerAppliesChange(event.getBlock(), ChangeAction.BLOCK_FORM, "Natural Block Form");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void onEntityBlockForm(EntityBlockFormEvent event) {
        if (BlockLoggingSuppression.isSuppressed()) {
            return;
        }

        Actor actor = actorForEntity(event.getEntity(), "Entity Block Form");
        logBlockAfterServerAppliesChange(
                event.getBlock(),
                ChangeAction.ENTITY_BLOCK_FORM,
                actor.uuid(),
                actor.name()
        );
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void onLeavesDecay(LeavesDecayEvent event) {
        if (BlockLoggingSuppression.isSuppressed()) {
            return;
        }
        logBlockAfterServerAppliesChange(event.getBlock(), ChangeAction.LEAVES_DECAY, "Leaves Decay");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void onStructureGrow(StructureGrowEvent event) {
        if (BlockLoggingSuppression.isSuppressed()) {
            return;
        }

        if (event.isFromBonemeal()) {
            fertilizationDeduplicator.rememberBonemealedStructure(Bukkit.getCurrentTick(), event.getBlocks());
        }

        Map<BlockPosition, CapturedBlockState> beforeStates = captureBeforeStates(event.getBlocks());
        Player player = event.getPlayer();
        String actorName = event.isFromBonemeal() ? "Fertilized Structure Growth" : "Natural Structure Growth";
        if (player == null) {
            logAfterServerAppliesChange(beforeStates, ChangeAction.STRUCTURE_GROW, actorName);
        } else {
            logAfterServerAppliesChange(
                    beforeStates,
                    ChangeAction.STRUCTURE_GROW,
                    player.getUniqueId().toString(),
                    player.getName()
            );
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void onBlockFertilize(BlockFertilizeEvent event) {
        if (BlockLoggingSuppression.isSuppressed()) {
            return;
        }

        Map<BlockPosition, CapturedBlockState> beforeStates = captureBeforeStates(
                fertilizationDeduplicator.excludeBonemealedStructureBlocks(
                        Bukkit.getCurrentTick(), event.getBlocks()));
        Player player = event.getPlayer();
        if (player == null) {
            logAfterServerAppliesChange(beforeStates, ChangeAction.FERTILIZE, "Fertilization");
        } else {
            logAfterServerAppliesChange(
                    beforeStates,
                    ChangeAction.FERTILIZE,
                    player.getUniqueId().toString(),
                    player.getName()
            );
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (BlockLoggingSuppression.isSuppressed()) {
            return;
        }

        Actor actor = actorForEntity(event.getEntity(), "Entity Block Change");
        logBlockAfterServerAppliesChange(
                event.getBlock(),
                ChangeAction.ENTITY_CHANGE_BLOCK,
                actor.uuid(),
                actor.name()
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    void onPlayerInteract(PlayerInteractEvent event) {
        if (BlockLoggingSuppression.isSuppressed()) {
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK && event.getAction() != Action.PHYSICAL) {
            return;
        }
        if (event.useInteractedBlock() == Event.Result.DENY && event.useItemInHand() == Event.Result.DENY) {
            return;
        }

        Block clickedBlock = event.getClickedBlock();
        if (clickedBlock == null) {
            return;
        }

        Material material = clickedBlock.getType();
        if (isTransientInteraction(material)) {
            return;
        }

        BlockData blockData = clickedBlock.getBlockData();
        BlockState blockState = clickedBlock.getState();
        if (blockState instanceof InventoryHolder
                && !hasStructuralInventoryInteraction(material)) {
            return;
        }

        Player player = event.getPlayer();
        Map<BlockPosition, CapturedBlockState> beforeStates = new LinkedHashMap<>();
        captureInteractionBefore(beforeStates, clickedBlock, blockData, blockState);
        logAfterServerAppliesChange(
                beforeStates,
                ChangeAction.PLAYER_INTERACT,
                player.getUniqueId().toString(),
                player.getName()
        );
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void onPlayerTakeLecternBook(PlayerTakeLecternBookEvent event) {
        if (BlockLoggingSuppression.isSuppressed()) {
            return;
        }

        Lectern lectern = event.getLectern();
        Player player = event.getPlayer();
        Map<BlockPosition, CapturedBlockState> beforeStates = new LinkedHashMap<>();
        captureInteractionBefore(beforeStates, lectern.getBlock(), lectern.getBlockData(), lectern);
        logAfterServerAppliesChange(
                beforeStates,
                ChangeAction.PLAYER_INTERACT,
                player.getUniqueId().toString(),
                player.getName()
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
        captureBefore(beforeStates, event.getBlock());
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

    private void logBlockAfterServerAppliesChange(Block block, ChangeAction action, String actorName) {
        logBlockAfterServerAppliesChange(block, action, SYSTEM_UUID, actorName);
    }

    private void logBlockAfterServerAppliesChange(
            Block block,
            ChangeAction action,
            String actorUuid,
            String actorName
    ) {
        Map<BlockPosition, CapturedBlockState> beforeStates = new LinkedHashMap<>();
        captureBefore(beforeStates, block);
        logAfterServerAppliesChange(beforeStates, action, actorUuid, actorName);
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
            boolean structuralChange = !before.blockData().equals(afterData);
            if (before.requireStructuralChange() && !structuralChange) {
                continue;
            }

            byte[] afterEntityData = BlockEntitySnapshot.capture(afterBlock);
            if (!structuralChange && Arrays.equals(before.entityData(), afterEntityData)) {
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

    private Map<BlockPosition, CapturedBlockState> captureBeforeStates(Iterable<BlockState> states) {
        Map<BlockPosition, CapturedBlockState> beforeStates = new LinkedHashMap<>();
        for (BlockState state : states) {
            captureBefore(beforeStates, state.getBlock());
        }
        return beforeStates;
    }

    private void captureInteractionBefore(
            Map<BlockPosition, CapturedBlockState> beforeStates,
            Block block,
            BlockData blockData,
            BlockState state
    ) {
        beforeStates.putIfAbsent(
                positionOf(block),
                new CapturedBlockState(
                        blockData.getAsString(),
                        BlockEntitySnapshot.capture(state),
                        state instanceof InventoryHolder
                )
        );

        Block pairedBlock = null;
        if (blockData instanceof Door door) {
            pairedBlock = block.getRelative(
                    door.getHalf() == Bisected.Half.TOP ? BlockFace.DOWN : BlockFace.UP
            );
        } else if (blockData instanceof Bed bed) {
            pairedBlock = block.getRelative(
                    bed.getPart() == Bed.Part.FOOT
                            ? bed.getFacing()
                            : bed.getFacing().getOppositeFace()
            );
        }

        if (pairedBlock != null) {
            BlockData pairedData = pairedBlock.getBlockData();
            BlockState pairedState = pairedBlock.getState();
            beforeStates.putIfAbsent(
                    positionOf(pairedBlock),
                    new CapturedBlockState(
                            pairedData.getAsString(),
                            BlockEntitySnapshot.capture(pairedState),
                            pairedState instanceof InventoryHolder
                    )
            );
        }
    }

    private boolean isTransientInteraction(Material material) {
        if (material == null) {
            return false;
        }

        String name = material.name();
        return material == Material.TRIPWIRE
                || name.endsWith("_BUTTON")
                || name.endsWith("_PRESSURE_PLATE")
                || name.endsWith("_BED");
    }

    private boolean hasStructuralInventoryInteraction(Material material) {
        return material == Material.LECTERN
                || material == Material.CHISELED_BOOKSHELF
                || material == Material.JUKEBOX;
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

    private boolean isPlaceableBucket(Material material) {
        return material.name().endsWith("_BUCKET") && material != Material.MILK_BUCKET;
    }

    private Actor actorForEntity(Entity entity, String causeLabel) {
        if (entity == null) {
            return new Actor(SYSTEM_UUID, causeLabel);
        }
        Actor playerCause = playerActorForCause(entity, new HashSet<>());
        if (playerCause != null) {
            return playerCause;
        }
        return new Actor(
                entity.getUniqueId().toString(),
                causeLabel + ": " + readableEnum(entity.getType().name())
        );
    }

    private Actor playerActorForCause(Entity entity, Set<UUID> visited) {
        if (entity == null) {
            return null;
        }
        if (entity instanceof Player player) {
            return new Actor(player.getUniqueId().toString(), player.getName());
        }
        Entity causalEntity = null;
        if (entity instanceof Projectile projectile) {
            Object shooter = projectile.getShooter();
            if (shooter instanceof Player player) {
                return new Actor(player.getUniqueId().toString(), player.getName());
            }
            if (shooter instanceof Entity shooterEntity) {
                causalEntity = shooterEntity;
            }
        } else if (entity instanceof TNTPrimed tnt) {
            Entity source = tnt.getSource();
            if (source instanceof Player player) {
                return new Actor(player.getUniqueId().toString(), player.getName());
            }
            causalEntity = source;
        }

        if (causalEntity == null || !visited.add(entity.getUniqueId())) {
            return null;
        }
        return playerActorForCause(causalEntity, visited);
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

    private record CapturedBlockState(String blockData, byte[] entityData, boolean requireStructuralChange) {
        private CapturedBlockState(String blockData, byte[] entityData) {
            this(blockData, entityData, false);
        }
    }

    private record Actor(String uuid, String name) {
    }
}
