# FragGuard 26.3-1.2.1 — Storage-efficient historical actor indexes (#139)

## Why the change was needed

The original schema-v4→v5 migration built three indexes containing every block event, including entries recorded with the short non-player actor label `SYSTEM`. All index definitions were created in a **single transaction** on an existing 57-million-row production database, resulting in `SQLITE_FULL` even after a verified compaction to 17.03 GiB.

This patch reduces the number of indexed records and splits index creation into independently committed stages. It **does not prove the root cause** of the hosting restriction and does not guarantee a successful migration with an unknown account quota or SQLite native temp-volume limit; #138 diagnostics and #140 storage preflight remain essential.

## New actor index predicate

The three indexes now use the same partial predicate:

```sql
WHERE length(actor_uuid) IN (32, 36)
```

Historical Minecraft player UUIDs are accepted by the resolver as **32-character compact** or **36-character hyphenated** representations. Short system/environmental actor labels cannot identify a player, so those events need not appear in the direct-player indexes. Player lookup and rollback SQL explicitly include the identical predicate in their **direct actor branch**, while the OR branch for `causal_player_uuid` remains untouched.

This means:
- Normal player UUID/name lookup and newest-name resolution remain indexed.
- World-scoped direct-player history can use the `actor_uuid, world_uuid, happened_at, id` index.
- Non-player system events are **still stored and returned by radius lookup**, and system-caused changes attributed through `causal_player_uuid` remain accessible through the separate causal indexes.
- Existing retained block history, rollback rows, and world states are **never rewritten or deleted** by the change.
- The predicate intentionally filters to syntactically possible UUID lengths; it does not attempt to prove a UUID belongs to a player. UUID-shaped entity identifiers may still appear, which is acceptable because the resolver validates historical identities.

## Migration stages and restart safety

In schema v4→v5, the three index statements are now independent autocommit operations. After **each completed index**, FG requests `PRAGMA wal_checkpoint(TRUNCATE)` before beginning another index. This avoids the old all-index transaction holding every new index's WAL pages simultaneously.

The `user_version` stays **4** throughout index creation; only after all indexes have finished and checkpointed successfully does FG advance the schema to **5**. If an index or checkpoint fails:

1. Startup fails closed and does not permit FG to operate with an incomplete migration.
2. Existing data and the version-4 schema remain intact.
3. Completed indexes may remain, which is safe for schema-v4 readers; the next attempt skips them with `CREATE INDEX IF NOT EXISTS`.
4. The next attempt starts at the still-missing index, avoiding repeated disk/temp work for already-completed indexes.

A busy checkpoint is treated as a **safe startup failure** rather than continuing to grow the WAL. The application should not operate against the database until the active reader has released the checkpoint lock.

No full database backup is created for this metadata/index-only migration. Operators should retain verified backups separately and never manually remove `-wal`/`-shm` files.

## Query plan and fixture checks

Tests and fixture analysis must validate the following query families:

| Query family | Expected index |
| --- | --- |
| Direct player newest history by UUID/time | `idx_fg_actor_time` |
| Direct player world-scoped history by UUID/world/time | `idx_fg_actor_world_time` |
| Historical username → UUID resolution | `idx_fg_actor_name_uuid` |
| Indirect player event attribution | existing `idx_fg_causal_*` indexes |
| Radius lookup and 30-day retention | existing area/time indexes |

`EXPLAIN QUERY PLAN` tests force each new partial index with `INDEXED BY` to prove eligibility. The automated fixture compares page allocation with the former full-index SQL on identical **SYSTEM-heavy** synthetic data; results are intentionally dataset-dependent. For the integrated release validation (#141), report actual peak database/WAL/temp usage, time, and query plans on representative large data.

## Remaining work and release safety

- #140 will add a conservative migration-storage preflight and safer host/quota error reporting.
- #141 will test realistic-scale v4→v9 migration behavior and failure/restart scenarios.
- Final live Paper server migration and gameplay tests remain postponed to the end of the 26.3-1.2.1 release.

**Keep the production server on FG 26.3-1.2.0 for now.** Do not install an issue-branch development JAR against the production database.
