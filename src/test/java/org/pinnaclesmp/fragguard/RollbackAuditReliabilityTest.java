package org.pinnaclesmp.fragguard;

import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RollbackAuditReliabilityTest {
    private static final UUID WORLD_UUID = UUID.fromString("ed316352-a9a0-4edb-95b7-6a9d7bafee4c");
    private static final UUID ACTOR_UUID = UUID.fromString("d2bc13dd-d8a6-4ff9-8de5-91af444ca4db");

    @TempDir
    Path temporaryDirectory;

    private final AtomicInteger currentTick = new AtomicInteger(100);
    private Database database;

    @AfterEach
    void shutDownDatabase() {
        if (database != null) {
            database.shutdown();
        }
    }

    @Test
    void requiredRollbackAuditsBypassGameplayQueueAndRemainDistinct() throws Exception {
        database = startDatabase();
        long timestamp = System.currentTimeMillis();
        BlockChange requested = new BlockChange(
                timestamp,
                700L,
                ACTOR_UUID.toString(),
                "Builder [rollback #41]",
                "world",
                4,
                64,
                4,
                ChangeAction.ROLLBACK,
                "minecraft:stone",
                "minecraft:torch"
        );
        BlockChange observedPhysics = new BlockChange(
                timestamp + 1L,
                700L,
                ACTOR_UUID.toString(),
                "Builder [rollback #41]",
                "world",
                4,
                64,
                4,
                ChangeAction.ROLLBACK,
                "minecraft:torch",
                "minecraft:air"
        );

        List<Long> ids = database.insertRequiredAsync(List.of(requested, observedPhysics)).join();

        assertEquals(2, ids.size(), "required writes must return stable IDs for post-persist revalidation cleanup");
        DatabaseHealth health = database.health();
        assertEquals(0, health.queuedWrites(), "required rollback audits must not use the bounded gameplay queue");
        assertEquals(0, health.droppedWrites());
        assertEquals(0, health.coalescedWrites(), "required rollback transitions must remain individually auditable");

        LookupPage page = database.lookupAsync("world", 4, 4, 1, 1, 15, 30).join();
        assertEquals(2, page.totalRows());
        assertTrue(page.rows().stream().allMatch(row -> row.action() == ChangeAction.ROLLBACK));
        assertEquals("minecraft:torch", page.rows().get(0).beforeData());
        assertEquals("minecraft:air", page.rows().get(0).afterData());
        assertEquals("minecraft:stone", page.rows().get(1).beforeData());
        assertEquals("minecraft:torch", page.rows().get(1).afterData());

        database.deleteRequiredAsync(List.of(ids.get(0))).join();
        LookupPage afterRetraction = database.lookupAsync("world", 4, 4, 1, 1, 15, 30).join();
        assertEquals(1, afterRetraction.totalRows(),
                "a stale pre-mutation audit must be retractable when live-state revalidation fails");
        assertEquals("minecraft:torch", afterRetraction.rows().get(0).beforeData());
        assertEquals("minecraft:air", afterRetraction.rows().get(0).afterData());
    }

    @Test
    void observedRollbackAuditRecordsRequestedToActualTransition() {
        RollbackJob job = new RollbackJob(
                41L,
                System.currentTimeMillis(),
                ACTOR_UUID.toString(),
                "Builder",
                WORLD_UUID.toString(),
                "world",
                0,
                0,
                10,
                System.currentTimeMillis() - 1_000L,
                "RUNNING",
                1,
                0,
                0,
                0,
                null
        );

        BlockChange correction = RollbackAudit.create(
                job,
                "world",
                4,
                64,
                4,
                "minecraft:torch",
                "minecraft:air",
                false
        );

        assertEquals(ChangeAction.ROLLBACK, correction.action());
        assertEquals("Builder [rollback #41]", correction.actorName());
        assertEquals("minecraft:torch", correction.beforeData());
        assertEquals("minecraft:air", correction.afterData());
    }

    private Database startDatabase() throws Exception {
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        World world = mock(World.class);
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("database-write-queue-capacity", 64);
        configuration.set("database-write-batch-size", 16);
        configuration.set("database-query-timeout-seconds", 5);
        when(plugin.getDataFolder()).thenReturn(temporaryDirectory.toFile());
        when(plugin.getConfig()).thenReturn(configuration);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("FragGuardTest"));
        when(server.getCurrentTick()).thenAnswer(invocation -> currentTick.get());
        when(server.getWorld("world")).thenReturn(world);
        when(world.getUID()).thenReturn(WORLD_UUID);

        Database instance = new Database(plugin);
        instance.init();
        return instance;
    }
}
