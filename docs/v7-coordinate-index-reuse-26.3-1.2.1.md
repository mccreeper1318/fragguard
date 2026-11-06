# FragGuard 26.3-1.2.1 — Storage-safe v7→v8 coordinate migration (#148)

## What failed on Beta 2

On November 6, 2026, a 17.03 GiB history database (~57,009,208 changes) successfully reached **schema v7** before a new, almost-all-history `idx_fg_coordinate_time` failed with `SQLITE_FULL`. The legacy index creation was:

```sql
CREATE INDEX IF NOT EXISTS idx_fg_coordinate_time
ON block_changes(y, world_uuid, x, z, happened_at DESC, id DESC)
WHERE rollback_pending = 0;
```

This statement required sorting and indexing almost the entire history. The 1 GiB Java temp filesystem reported by the host is not proof of SQLite's actual sort-temp location; account and native temp quotas remain unknown (#147). The owner has **no verified pre-upgrade schema-v4 backup**. A safe path must be a non-destructive forward migration from schema v7.

## New approach — no second full-history index

FG already maintains:

```sql
CREATE INDEX idx_fg_spatial_time
ON block_changes(world_uuid, chunk_x, chunk_z, happened_at);
```

These indexed fields exist and are populated in the legacy v4 database. A block coordinate uniquely determines its chunk:

```
chunk_x = x >> 4
chunk_z = z >> 4
```

The v7→v8 migration now performs only **read-only validation** of that existing index and the persisted chunk coordinates, then advances `user_version`. It **does not** create, rebuild or drop any global coordinate index, delete or rewrite history, or change retained snapshots/jobs. The v7 stage's estimated write headroom is adjusted from full-index space to a 128 MiB metadata minimum (plus the configured margin).

### Safety conditions before advancing schema

- Verify `idx_fg_spatial_time` exists and contains, in order: `world_uuid`, `chunk_x`, `chunk_z`, `happened_at`.
- Scan historical rows **read-only** to detect any saved chunk inconsistent with `x >> 4` or `z >> 4`. Include negative Minecraft coordinates.
- If the index is missing or a mismatch is found, **stop startup** and leave the v7 schema/data untouched rather than risk silently skipping history. Operators should investigate on an isolated copy, not edit `user_version`.
- On a normal database, no huge `CREATE INDEX` is run. Only a small schema-version metadata transaction is committed. If that transaction fails, migration remains incomplete and can be retried safely.

The consistency scan reads all retained rows. On a 57-million-row database it can take time, but unlike `CREATE INDEX`, it does not allocate another multi-gigabyte index or external sort. It may still generate I/O load and must be tested on a representative **non-production** copy.

### Exact rollback queries

The two safety-sensitive paths use the existing index by adding `chunk_x = ? AND chunk_z = ?`, computed from the specific block coordinate:

1. Player rollback history reconstruction: exact `world_uuid`, chunk, `x/y/z`, `rollback_pending=0`, time cutoff, newest-first `ORDER BY happened_at DESC, id DESC` and bounded limit.
2. Player rollback job confirmation: exact world/chunk/coordinate and changes after the preview's snapshot timestamp, preventing stale edits from being overwritten.

**All physical actors remain included**, including other players, world events, system damage and causally attributed TNT. The new query conditions narrow to the correct chunk but never filter by player identity, preserving historical conflict boundaries.

The existing index stores each history row's rowid after the indexed fields; SQLite can traverse the matching chunk/time entries in descending timestamp and ID order. Automated `EXPLAIN QUERY PLAN` checks prove index eligibility without a global temp B-tree. Even if a world/chunk contains many repeated edits, result semantics remain unchanged. Performance may differ from the proposed per-coordinate index for very active chunks; release testing must measure it and retain existing per-coordinate row/query deadlines.

## Compatibility and failure handling

- Existing v7 databases from the failed Beta 2 rollout can proceed through v7→v8 and v8→v9 **without** a v4 restore, provided all migration checks pass.
- New v4→v9 upgrades also avoid building the global coordinate index.
- Databases that already contain `idx_fg_coordinate_time` from earlier successful builds remain compatible. This patch **does not drop** their index automatically.
- The #147 synthetic `SQLITE_FULL` regression remains in place as a historical reproduction of Beta 2's old DDL, but the new v7 upgrade does not execute it.
- A new failure must not be worked around by hand-editing `PRAGMA user_version`, deleting `-wal`/`-shm`, or overwriting the only database copy.

## Validation (CI and staged release gates)

- [ ] JUnit: no global index created during v7→v8, even with a synthetic `max_page_count` limited to one spare page.
- [ ] JUnit: real startup from v7 and from a v4 seed reaches v9, retains source rows and rollback job/snapshot metadata, and passes `integrity_check`.
- [ ] JUnit: `EXPLAIN QUERY PLAN` for timeline and stale-preview queries uses `idx_fg_spatial_time`; no unbounded sort is required for the ordered history query.
- [ ] JUnit: cross-world filtering, negative chunk coordinates, same-time ID ordering, system and other-player history, pending audit rows, stale job protection and selective rollback safety.
- [ ] JUnit: missing legacy spatial index or mismatched chunk values fails closed without modifying or dropping history.

**Still required:** on a safe, coherent **non-production copy of the existing v7 database**, verify exact row counts, `integrity_check`, stage durations, query plans and rollback behavior, plus actual host/native-temp and quota information (#147/#151). The test suite cannot prove large-server performance or capacity. No production migration has been attempted by this PR; all final Paper server tests remain at the end of 26.3-1.2.1, after #149/#150/#151.

Keep release PR #146 open and unmerged.
