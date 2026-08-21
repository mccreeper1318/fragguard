package org.pinnaclesmp.fragguard;

import org.bukkit.World;
import org.bukkit.block.Block;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RollbackAuditTest {
    @Test
    void createsRollbackAuditWithOperatorJobAndBlockTransition() {
        RollbackJob job = new RollbackJob(
                73L,
                1L,
                "operator-uuid",
                "Admin",
                "world-uuid",
                "world",
                0,
                0,
                10,
                0L,
                "RUNNING",
                1,
                0,
                0,
                0,
                null
        );
        World world = mock(World.class);
        Block block = mock(Block.class);
        when(world.getName()).thenReturn("world");
        when(block.getWorld()).thenReturn(world);
        when(block.getX()).thenReturn(4);
        when(block.getY()).thenReturn(70);
        when(block.getZ()).thenReturn(-8);

        BlockChange rollback = RollbackAudit.create(
                job,
                block,
                "minecraft:diamond_block",
                "minecraft:stone",
                false
        );

        assertEquals("operator-uuid", rollback.actorUuid());
        assertEquals("Admin [rollback #73]", rollback.actorName());
        assertEquals("world", rollback.worldName());
        assertEquals(4, rollback.x());
        assertEquals(70, rollback.y());
        assertEquals(-8, rollback.z());
        assertEquals(ChangeAction.ROLLBACK, rollback.action());
        assertEquals("minecraft:diamond_block", rollback.beforeData());
        assertEquals("minecraft:stone", rollback.afterData());
        assertTrue(rollback.happenedAt() > 0L);

        BlockChange undo = RollbackAudit.create(
                job,
                block,
                "minecraft:stone",
                "minecraft:diamond_block",
                true
        );
        assertEquals("Admin [rollback #73 undo]", undo.actorName());
        assertEquals(ChangeAction.ROLLBACK, undo.action());

        byte[] beforeEntityData = new byte[]{1, 2, 3};
        byte[] afterEntityData = new byte[]{4, 5, 6};
        BlockChange entityAudit = RollbackAudit.create(job, block,
                "minecraft:chest", "minecraft:chest", beforeEntityData, afterEntityData, false);
        assertTrue(Arrays.equals(beforeEntityData, entityAudit.beforeEntityData()));
        assertTrue(Arrays.equals(afterEntityData, entityAudit.afterEntityData()));
    }

    @Test
    void suppressionIsNestedAndAlwaysRestored() {
        assertFalse(BlockLoggingSuppression.isSuppressed());
        AtomicBoolean nestedWasSuppressed = new AtomicBoolean();

        BlockLoggingSuppression.runSuppressed(() -> {
            assertTrue(BlockLoggingSuppression.isSuppressed());
            BlockLoggingSuppression.runSuppressed(() -> nestedWasSuppressed.set(BlockLoggingSuppression.isSuppressed()));
            assertTrue(BlockLoggingSuppression.isSuppressed());
        });

        assertTrue(nestedWasSuppressed.get());
        assertFalse(BlockLoggingSuppression.isSuppressed());

        assertThrows(IllegalStateException.class, () ->
                BlockLoggingSuppression.runSuppressed(() -> {
                    throw new IllegalStateException("boom");
                }));
        assertFalse(BlockLoggingSuppression.isSuppressed());
    }
}
