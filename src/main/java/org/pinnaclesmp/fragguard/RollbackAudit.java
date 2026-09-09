package org.pinnaclesmp.fragguard;

import org.bukkit.block.Block;

final class RollbackAudit {
    private RollbackAudit() {
    }

    static BlockChange create(RollbackJob job, Block block, String beforeData, String afterData, boolean undo) {
        return create(job, block, beforeData, afterData, null, null, undo);
    }

    static BlockChange create(RollbackJob job, Block block, String beforeData, String afterData,
                              byte[] beforeEntityData, byte[] afterEntityData, boolean undo) {
        return create(
                job,
                block.getWorld().getName(),
                block.getX(),
                block.getY(),
                block.getZ(),
                beforeData,
                afterData,
                beforeEntityData,
                afterEntityData,
                undo
        );
    }

    static BlockChange create(RollbackJob job, String worldName, int x, int y, int z,
                              String beforeData, String afterData, boolean undo) {
        return create(job, worldName, x, y, z, beforeData, afterData, null, null, undo);
    }

    static BlockChange create(RollbackJob job, String worldName, int x, int y, int z,
                              String beforeData, String afterData, byte[] beforeEntityData,
                              byte[] afterEntityData, boolean undo) {
        String phase = undo ? " undo" : "";
        String actorLabel = job.actorName() + " [rollback #" + job.id() + phase + "]";
        return new BlockChange(
                System.currentTimeMillis(),
                job.actorUuid(),
                actorLabel,
                worldName,
                x,
                y,
                z,
                ChangeAction.ROLLBACK,
                beforeData,
                afterData,
                beforeEntityData,
                afterEntityData
        );
    }
}
