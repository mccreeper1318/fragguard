package org.pinnaclesmp.fragguard;

enum ChangeAction {
    PLACE,
    BREAK,
    EXPLOSION,
    FIRE_SPREAD,
    FIRE_BURN,
    LIQUID_PLACE,
    LIQUID_REMOVE,
    LIQUID_FLOW,
    LIQUID_BREAK,
    PISTON_EXTEND,
    PISTON_RETRACT,
    PISTON_BREAK;

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
        };
    }
}
