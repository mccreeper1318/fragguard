# FragGuard 26.3-1.2.1 — Final large-database validation and sign-off (#151)

**Status: validation tooling in development; production NOT cleared.**

The server owner confirmed **no verified v4 backup exists**. Beta 2 upgraded a ~17.03 GiB, ~57,009,208-history database through schema **v7**, then failed with SQLITE_FULL when its old v7→v8 migration attempted a new all-history coordinate index. The actual committed schema must be confirmed on a coherent **offline** copy. The #148 fix removes that huge index build; #149 uses Paper's SQLite JDBC; #150 tests forward-recovery failures. **No production data or final Paper live tests have been touched by this work.**

## Gate A — Safe source preservation (not completed)

- [ ] Arrange a **fully stopped-server coherent snapshot** of the *entire* \`plugins/FragGuard\` folder, including \`fragguard.db\`, matching \`-wal\`/\`-shm\` if they exist, configs, and available auxiliary files. If the hosting provider can supply an atomic snapshot, prefer it.
- [ ] Verify the copied SQLite file is on independent, capacity-verified storage. **Do not delete/mix sidecars**, run \`VACUUM\` on the sole source, or hand-edit \`PRAGMA user_version\`. Preserve one untouched recovery copy while using another for staging.
- [ ] Record exact original schema, SQLite \`integrity_check\`, **all history and rollback-job row counts**, DB/WAL size and file checksums outside the repo. Do not attach database data or raw player information to public GitHub.
- [ ] Obtain confirmed remaining *account/container writable space*, SQLite native temp filesystem free bytes/inodes and any size constraints from BisectHosting. Java temp free space and host-visible 2.45 TiB do **not** prove these limits.

## Gate B — Read-only offline full-history manifests (not completed)

On isolated snapshot copies, run Python 3 using only the standard library:

\`\`\`bash
python3 scripts/validate_offline_migration.py inspect \
  --db /staging/before/fragguard.db \
  --output /staging/reports/before.json \
  --i-confirm-isolated-copy
\`\`\`

This opens the supplied SQLite **copy** read-only, sets \`PRAGMA query_only\`, checks schema version (v4/v7/v8/v9), \`PRAGMA integrity_check(1)\` and \`foreign_key_check\`, counts three core tables, and computes **streamed SHA-256 hashes of all existing historical common-column values**, including block-state and entity-snapshot BLOBs. It also hashes causal columns on v7/v8 data. Rows are streamed in ID order with bounded memory and **no raw history, coordinates, usernames, paths or secrets in the JSON report**.

**This is an all-row read and integrity scan. On 57M records it can take significant time and storage I/O; use an isolated copy only.** The tool will refuse to run without \`--i-confirm-isolated-copy\`. It does not checkpoint, migrate, \`VACUUM\`, write SQL or change the database.

After the separately staged upgrade of a **different writable copy**, inspect the v9 result:

\`\`\`bash
python3 scripts/validate_offline_migration.py inspect \
  --db /staging/after/fragguard.db \
  --output /staging/reports/after.json \
  --i-confirm-isolated-copy

python3 scripts/validate_offline_migration.py compare \
  --before /staging/reports/before.json \
  --after /staging/reports/after.json \
  --output /staging/reports/verification.json
\`\`\`

Comparison **fails closed** if the original schema was not v4/v7/v8, target is not v9, integrity checks fail, any historical/rollback-job count changes, any common-column or causal-field SHA-256 differs, or any required v9 actor/causal/spatial/job index is absent.

**Important nuance:** A schema migration may intentionally change some saved metadata (for example, mapping old world identities or refreshing world display names). A hash mismatch means the two files are **not byte-for-byte equivalent over the checked historic fields**, and must be investigated, **not** silently waived. After a safe documented review, distinguish expected metadata transformations from dropped or damaged block events. The report is not a substitute for semantic rollback/undo tests.

The manifests exclude raw player history but may include file sizes and schema metadata; avoid posting internal host quota details publicly.

### Codex review safeguards for the offline checker (PR #156)

- Report destinations must be independent filesystem files, not path aliases, symbolic links, or **hard links** to an input database, matching WAL/SHM sidecar, or either input manifest. The tool checks filesystem identity (`samefile`) in addition to resolved paths and writes reports through a temporary file plus atomic replacement to avoid truncating a source inode.
- Final v9 migration comparison requires **all eight historical/rollback indexes**: `idx_fg_spatial_time`, `idx_fg_actor_time`, `idx_fg_actor_world_time`, `idx_fg_actor_name_uuid`, `idx_fg_causal_actor_time`, `idx_fg_causal_actor_world_time`, `idx_fg_causal_name_uuid`, and `idx_fg_job_chunk_overlap`. Missing any index fails the gate, even if history counts and hashes agree.
- Regression tests intentionally create database, WAL/SHM and manifest hard links and verify unchanged input bytes; they also remove each required index individually and confirm release validation fails.

## Gate C — Bounded automated migration suites (CI underway)

The normal CI suite includes:

- [ ] Genuine v4-shaped seed → v9, counts, world/player/causal history and block-entity BLOB snapshots, rollback jobs, actor/spatial query plans, native temp/visible WAL caveats.
- [ ] Genuine v7-shaped seed → v9 with retained radius job/snapshots, early quota refusal + retry, v8→v9 transactional failure + retry, invalid chunk guard and restart.
- [ ] Additional synthetic **12,000-row default v7→v9** test with source/final DB bytes, elapsed duration and \`integrity_check\`; no all-history coordinate index.
- [ ] Offline manifest Python unit tests (read-only operation, causal/BLOB tamper detection, index failure, no overwrite, no raw data leaks).
- [ ] All Paper build/unit tests, packaging guardrails and shadow verification pass.

A separately provisioned **non-production** machine may increase seeded rows (not a replacement for a real v7 copy):

\`\`\`bash
FG_VALIDATION_ROWS=250000 FG_V7_VALIDATION_ROWS=250000 \
  ./gradlew --no-daemon test \
  --tests '*LargeHistoryMigrationIntegrationTest' \
  --tests '*SchemaSevenForwardRecoveryIntegrationTest' --info
\`\`\`

The bounds are **100–2,000,000 synthetic extra rows** per test. Do not run on the production host or assume such a fixture proves account/temp quotas for the real 57M-row DB.

## Gate D — Actual 57M-scale isolated upgrade and behavior (not completed)

- [ ] Run FG 26.3-1.2.1 candidate on an **independent writable schema-v7 snapshot copy** in matching Paper 26.3 build 241 / Java 25 staging. No production or player traffic. Confirm runtime JDBC/native versions and sanitized library origins.
- [ ] Record schema stages, elapsed runtime, DB and WAL growth, visible temp/journal peaks, memory/I/O and confirmed hosting writable/native-temp capacity. SQLite may unlink temporary sort files while open; file samplers **cannot** prove total peak usage.
- [ ] Validate before/after manifests; \`PRAGMA integrity_check\`, exact historical/rollback counts, world mappings, player and causal attribution, snapshots, existing rollback jobs/undo and 30-day retention settings.
- [ ] Benchmark GUI/command lookup and **busy-chunk** player rollback planning/query plans, ensure newest-first order, world/negative-coordinate filtering, no skipped system/other-player edits, and reasonable resource consumption. The reused chunk index is correct in small tests but hot chunks may be slower than the removed per-coordinate index.
- [ ] Exercise interrupted v7→v8/v8→v9 on **disposable non-production copies only**. Verify schema rollback and restart safety. Never inject failures into the sole real database.

## Gate E — Final live Paper tests and release approval (intentionally LAST)

- [ ] After all code/review/CI and isolated tests succeed, secure **a fresh verified coherent backup** of current production. Record actual on-disk schema (may be v7 or v8). Never assume 1.2.0 is a rollback path; without a v4 backup it is not.
- [ ] Controlled 1.2.1 restart and monitor full migration to v9, SQLite driver, logging health and WAL checkpoint. If failure occurs, stop; preserve unchanged snapshot and the failed-copy evidence for *forward repair*, **not** a forced schema reset.
- [ ] Live checks: place/break, rollback/undo, GUI history and paging, radius lookup, UUID/name cross-world lookup, TNT/piston causal attribution, player safety boundaries, stale previews, rollback jobs/restart, retention cleanup and logs. **Do not perform until the end of update**.
- [ ] Document actual measurements/results in #151 and parent #105. Request explicit release approval. Then, and only then, merge release PR #146 and publish/tag **26.3-1.2.1**.

**No live tests or production migrations have occurred as part of this issue.** Until a verified real-data staging run is completed, #151 remains open and the release PR remains unmerged.
