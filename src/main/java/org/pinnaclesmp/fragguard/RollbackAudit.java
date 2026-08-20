package org.pinnaclesmp.fragguard;

import org.bukkit.block.Block;

final class RollbackAudit {
    private RollbackAudit() {
    }

    static BlockChange create(RollbackJob job, Block block, String beforeData, String afterData, boolean undo) {
        String phase = undo ? " undo" : "";
        String actorLabel = job.actorName() + " [rollback #" + job.id() + phase + "]";
        return new BlockChange(
                System.currentTimeMillis(),
                job.actorUuid(),
                actorLabel,
                block.getWorld().getName(),
                block.getX(),
                block.getY(),
                block.getZ(),
                ChangeAction.ROLLBACK,
                beforeData,
                afterData
        );
    }
}
