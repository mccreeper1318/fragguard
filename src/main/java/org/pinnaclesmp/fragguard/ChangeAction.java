package org.pinnaclesmp.fragguard;

import java.util.HashMap;
import java.util.Map;

enum ChangeAction {
    PLACE("block.place", "PLACE"),
    BREAK("block.break", "BREAK"),
    EXPLOSION("block.explosion", "EXPLOSION"),
    FIRE_SPREAD("fire.spread", "FIRE_SPREAD"),
    FIRE_BURN("fire.burn", "FIRE_BURN"),
    LIQUID_PLACE("liquid.place", "LIQUID_PLACE"),
    LIQUID_REMOVE("liquid.remove", "LIQUID_REMOVE"),
    LIQUID_FLOW("liquid.flow", "LIQUID_FLOW"),
    LIQUID_BREAK("liquid.break", "LIQUID_BREAK"),
    PISTON_EXTEND("piston.extend", "PISTON_EXTEND"),
    PISTON_RETRACT("piston.retract", "PISTON_RETRACT"),
    PISTON_BREAK("piston.break", "PISTON_BREAK"),
    ROLLBACK("block.rollback", "ROLLBACK"),
    UNKNOWN("unknown", "UNKNOWN");

    private static final Map<String, ChangeAction> STORED_ACTIONS = createStoredActions();

    private final String storageId;
    private final String legacyStorageId;

    ChangeAction(String storageId, String legacyStorageId) {
        this.storageId = storageId;
        this.legacyStorageId = legacyStorageId;
    }

    String storageId() {
        return storageId;
    }

    String legacyStorageId() {
        return legacyStorageId;
    }

    static ChangeAction fromStorageId(String storageId) {
        return STORED_ACTIONS.getOrDefault(storageId, UNKNOWN);
    }

    private static Map<String, ChangeAction> createStoredActions() {
        Map<String, ChangeAction> storedActions = new HashMap<>();
        for (ChangeAction action : values()) {
            storedActions.put(action.storageId, action);
            storedActions.put(action.legacyStorageId, action);
        }
        return Map.copyOf(storedActions);
    }

    String displayPastTense() {
        return switch (this) {
            case PLACE -> "placed";
            case BREAK -> "destroyed";
            case EXPLOSION -> "exploded";
            case FIRE_SPREAD -> "spread";
            case FIRE_BURN -> "burned";
            case LIQUID_PLACE -> "placed liquid at";
            case LIQUID_REMOVE -> "removed liquid from";
            case LIQUID_FLOW -> "flowed into";
            case LIQUID_BREAK -> "destroyed";
            case PISTON_EXTEND -> "moved";
            case PISTON_RETRACT -> "moved";
            case PISTON_BREAK -> "destroyed";
            case ROLLBACK -> "rolled back";
            case UNKNOWN -> "changed";
        };
    }
}
