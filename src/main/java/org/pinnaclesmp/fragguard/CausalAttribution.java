package org.pinnaclesmp.fragguard;

/**
 * Optional causal ownership, distinct from the physical actor stored in
 * actor_uuid/actor_name. Null means that no player can be proven responsible.
 * chainId is an ephemeral per-activation identifier; it never resumes after restart.
 */
record CausalAttribution(String playerUuid, String playerName, String chainId, String causeType) {
    CausalAttribution {
        if (playerUuid == null || playerUuid.isBlank() || chainId == null || chainId.isBlank()
                || causeType == null || causeType.isBlank()) {
            throw new IllegalArgumentException("Incomplete causal attribution");
        }
    }
}
