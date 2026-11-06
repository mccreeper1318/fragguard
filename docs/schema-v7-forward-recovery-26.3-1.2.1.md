# FragGuard 26.3-1.2.1 — Safe forward recovery from schema v7 (#150)

## Current incident and hard constraints

The November 6 Beta 2 attempt successfully migrated the production SQLite database through **schema v7**, then failed building the old global coordinate index during v7→v8 with `SQLITE_FULL`. The production file contains about **57,009,208 historical changes** and was reported as **17.03 GiB** after compaction. The **actual schema version must still be verified from a coherent offline snapshot**: success messages strongly suggest v7, but the log is not an offline database verification.

**There is no verified pre-upgrade schema-v4 backup.** Do **not** replace the newer FG JAR with 1.2.0 against this database, edit `PRAGMA user_version`, remove `-wal` or `-shm` manually, re-run `VACUUM` on the sole copy, or manually delete indexes/history.

The correct goal is **non-destructive forward recovery from the existing committed schema**, preserving all existing block changes, block entities, rollback job snapshots and undo state. The code in #148 avoids recreating the huge `idx_fg_coordinate_time` index. This #150 change verifies that a failed upgrade does not require any older backup and can safely resume.

## Prerequisite: a coherent, verified offline snapshot

Do this **only in the final controlled staging/release phase** once the code review and release gates are complete. No production changes were made while developing #150.

1. **Keep the Paper server stopped** before taking a file-level snapshot. If the host can create a complete filesystem snapshot, request one without rotating, deleting or renaming SQLite files.
2. Copy **the entire** `plugins/FragGuard` directory (database, configuration, matching `fragguard.db-wal` and `fragguard.db-shm` if present, and any existing backups) to an independent, capacity-verified storage location. Do **not** assume a directory copy is consistent while Paper/SQLite is actively writing. A stopped-server copy or a host-provided coordinated snapshot is required. If the host cannot provide enough space, stop here and arrange external storage or a provider snapshot; do not sacrifice the only copy.
3. Retain a second independent copy for tests. Work **only on that isolated copy**, with its matching SQLite sidecars. Do not mix sidecars from different snapshots and do not copy back a single modified DB over a still-running server.
4. Before running an upgrade, check the **copy** using a supported SQLite client with the database opened normally (not read-only in a way that drops committed WAL changes):
   ```sql
   PRAGMA user_version;
   PRAGMA integrity_check;
   SELECT COUNT(*) FROM block_changes;
   SELECT COUNT(*) FROM rollback_jobs;
   SELECT COUNT(*) FROM rollback_job_changes;
   PRAGMA index_list(block_changes);
   ```
   Record the baseline, verified engine/JDBC version and the exact count. Do not update `user_version` manually. If integrity fails, **do not migrate**; preserve the unchanged source and investigate.
5. Verify **hosting account free quota, SQLite-native temp filesystem and available inodes** separately from host `getUsableSpace()`. The ~1 GiB Java temp mount observed in Beta 2 is not confirmed as SQLite's native temp location.

## Forward migration design

- For a valid v7 database, FragGuard logs that it is **resuming forward migration to v9** and explicitly warns that **no automatic full-database backup is created** for v7 metadata/index steps. The operator must have an external coherent snapshot.
- **v7→v8** uses the existing `idx_fg_spatial_time` index, verifies the index definition and checks all existing block coordinates against saved chunks without rewriting them. The v7→v8 schema-version change is transactional. If missing/malformed chunk metadata is found, FG stops without touching history or automatically "repairing" it.
- **v8→v9** adds player-rollback job scope and provenance fields and the `rollback_job_chunks` table. All DDL, backfills and `PRAGMA user_version=9` execute in one transaction. A failure during the backfill rolls back those columns/table changes and leaves the schema at **v8** for an ordinary retry. A v7→v8 success is **not** rolled back by a later failed v8→v9 transaction; resume from the actual committed v8 next time.
- v7→v8 no longer calls a redundant post-commit WAL checkpoint intended for huge index builds. The v5/v6 index checkpoints remain. Ordinary shutdown has its own WAL checkpoint.
- For schema v7 or later, the plugin **does not create duplicate gigantic automatic full backups**. A missing snapshot is an operator safety blocker, not an invitation to downgrade.
- If FG detects a schema version **newer than this JAR understands**, it rejects startup with a warning to use compatible newer code or a verified schema-matching backup. This does not alter older already-published FG 1.2.0 JARs.

Do not treat a user-visible "migration failed" message as proof that all preceding steps rolled back. Check `PRAGMA user_version` on the offline copy and resume from the **actually committed version**.

## Automated failure/restart coverage

`SchemaSevenForwardRecoveryIntegrationTest` creates an authentic **v7-shaped fixture**: original v4 tables/rollback BLOBs plus v5 actor indexes and v6/v7 causal fields/indexes. The fixture deliberately **does not contain** v8's failed coordinate index or v9's job scope fields.

- [ ] Artificial **1 MiB known account-free preflight** refuses the v7→v8 upgrade; version remains v7; all block history, world identities and BLOB snapshots remain unchanged.
- [ ] A subsequent normal start advances v7→v8→v9, retaining exact original rows, existing rollback jobs and BLOB snapshots, and no full-history index or automatic v4 backup is created.
- [ ] A test-only SQLite trigger aborts `rollback_job_changes` backfill **after** v8→v9 begins. Assert schema remains v8; added columns and chunk tables roll back; no rows or snapshots change. Drop the test-only trigger *on the fixture*, retry v8→v9, restart and verify integrity.
- [ ] An inconsistent legacy `chunk_x` fails closed at v7; no source rows are deleted or silently rewritten. The test fixes only its synthetic fixture and verifies the next migration succeeds.
- [ ] Verify `integrity_check`, world/actor/causal data, `scope_type=RADIUS`, saved rollback entity data, WAL shutdown/restart and unchanged history counts.

**Boundaries:** CI is synthetic. It does not reproduce the production **57-million-row** processing time, host quotas or native SQLite temp behavior, and is not sufficient to authorize a production attempt.

## Final controlled rollout (release gate #151 — not done yet)

1. Complete PR #150 review, all GitHub Actions checks, and remaining bugfixes (#147–#149).
2. Create and independently verify a coherent copy of the existing v7 (or actual committed schema) database and preserve an untouched off-host copy.
3. In isolated Paper 26.3 build 241 / Java 25 staging, run the current tested FG 1.2.1 candidate on one disposable copy. Watch stage logs and driver-provenance messages; verify full `integrity_check`, row counts, job/undo snapshots, world identities, rollback/player lookup query plans, and WAL clean shutdown/reopen. Record time and disk growth. Never treat the synthetic unit tests as a substitute.
4. If it fails, **preserve the failed copy and logs**, inspect actual `user_version` and error, and retry only after resolving the issue. Keep the original copy unchanged. Do not force the version or attempt an incompatible JAR.
5. Only after staging passes and confirmed writable capacity exists, schedule final live-server tests. Stop Paper, take a fresh coherent verified snapshot, install the reviewed candidate, and confirm it reaches schema v9 with all 57,009,208 historical records (or the exact actual baseline) preserved.
6. If production migration fails, stop FG/server and preserve the current DB + matching sidecars and logs. Restore **only** the verified latest coherent pre-attempt snapshot of the same v7 (or actual) schema to return to the pre-attempt state, or pursue a corrected forward build after staging. Do **not** run FG 1.2.0 against restored v7. A v4 rollback is not available.
7. Sign off #151 and parent #105 before merging release PR #146 or publishing 26.3-1.2.1.

### Data limitations

With FG disabled after the Beta 2 failure, **new Minecraft block changes were not recorded**. Forward recovery preserves historical events already committed in the database; it cannot reconstruct events that were never logged. Restoring a snapshot older than the most recent committed history may also discard later records. This is why the latest coherent snapshot must be preserved.
