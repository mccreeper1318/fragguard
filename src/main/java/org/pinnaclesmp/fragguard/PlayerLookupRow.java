package org.pinnaclesmp.fragguard;

/** Lightweight history row: neither container inventory nor block-entity BLOB is loaded. */
record PlayerLookupRow(
        long id,
        long happenedAt,
        String actorUuid,
        String actorName,
        String causalPlayerUuid,
        String causalPlayerName,
        String causalCauseType,
        String worldName,
        int x, int y, int z,
        ChangeAction action,
        String beforeData,
        String afterData,
        boolean blockEntityDataPresent
) {
    /** Only non-direct history is described as indirectly caused by the player. */
    boolean indirectlyAttributedTo(String selectedPlayerUuid) {
        return selectedPlayerUuid.equals(causalPlayerUuid)
                && !selectedPlayerUuid.equals(actorUuid);
    }
}
