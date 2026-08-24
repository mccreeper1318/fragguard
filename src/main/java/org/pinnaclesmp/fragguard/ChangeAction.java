package org.pinnaclesmp.fragguard;

import java.util.HashMap;
import java.util.Map;

enum ChangeAction {
    PLACE("block.place", "PLACE"),
    BREAK("block.break", "BREAK"),
    EXPLOSION("block.explosion", "EXPLOSION"),
    FIRE_SPREAD("fire.spread", "FIRE_SPREAD"),
    FIRE_BURN("fire.burn", "FIRE_BURN"),
    FIRE_IGNITE("fire.ignite", "FIRE_IGNITE"),
    LIQUID_PLACE("liquid.place", "LIQUID_PLACE"),
    LIQUID_REMOVE("liquid.remove", "LIQUID_REMOVE"),
    LIQUID_FLOW("liquid.flow", "LIQUID_FLOW"),
    LIQUID_BREAK("liquid.break", "LIQUID_BREAK"),
    SPONGE_ABSORB("liquid.sponge_absorb", "SPONGE_ABSORB"),
    DISPENSER_LIQUID_PLACE("liquid.dispenser_place", "DISPENSER_LIQUID_PLACE"),
    DISPENSER_LIQUID_REMOVE("liquid.dispenser_remove", "DISPENSER_LIQUID_REMOVE"),
    PISTON_EXTEND("piston.extend", "PISTON_EXTEND"),
    PISTON_RETRACT("piston.retract", "PISTON_RETRACT"),
    PISTON_BREAK("piston.break", "PISTON_BREAK"),
    BLOCK_GROW("block.grow", "BLOCK_GROW"),
    BLOCK_FADE("block.fade", "BLOCK_FADE"),
    BLOCK_FORM("block.form", "BLOCK_FORM"),
    BLOCK_SPREAD("block.spread", "BLOCK_SPREAD"),
    LEAVES_DECAY("block.leaves_decay", "LEAVES_DECAY"),
    STRUCTURE_GROW("block.structure_grow", "STRUCTURE_GROW"),
    FERTILIZE("block.fertilize", "FERTILIZE"),
    ENTITY_CHANGE_BLOCK("block.entity_change", "ENTITY_CHANGE_BLOCK"),
    ENTITY_BLOCK_FORM("block.entity_form", "ENTITY_BLOCK_FORM"),
    PLAYER_INTERACT("block.player_interact", "PLAYER_INTERACT"),
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
            case FIRE_IGNITE -> "ignited";
            case LIQUID_PLACE -> "placed liquid at";
            case LIQUID_REMOVE -> "removed liquid from";
            case LIQUID_FLOW -> "flowed into";
            case LIQUID_BREAK -> "destroyed";
            case SPONGE_ABSORB -> "absorbed water from";
            case DISPENSER_LIQUID_PLACE -> "dispensed liquid into";
            case DISPENSER_LIQUID_REMOVE -> "removed liquid from";
            case PISTON_EXTEND -> "moved";
            case PISTON_RETRACT -> "moved";
            case PISTON_BREAK -> "destroyed";
            case BLOCK_GROW -> "grew";
            case BLOCK_FADE -> "faded";
            case BLOCK_FORM -> "formed";
            case BLOCK_SPREAD -> "spread";
            case LEAVES_DECAY -> "decayed";
            case STRUCTURE_GROW -> "grew";
            case FERTILIZE -> "fertilized";
            case ENTITY_CHANGE_BLOCK -> "changed";
            case ENTITY_BLOCK_FORM -> "formed";
            case PLAYER_INTERACT -> "interacted with";
            case ROLLBACK -> "rolled back";
            case UNKNOWN -> "changed";
        };
    }
}
