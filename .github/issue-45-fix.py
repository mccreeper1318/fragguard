from pathlib import Path


def replace_once(text, old, new, label):
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected 1 match, found {count}")
    return text.replace(old, new, 1)


listener_path = Path("src/main/java/org/pinnaclesmp/fragguard/BlockChangeListener.java")
listener = listener_path.read_text()
listener = replace_once(
    listener,
    "import org.bukkit.block.data.type.Door;\n",
    "import org.bukkit.block.data.type.Door;\nimport org.bukkit.block.data.type.TNT;\n",
    "TNT block-data import",
)
listener = replace_once(
    listener,
    "import org.bukkit.event.block.SpongeAbsorbEvent;\n",
    "import org.bukkit.event.block.SpongeAbsorbEvent;\nimport org.bukkit.event.block.TNTPrimeEvent;\n",
    "TNTPrimeEvent import",
)
listener = replace_once(
    listener,
    """        Block burnedBlock = event.getBlock();
        Map<BlockPosition, CapturedBlockState> beforeStates = new LinkedHashMap<>();
""",
    """        Block burnedBlock = event.getBlock();
        if (burnedBlock.getType() == Material.TNT
                && plugin.getConfig().getBoolean(\"log-explosions\", true)) {
            return;
        }
        Map<BlockPosition, CapturedBlockState> beforeStates = new LinkedHashMap<>();
""",
    "TNT burn deduplication",
)
listener = replace_once(
    listener,
    """        Block brokenBlock = event.getBlock();
        captureBefore(pendingBreak.beforeStates, brokenBlock);
        for (BlockFace face : PLAYER_BREAK_NEIGHBORS) {
""",
    """        Block brokenBlock = event.getBlock();
        boolean handledByTntPrime = brokenBlock.getBlockData() instanceof TNT tntData
                && tntData.isUnstable()
                && plugin.getConfig().getBoolean(\"log-explosions\", true);
        if (!handledByTntPrime) {
            captureBefore(pendingBreak.beforeStates, brokenBlock);
        }
        for (BlockFace face : PLAYER_BREAK_NEIGHBORS) {
""",
    "unstable TNT break deduplication",
)
marker = """    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void onEntityExplode(EntityExplodeEvent event) {
"""
handler = """    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void onTntPrime(TNTPrimeEvent event) {
        if (BlockLoggingSuppression.isSuppressed()) {
            return;
        }
        if (!plugin.getConfig().getBoolean(\"log-explosions\", true)) {
            return;
        }

        Actor actor = actorForTntPrime(event);
        logBlockAfterServerAppliesChange(
                event.getBlock(),
                ChangeAction.TNT_PRIME,
                actor.uuid(),
                actor.name()
        );
    }

"""
listener = replace_once(listener, marker, handler + marker, "TNT prime handler")
explosion_capture = "        event.blockList().forEach(block -> captureBefore(beforeStates, block));\n"
if listener.count(explosion_capture) != 2:
    raise SystemExit(f"explosion TNT deduplication: expected 2 matches, found {listener.count(explosion_capture)}")
listener = listener.replace(
    explosion_capture,
    """        for (Block block : event.blockList()) {
            if (block.getType() != Material.TNT) {
                captureBefore(beforeStates, block);
            }
        }
""",
)
listener = replace_once(
    listener,
    """        Material material = clickedBlock.getType();
        if (isTransientInteraction(material)) {
""",
    """        Material material = clickedBlock.getType();
        if (material == Material.TNT && plugin.getConfig().getBoolean(\"log-explosions\", true)) {
            return;
        }
        if (isTransientInteraction(material)) {
""",
    "player TNT interaction deduplication",
)
listener = replace_once(
    listener,
    """    private Actor actorForEntity(Entity entity, String causeLabel) {
""",
    """    private Actor actorForTntPrime(TNTPrimeEvent event) {
        String causeLabel = \"TNT Prime: \" + readableEnum(event.getCause().name());
        Entity primingEntity = event.getPrimingEntity();
        if (primingEntity != null) {
            return actorForEntity(primingEntity, causeLabel);
        }

        Block primingBlock = event.getPrimingBlock();
        if (primingBlock != null) {
            return new Actor(
                    SYSTEM_UUID,
                    causeLabel + \": \" + readableEnum(primingBlock.getType().name())
            );
        }
        return new Actor(SYSTEM_UUID, causeLabel);
    }

    private Actor actorForEntity(Entity entity, String causeLabel) {
""",
    "TNT prime attribution helper",
)
listener_path.write_text(listener)

action_path = Path("src/main/java/org/pinnaclesmp/fragguard/ChangeAction.java")
action = action_path.read_text()
action = replace_once(
    action,
    '    EXPLOSION("block.explosion", "EXPLOSION"),\n',
    '    EXPLOSION("block.explosion", "EXPLOSION"),\n    TNT_PRIME("block.tnt_prime", "TNT_PRIME"),\n',
    "TNT prime action",
)
action = replace_once(
    action,
    '            case EXPLOSION -> "exploded";\n',
    '            case EXPLOSION -> "exploded";\n            case TNT_PRIME -> "primed";\n',
    "TNT prime display text",
)
action_path.write_text(action)

test_path = Path("src/test/java/org/pinnaclesmp/fragguard/BlockChangeListenerTest.java")
test = test_path.read_text()
test = replace_once(
    test,
    "import org.bukkit.event.block.SpongeAbsorbEvent;\n",
    "import org.bukkit.event.block.SpongeAbsorbEvent;\nimport org.bukkit.event.block.TNTPrimeEvent;\n",
    "test TNTPrimeEvent import",
)
tests = r'''    @ParameterizedTest(name = "{0}")
    @MethodSource("tntPrimeCases")
    void recordsTntPrimeRemovalWithSpecificAttribution(
            String caseName,
            TNTPrimeEvent.PrimeCause cause,
            Entity primingEntity,
            Material primingBlockType,
            String expectedActorUuid,
            String expectedActorName
    ) {
        try (DeferredChangeHarness harness = new DeferredChangeHarness()) {
            Block tnt = harness.block(8, 64, 8, "minecraft:tnt[unstable=false]", "minecraft:air");
            when(tnt.getType()).thenReturn(Material.TNT);
            Block primingBlock = null;
            if (primingBlockType != null) {
                primingBlock = mock(Block.class);
                when(primingBlock.getType()).thenReturn(primingBlockType);
            }

            TNTPrimeEvent event = mock(TNTPrimeEvent.class);
            when(event.getBlock()).thenReturn(tnt);
            when(event.getCause()).thenReturn(cause);
            when(event.getPrimingEntity()).thenReturn(primingEntity);
            when(event.getPrimingBlock()).thenReturn(primingBlock);

            harness.listener.onTntPrime(event);
            verify(harness.database, never()).insertAsync(any());
            harness.runNextTick();

            BlockChange change = harness.captureSingleChange();
            assertEquals(ChangeAction.TNT_PRIME, change.action());
            assertEquals(expectedActorUuid, change.actorUuid());
            assertEquals(expectedActorName, change.actorName());
            assertEquals("minecraft:tnt[unstable=false]", change.beforeData());
            assertEquals("minecraft:air", change.afterData());
        }
    }

    @Test
    void playerInteractionDefersTntRemovalToTntPrimeEvent() {
        try (DeferredChangeHarness harness = new DeferredChangeHarness()) {
            Block tnt = harness.block(9, 64, 9, "minecraft:tnt[unstable=false]", "minecraft:air");
            when(tnt.getType()).thenReturn(Material.TNT);

            harness.listener.onPlayerInteract(harness.interactEvent(tnt));
            verify(harness.database, never()).insertAsync(any());
            verify(harness.scheduler, never()).runTask(eq(harness.plugin), any(Runnable.class));

            TNTPrimeEvent prime = mock(TNTPrimeEvent.class);
            when(prime.getBlock()).thenReturn(tnt);
            when(prime.getCause()).thenReturn(TNTPrimeEvent.PrimeCause.PLAYER);
            when(prime.getPrimingEntity()).thenReturn(harness.player);
            harness.listener.onTntPrime(prime);
            harness.runNextTick();

            BlockChange change = harness.captureSingleChange();
            assertEquals(ChangeAction.TNT_PRIME, change.action());
            assertEquals(PLAYER_UUID.toString(), change.actorUuid());
            assertEquals("Builder", change.actorName());
        }
    }

    @Test
    void explosionDefersTntCoordinateToTntPrimeEvent() {
        try (DeferredChangeHarness harness = new DeferredChangeHarness()) {
            UUID creeperUuid = UUID.fromString("55fdc0a2-f639-4f37-87fb-802ca6f386d2");
            Entity creeper = mock(Entity.class);
            when(creeper.getUniqueId()).thenReturn(creeperUuid);
            when(creeper.getType()).thenReturn(EntityType.CREEPER);

            Block tnt = harness.block(10, 64, 10, "minecraft:tnt[unstable=false]", "minecraft:air");
            Block stone = harness.block(11, 64, 10, "minecraft:stone", "minecraft:air");
            when(tnt.getType()).thenReturn(Material.TNT);
            when(stone.getType()).thenReturn(Material.STONE);

            EntityExplodeEvent explosion = mock(EntityExplodeEvent.class);
            when(explosion.getEntity()).thenReturn(creeper);
            when(explosion.blockList()).thenReturn(new ArrayList<>(List.of(tnt, stone)));
            harness.listener.onEntityExplode(explosion);

            TNTPrimeEvent prime = mock(TNTPrimeEvent.class);
            when(prime.getBlock()).thenReturn(tnt);
            when(prime.getCause()).thenReturn(TNTPrimeEvent.PrimeCause.EXPLOSION);
            when(prime.getPrimingEntity()).thenReturn(creeper);
            harness.listener.onTntPrime(prime);
            harness.runAllTasks();

            ArgumentCaptor<BlockChange> changes = ArgumentCaptor.forClass(BlockChange.class);
            verify(harness.database, times(2)).insertAsync(changes.capture());
            assertEquals(List.of(ChangeAction.EXPLOSION, ChangeAction.TNT_PRIME),
                    changes.getAllValues().stream().map(BlockChange::action).toList());
            assertEquals(List.of(11, 10), changes.getAllValues().stream().map(BlockChange::x).toList());
        }
    }

    private static Stream<Arguments> tntPrimeCases() {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(PLAYER_UUID);
        when(player.getName()).thenReturn("Builder");

        Projectile projectile = mock(Projectile.class);
        when(projectile.getShooter()).thenReturn(player);

        UUID creeperUuid = UUID.fromString("d5817df9-f062-4f5e-a0dd-ac328f570fa7");
        Entity creeper = mock(Entity.class);
        when(creeper.getUniqueId()).thenReturn(creeperUuid);
        when(creeper.getType()).thenReturn(EntityType.CREEPER);

        return Stream.of(
                Arguments.of("redstone priming", TNTPrimeEvent.PrimeCause.REDSTONE, null,
                        Material.REDSTONE_WIRE, "SYSTEM", "TNT Prime: Redstone: Redstone Wire"),
                Arguments.of("fire priming", TNTPrimeEvent.PrimeCause.FIRE, null,
                        Material.FIRE, "SYSTEM", "TNT Prime: Fire: Fire"),
                Arguments.of("player-fired projectile priming", TNTPrimeEvent.PrimeCause.PROJECTILE, projectile,
                        null, PLAYER_UUID.toString(), "Builder"),
                Arguments.of("entity explosion priming", TNTPrimeEvent.PrimeCause.EXPLOSION, creeper,
                        null, creeperUuid.toString(), "TNT Prime: Explosion: Creeper"),
                Arguments.of("direct player priming", TNTPrimeEvent.PrimeCause.PLAYER, player,
                        null, PLAYER_UUID.toString(), "Builder")
        );
    }

'''
test = replace_once(
    test,
    '''    @ParameterizedTest(name = "{0}")
    @MethodSource("multiBlockPlacements")
''',
    tests + '''    @ParameterizedTest(name = "{0}")
    @MethodSource("multiBlockPlacements")
''',
    "TNT prime regression tests",
)
test_path.write_text(test)

changelog_path = Path("changelog.md")
changelog = changelog_path.read_text()
changelog = replace_once(
    changelog,
    "- Fixed #44 so expected timed-query cancellations no longer mark SQLite storage unhealthy or emit storage-failure warnings. FragGuard now distinguishes its own timeout cancellation, JDBC query timeouts, and SQLite `SQLITE_INTERRUPT` results from genuine database failures.\n",
    "- Fixed #44 so expected timed-query cancellations no longer mark SQLite storage unhealthy or emit storage-failure warnings. FragGuard now distinguishes its own timeout cancellation, JDBC query timeouts, and SQLite `SQLITE_INTERRUPT` results from genuine database failures.\n- Fixed #45 by recording TNT blocks when they are primed by redstone, fire, projectiles, entities, players, unstable block breaks, or explosions. TNT priming now captures the actual next-tick block state with the most specific available cause attribution, while specialized TNT history owns the transition so generic interaction, burn, break, and explosion handlers do not duplicate it.\n",
    "26.3-1.1.3 changelog entry",
)
changelog_path.write_text(changelog)
