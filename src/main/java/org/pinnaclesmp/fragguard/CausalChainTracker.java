package org.pinnaclesmp.fragguard;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Main-thread-only, finite memory tracking of *observed* player-to-mechanism
 * links. No nearest-player, elapsed-time-only, or radius-only ownership guesses.
 *
 * A player may seed a supported switch/lever/redstone part. A piston can inherit
 * ownership only from a connected, active component and transfers that link to
 * blocks it physically moves. TNT can inherit from an observed priming entity,
 * a directly linked priming block, or a just-observed priming coordinate.
 * Ambiguous multiple-player links never resolve to either player.
 */
final class CausalChainTracker {
    static final long IDLE_MILLIS = 4_000L;
    static final long MAX_AGE_MILLIS = 30_000L;
    static final long PRIME_MATCH_MILLIS = 500L;
    static final long TNT_MOVEMENT_MATCH_MILLIS = 500L;
    // An observed player switch-off may power down a redstone chain before a
    // piston retracts. Never let unrelated later falling edges refresh it.
    static final long FALLING_EDGE_HANDOFF_MILLIS = 500L;
    // An observed TNT entity is a concrete cause even with a slower-than-20 TPS fuse.
    static final long TNT_ENTITY_LIFETIME_MILLIS = 60_000L;
    static final int MAX_BLOCK_LINKS = 2_048;
    static final int MAX_ENTITY_LINKS = 512;
    static final int MAX_PRIME_LINKS = 256;
    static final int MAX_TNT_MOVEMENT_LINKS = 256;
    static final int MAX_DISPLACEMENT = 96;

    private final Map<Position, Link> blocks = new HashMap<>();
    private final Map<UUID, Link> entities = new HashMap<>();
    private final Map<Position, Link> recentPrimes = new HashMap<>();
    // Only positions of TNT blocks actually observed moving with a piston.
    // A linked piston moving stone or slime must never authorize TNT entities.
    private final Map<Position, Link> recentMovedTnt = new HashMap<>();

    record Position(String world, int x, int y, int z) {
        Position shift(int dx, int dy, int dz) {
            return new Position(world, x + dx, y + dy, z + dz);
        }
    }

    private record Link(CausalAttribution attribution, long createdAt, long lastObserved,
                        Position origin, long lastDirectActivationAt) {
        Link touch(long now, String cause) {
            return new Link(new CausalAttribution(attribution.playerUuid(),
                    attribution.playerName(), attribution.chainId(), cause),
                    createdAt, now, origin, lastDirectActivationAt);
        }

        Link directlyActivated(long now) {
            Link refreshed = touch(now, "PLAYER_ACTIVATION");
            return new Link(refreshed.attribution, refreshed.createdAt, now,
                    refreshed.origin, now);
        }
    }

    void playerActivated(Position position, String uuid, String name, long now) {
        sweep(now);
        // Direct intervention at a known component ends the old session,
        // including its tracked TNT entities. No spatial ownership is inferred.
        Set<String> superseded = new HashSet<>();
        for (Position neighbor : connected(position)) {
            Link previous = blocks.get(neighbor);
            if (previous != null && !previous.attribution.playerUuid().equals(uuid)) {
                superseded.add(previous.attribution.chainId());
            }
        }
        for (String id : superseded) {
            invalidateChain(id);
        }
        Link existing = blocks.get(position);
        if (existing != null && existing.attribution.playerUuid().equals(uuid) && active(existing, position, now)) {
            blocks.put(position, existing.directlyActivated(now));
            return; // Repeat pressure-plate events must not split the same causal chain.
        }
        if (blocks.size() >= MAX_BLOCK_LINKS) {
            clear(); // fail closed under load, never evict into arbitrary ownership
        }
        blocks.put(position, new Link(new CausalAttribution(uuid, name, UUID.randomUUID().toString(),
                "PLAYER_ACTIVATION"), now, now, position, now));
    }

    void playerModified(Position position, String uuid, String name, long now,
                        boolean isMechanismComponent) {
        sweep(now);
        Set<String> superseded = new HashSet<>();
        for (Position neighbor : connected(position)) {
            Link previous = blocks.get(neighbor);
            if (previous != null) {
                superseded.add(previous.attribution.chainId());
            }
        }
        for (String id : superseded) {
            invalidateChain(id);
        }
        if (isMechanismComponent) {
            playerActivated(position, uuid, name, now);
        } else {
            blocks.remove(position);
        }
    }

    /** Only extend through a source block already linked to a player. */
    CausalAttribution redstone(Position source, long now, boolean powered) {
        sweep(now);
        if (!powered) {
            // The player is the *observed* cause of this falling edge. Keep
            // their just-refreshed activation through connected power-off
            // updates so the subsequent piston retraction can inherit it.
            // Never revive an old/ambiguous machine or an unobserved switch.
            Link selected = uniqueConnected(source, now);
            if (selected != null) {
                for (Position adjacent : connected(source)) {
                    Link candidate = blocks.get(adjacent);
                    if (active(candidate, source, now)
                            && candidate.attribution.chainId().equals(selected.attribution.chainId())
                            && freshFallingEdge(candidate, now)) {
                        remember(source, candidate.touch(now, "REDSTONE_OFF"), now);
                        return candidate.attribution;
                    }
                }
            }
            blocks.remove(source);
            return null;
        }
        Link link = uniqueConnected(source, now);
        if (link == null) {
            return null;
        }
        remember(source, link.touch(now, "REDSTONE"), now);
        return link.attribution;
    }

    CausalAttribution piston(Position piston, List<Position> moved,
                            int dx, int dy, int dz, long now) {
        return piston(piston, moved, List.of(), dx, dy, dz, now);
    }

    /** TNT evidence comes from moved blocks whose material was actually TNT. */
    CausalAttribution piston(Position piston, List<Position> moved, List<Position> movedTnt,
                            int dx, int dy, int dz, long now) {
        sweep(now);
        Link link = uniqueConnected(piston, now);
        if (link == null) {
            return null;
        }
        List<Position> touched = new ArrayList<>();
        touched.add(piston);
        touched.add(piston.shift(dx, dy, dz));
        for (Position position : moved) {
            touched.add(position);
            touched.add(position.shift(dx, dy, dz));
        }
        if ((long) blocks.size() + touched.size() >= MAX_BLOCK_LINKS) {
            clear(); // No partial machine ownership after capacity exhaustion.
            return null;
        }
        // Conflicting active ownership anywhere in the actual moved set
        // invalidates both sessions rather than attributing by proximity.
        Set<String> conflict = new HashSet<>();
        for (Position position : touched) {
            Link prior = blocks.get(position);
            if (prior != null && !prior.attribution.chainId().equals(link.attribution.chainId())) {
                conflict.add(prior.attribution.chainId());
            }
        }
        if (!conflict.isEmpty()) {
            conflict.add(link.attribution.chainId());
            for (String chain : conflict) {
                invalidateChain(chain);
            }
            return null;
        }
        for (Position position : touched) {
            remember(position, link.touch(now, "PISTON"), now);
        }
        // A TNT duper may spawn primed TNT at the original or destination
        // coordinate of a TNT block moved in this exact piston event. Only
        // actual TNT materials may seed this brief, one-shot handoff.
        for (Position tntSource : movedTnt) {
            if (!moved.contains(tntSource)) {
                continue;
            }
            if (recentMovedTnt.size() + 2 > MAX_TNT_MOVEMENT_LINKS) {
                recentMovedTnt.clear(); // Do not infer ownership from an overflow.
                break;
            }
            Link tntEvidence = link.touch(now, "PISTON_TNT");
            recentMovedTnt.put(tntSource, tntEvidence);
            recentMovedTnt.put(tntSource.shift(dx, dy, dz), tntEvidence);
        }
        return link.attribution;
    }

    CausalAttribution observedAt(Position physicalSource, long now, String cause) {
        sweep(now);
        Link link = uniqueConnected(physicalSource, now);
        return link == null ? null : link.touch(now, cause).attribution;
    }

    CausalAttribution prime(Position tntBlock, Position primingBlock,
                            UUID primingEntity, CausalAttribution directPlayer, long now) {
        sweep(now);
        Link causal;
        // Preserve the original chain for a TNT -> TNT prime, even when
        // Paper's recursive getSource() also resolves to the original player.
        causal = primingEntity == null ? null : entities.get(primingEntity);
        if (causal == null && directPlayer != null) {
            causal = new Link(directPlayer, now, now, tntBlock, -1L);
        }
        if (causal == null && primingBlock != null) {
            causal = blocks.get(primingBlock);
        }
        if (causal == null || !(primingEntity != null && entities.get(primingEntity) == causal
                ? entityActive(causal, now) && withinBounds(causal.origin, tntBlock)
                : active(causal, tntBlock, now))) {
            return null;
        }
        CausalAttribution result = causal.touch(now, "TNT_PRIME").attribution;
        if (recentPrimes.size() >= MAX_PRIME_LINKS) {
            recentPrimes.clear();
        }
        // Prime is a new observed physical event: reset its short handoff
        // window even when the source TNT burned a long fuse.
        recentPrimes.put(tntBlock, new Link(result, now, now, tntBlock, -1L));
        return result;
    }

    /** TNT entities spawned at an observed priming position, never merely nearby. */
    CausalAttribution spawnedTnt(Position position, UUID entityId, long now) {
        sweep(now);
        Link link = recentPrimes.remove(position);
        if (link == null || now - link.lastObserved > PRIME_MATCH_MILLIS || !active(link, position, now)) {
            // A TNT duper needs observed movement of an actual TNT block.
            // Never infer TNT ownership from any ordinary piston-touched tile.
            link = recentMovedTnt.remove(position);
            if (link == null || now - link.lastObserved > TNT_MOVEMENT_MATCH_MILLIS
                    || !active(link, position, now)) {
                return null;
            }
        }
        if (entities.size() >= MAX_ENTITY_LINKS) {
            entities.clear();
            return null;
        }
        link = new Link(new CausalAttribution(link.attribution.playerUuid(),
                link.attribution.playerName(), link.attribution.chainId(), "TNT_ENTITY"),
                now, now, position, -1L);
        entities.put(entityId, link);
        return link.attribution;
    }

    CausalAttribution entityCause(UUID entityId, long now) {
        sweep(now);
        Link link = entities.get(entityId);
        return entityActive(link, now) ? link.attribution : null;
    }

    /** Explicit source-to-TNT entity chain, even if no prime event was received. */
    CausalAttribution chainedEntity(UUID source, UUID child, Position position, long now) {
        sweep(now);
        Link link = entities.get(source);
        if (!entityActive(link, now) || !withinBounds(link.origin, position)
                || entities.size() >= MAX_ENTITY_LINKS) {
            return null;
        }
        Link childLink = new Link(new CausalAttribution(link.attribution.playerUuid(),
                link.attribution.playerName(), link.attribution.chainId(), "TNT_CHAIN"),
                now, now, position, -1L);
        entities.put(child, childLink);
        return childLink.attribution;
    }

    void unloadWorld(String world) {
        blocks.keySet().removeIf(p -> p.world.equals(world));
        recentPrimes.keySet().removeIf(p -> p.world.equals(world));
        recentMovedTnt.keySet().removeIf(p -> p.world.equals(world));
        entities.values().removeIf(link -> link.origin.world.equals(world));
    }

    void clear() {
        blocks.clear();
        entities.clear();
        recentPrimes.clear();
        recentMovedTnt.clear();
    }

    int trackedCount() {
        return blocks.size() + entities.size() + recentPrimes.size() + recentMovedTnt.size();
    }

    private void remember(Position position, Link link, long now) {
        if (!active(link, position, now)) {
            return;
        }
        if (blocks.size() >= MAX_BLOCK_LINKS) {
            clear();
            return;
        }
        blocks.put(position, link);
    }

    private Link uniqueConnected(Position at, long now) {
        Link result = null;
        for (Position neighbor : connected(at)) {
            Link candidate = blocks.get(neighbor);
            if (!active(candidate, at, now)) {
                continue;
            }
            if (result != null && !result.attribution.chainId().equals(candidate.attribution.chainId())) {
                String first = result.attribution.chainId();
                String second = candidate.attribution.chainId();
                invalidateChain(first);
                invalidateChain(second);
                return null; // ambiguous ownership invalidates both sessions
            }
            result = candidate;
        }
        return result;
    }

    private static List<Position> connected(Position pos) {
        return List.of(pos, pos.shift(1, 0, 0), pos.shift(-1, 0, 0),
                pos.shift(0, 1, 0), pos.shift(0, -1, 0),
                pos.shift(0, 0, 1), pos.shift(0, 0, -1));
    }

    private static boolean freshFallingEdge(Link link, long now) {
        return link != null && link.lastDirectActivationAt >= 0
                && now >= link.lastDirectActivationAt
                && now - link.lastDirectActivationAt <= FALLING_EDGE_HANDOFF_MILLIS;
    }

    private static boolean active(Link link, Position at, long now) {
        if (link == null || now < link.createdAt || now - link.createdAt > MAX_AGE_MILLIS
                || now - link.lastObserved > IDLE_MILLIS
                || ("REDSTONE_OFF".equals(link.attribution.causeType())
                    && !freshFallingEdge(link, now))) {
            return false;
        }
        Position origin = link.origin;
        return at == null || (origin.world.equals(at.world)
                && Math.abs((long) at.x - origin.x) <= MAX_DISPLACEMENT
                && Math.abs((long) at.y - origin.y) <= MAX_DISPLACEMENT
                && Math.abs((long) at.z - origin.z) <= MAX_DISPLACEMENT);
    }

    private static boolean entityActive(Link link, long now) {
        return link != null && now >= link.createdAt
                && now - link.createdAt <= TNT_ENTITY_LIFETIME_MILLIS;
    }

    private static boolean withinBounds(Position origin, Position at) {
        return origin.world.equals(at.world)
                && Math.abs((long) at.x - origin.x) <= MAX_DISPLACEMENT
                && Math.abs((long) at.y - origin.y) <= MAX_DISPLACEMENT
                && Math.abs((long) at.z - origin.z) <= MAX_DISPLACEMENT;
    }

    private void invalidateChain(String chainId) {
        blocks.values().removeIf(link -> link.attribution.chainId().equals(chainId));
        recentPrimes.values().removeIf(link -> link.attribution.chainId().equals(chainId));
        recentMovedTnt.values().removeIf(link -> link.attribution.chainId().equals(chainId));
        entities.values().removeIf(link -> link.attribution.chainId().equals(chainId));
    }

    private void sweep(long now) {
        blocks.entrySet().removeIf(entry -> !active(entry.getValue(), entry.getKey(), now));
        recentPrimes.entrySet().removeIf(entry -> !active(entry.getValue(), entry.getKey(), now)
                || now - entry.getValue().lastObserved > PRIME_MATCH_MILLIS);
        recentMovedTnt.entrySet().removeIf(entry -> !active(entry.getValue(), entry.getKey(), now)
                || now - entry.getValue().lastObserved > TNT_MOVEMENT_MATCH_MILLIS);
        entities.values().removeIf(link -> !entityActive(link, now));
    }
}
