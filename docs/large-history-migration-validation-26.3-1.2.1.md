# FragGuard 26.3-1.2.1 — Large-history migration release gate (#141)

**Status:** Automated/integration validation underway. **Not permission to perform the live-server upgrade.**

## Why this test exists

A populated schema-v4 database with 57,009,208 historical changes failed to upgrade to FG 26.3-1.2.1 because of `SQLITE_FULL`. A guarded `VACUUM INTO` reduced it from 85.11 GiB to a verified 17.03 GiB, but v4→v5 still failed. FG 26.3-1.2.0 starts against the compacted schema-v4 database. The managed-host account write quota and native SQLite temp capacity remain **unknown**.

Issues #138–#140 introduced per-index diagnostics, selective/restartable historical indexes and storage-aware preflight. #141 validates their interaction with real old-schema fixtures before the release.

## Automated CI — genuine version-4 database

`LargeHistoryMigrationIntegrationTest` seeds **actual v4-shaped schema tables** based on the FG 26.3-1.2.0 source. It does **not** merely set `PRAGMA user_version=4` on a modern v9 database.

The default fixture has **30,002 history rows**: 30,000 environmental/system events and two UUID-authored player rows (two worlds, historical username change, a block-entity BLOB). A legacy completed radius rollback job and its preserved snapshots are included.

The test runs FG's real `Database.init()` upgrade from schema **v4 through v5, v6, v7, v8, v9**. Checks:

- [ ] SQLite `PRAGMA integrity_check(1) = ok` and `user_version=9`.
- [ ] History count, retained direct-player rows, original block-entity BLOB, rollback job/snapshot and world UUID mapping preserved.
- [ ] All three direct-actor, three causal and coordinate/chunk indexes created.
- [ ] `EXPLAIN QUERY PLAN` uses the selective actor/time, actor/world/time and actor-name indexes for matching queries.
- [ ] Historical username resolves to the latest UUID/name; global and world-scoped player lookups return correct rows.
- [ ] A new post-migration causal TNT record is attributed, queryable, and usable for selective rollback planning.
- [ ] SQLite continues recording writes, passes a clean shutdown/checkpoint and reopens schema v9.
- [ ] A deliberately insufficient *declared* account free-space figure refuses migration at schema v4, preserving schema/history and allowing a later successful retry with available capacity.
- [ ] Existing unit tests validate simulated `SQLITE_FULL` during `CREATE INDEX`, in-progress index restart, quota and temporary-space preflights and rollback safety.

These **automated tests are not a 57-million-row reproduction** and cannot prove that BisectHosting's hidden account/temp quota permits the production upgrade.

## Running larger, non-production fixtures

Normal CI runs the bounded default to keep the build fast:

```bash
./gradlew --no-daemon clean test build
```

For a deliberately provisioned test machine, increase the synthetic event count:

```bash
FG_VALIDATION_ROWS=250000 ./gradlew --no-daemon test --tests '*LargeHistoryMigrationIntegrationTest' --info
```

The test accepts **100 to 2,000,000** system events plus two player events. Do not run it on the production server, beside its actual FG database, or on a host with unverified storage quotas. This workload uses test-generated data only.

For each migration test, capture:

- Number of rows and source/final SQLite DB bytes.
- End-to-end v4→v9 migration duration.
- Maximum **visible** main DB/WAL/journal file sizes sampled during migration.
- SQLite `integrity_check`, schema version, history/rollback counts, player/cause lookups, query plans and restart behavior.

**Important measurement limitation:** SQLite temp sort files may be unlinked while open. The sampler cannot measure total native SQLite temp usage, exact instantaneous peak (20 ms sampling), invisible managed-host quotas or live Paper load. A passing fixture is evidence of functionality, **not** a guarantee of adequate production capacity.

## Manual and final-live release gates — DO NOT RUN YET

These are release-end checklists and require explicit results in #141 before declaring 26.3-1.2.1 ready.

1. **Pre-upgrade:** all PR CI checks, issue #141 regression suite, larger fixture and code review pass. Resolve any blockers found.
2. **Capacity:** request confirmed **remaining account writable free bytes**, any per-container or per-file limit, available SQLite-native temp storage, and inode limits from BisectHosting. File-system-reported `getUsableSpace()` alone cannot establish the quota. Plan for WAL and temp index growth **in addition to** the 17.03 GiB DB and the retained 85.11 GiB backup.
3. **Backups:** retain the verified old 85.11 GiB backup **and** a freshly verified copy/snapshot of the current running schema-v4 DB (because FG 1.2.0 continues logging new changes). Ensure enough quota for both without blindly copying large files. Never overwrite the sole good backup.
4. **Controlled staging:** on an isolated test copy (not the production path), migrate with FG 1.2.1 in a matching Paper 26.3/Java 25 environment. Record each v4→v9 stage and space peak, then validate record counts, `integrity_check`, world identities, player attribution, rollback jobs/undo and startup/restart. Do not mix original `-wal`/`-shm` with a different DB.
5. **Production maintenance window (only after signoff):** stop server fully, secure verified backups and matching sidecars, check actual writable capacity, install tested 1.2.1, start and check **every** schema migration completed and plugin enabled. If it fails, stop and preserve the failed DB/sidecars for analysis; revert only using a **verified schema-v4** copy known compatible with FG 1.2.0.
6. **Final gameplay regression:** after migration succeeds, validate fresh block place/break, area lookup, GUI/paging, direct/causal (TNT/pistons) player lookup, previews, selective rollback, undo, cleanup/retention, persistent jobs, restart and WAL checkpoint. All manual Paper server tests remain at the **end of update** as previously agreed.
7. **Release signoff:** update this checklist and parent tracker #105 with actual measured results. Do not tag/merge a 1.2.1 production release until all required checks pass.

**Production operating rule:** leave FG 26.3-1.2.0 in service and keep backups while #141 is unfinished. No live test was performed in this PR.
