package org.pinnaclesmp.fragguard;

import java.util.Objects;

/**
 * Streams one block coordinate's retained history newest-first. Only the
 * uninterrupted newest suffix authored/caused by the selected UUID can be
 * reverted. A system/other-player row is an unconditional ownership boundary,
 * even if a later execution uses "force".
 *
 * No BLOB history is collected: each scanner only retains the newest after
 * snapshot, the earliest safe before snapshot, and one comparison state.
 */
final class SelectivePlayerRollbackPlanner {
    static final int MAX_HISTORY_ROWS_PER_COORDINATE = 8_192;

    enum Status {
        REVERSIBLE,
        HISTORICAL_CONFLICT,
        NO_OP,
        UNAVAILABLE
    }

    record HistoryRow(long id, String actorUuid, String causalPlayerUuid,
                      String worldName, String beforeData, String afterData,
                      byte[] beforeEntityData, byte[] afterEntityData) {
        boolean belongsTo(String playerUuid) {
            return playerUuid.equals(actorUuid) || playerUuid.equals(causalPlayerUuid);
        }
    }

    record Decision(Status status, RollbackTarget target, boolean protectedOlderChanges) {
        Decision {
            if ((status == Status.REVERSIBLE) != (target != null)) {
                throw new IllegalArgumentException("Only reversible decisions carry rollback targets.");
            }
        }
    }

    static final class CoordinateScanner {
        private final String playerUuid;
        private final int x;
        private final int y;
        private final int z;

        private String worldName;
        private String desiredBlock;
        private byte[] desiredEntity;
        private String expectedBlock;
        private byte[] expectedEntity;
        private String nextNewerBeforeBlock;
        private byte[] nextNewerBeforeEntity;
        private boolean hasOwnedSuffix;
        private boolean encounteredBoundary;
        private boolean protectedOlderChanges;
        private boolean invalidChain;
        private int rows;

        CoordinateScanner(String playerUuid, int x, int y, int z) {
            this.playerUuid = Objects.requireNonNull(playerUuid);
            this.x = x;
            this.y = y;
            this.z = z;
        }

        void add(HistoryRow row) {
            if (++rows > MAX_HISTORY_ROWS_PER_COORDINATE) {
                throw new HistoryLimitExceededException();
            }
            if (worldName == null) {
                worldName = row.worldName();
            }
            boolean belongs = row.belongsTo(playerUuid);
            if (encounteredBoundary) {
                if (belongs) {
                    protectedOlderChanges = true;
                }
                return;
            }

            if (!belongs) {
                encounteredBoundary = true;
                return;
            }
            if (!hasOwnedSuffix) {
                // Newest recorded change establishes the expected live state.
                expectedBlock = row.afterData();
                expectedEntity = row.afterEntityData();
                hasOwnedSuffix = true;
            } else if (!sameState(row.afterData(), row.afterEntityData(),
                    nextNewerBeforeBlock, nextNewerBeforeEntity)) {
                // Missing/intervening history, including container snapshots,
                // means that this suffix cannot be proven reversible.
                invalidChain = true;
            }
            desiredBlock = row.beforeData();
            desiredEntity = row.beforeEntityData();
            nextNewerBeforeBlock = row.beforeData();
            nextNewerBeforeEntity = row.beforeEntityData();
        }

        Decision finish() {
            if (invalidChain || worldName == null) {
                return new Decision(Status.UNAVAILABLE, null, protectedOlderChanges);
            }
            if (!hasOwnedSuffix) {
                return new Decision(protectedOlderChanges
                        ? Status.HISTORICAL_CONFLICT : Status.UNAVAILABLE,
                        null, protectedOlderChanges);
            }
            // A legacy record may have lost the inventory/sign/entity payload.
            // Do not infer that missing snapshots mean an empty container.
            if (unknownEntityState(desiredBlock, desiredEntity)
                    || unknownEntityState(expectedBlock, expectedEntity)) {
                return new Decision(Status.UNAVAILABLE, null, protectedOlderChanges);
            }
            if (sameState(desiredBlock, desiredEntity, expectedBlock, expectedEntity)) {
                return new Decision(Status.NO_OP, null, protectedOlderChanges);
            }
            return new Decision(Status.REVERSIBLE, new RollbackTarget(worldName, x, y, z,
                    desiredBlock, expectedBlock, desiredEntity, expectedEntity),
                    protectedOlderChanges);
        }
    }

    static boolean sameState(String firstBlock, byte[] firstEntity,
                             String secondBlock, byte[] secondEntity) {
        return Objects.equals(firstBlock, secondBlock)
                && BlockEntitySnapshot.equivalent(firstEntity, secondEntity);
    }

    /** Supported block-entity families without historical payloads fail closed. */
    private static boolean unknownEntityState(String block, byte[] payload) {
        if (payload != null || block == null) {
            return false;
        }
        int bracket = block.indexOf('[');
        String material = (bracket >= 0 ? block.substring(0, bracket) : block)
                .replace("minecraft:", "");
        return material.endsWith("_sign") || material.endsWith("_banner")
                || material.endsWith("_head") || material.endsWith("_skull")
                || material.endsWith("_shulker_box")
                || switch (material) {
                    case "chest", "trapped_chest", "barrel", "hopper", "furnace",
                         "blast_furnace", "smoker", "dropper", "dispenser",
                         "brewing_stand", "lectern", "decorated_pot",
                         "chiseled_bookshelf", "crafter", "beehive", "bee_nest" -> true;
                    default -> false;
                };
    }

    static final class HistoryLimitExceededException extends IllegalStateException {
        HistoryLimitExceededException() {
            super("Too many history events at one coordinate to verify a safe player rollback.");
        }
    }
}
