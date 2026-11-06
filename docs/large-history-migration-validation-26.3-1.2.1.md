# FragGuard 26.3-1.2.1 — Large-history regression suite (#141, #151)

**Status:** Synthetic CI regression coverage exists. The **actual 57-million-history / 17.03 GiB** database has not yet completed isolated v7→v9 staging. **No live Paper server tests or release sign-off.**

For the current authoritative no-v4-backup safety process, offline full-history manifest commands, provider quota checks, staging validation, manual tests and release criteria, see [Final large-database release gates](final-large-database-release-gates-26.3-1.2.1.md) (#151) and [Schema-v7 forward recovery](schema-v7-forward-recovery-26.3-1.2.1.md) (#150).

## Original incident and subsequent fix

- The 57,009,208-row v4 schema first failed v4→v5 with SQLITE_FULL. A VACUUM INTO reduced the DB from 85.11 GiB to a verified 17.03 GiB copy.
- Beta 2 subsequently **succeeded v4→v7** but failed creating the almost-all-history v7→v8 coordinate index. Because there is **no verified pre-upgrade v4 backup**, simply installing FG 1.2.0 is not a supported recovery. The current schema must be confirmed on an isolated copy.
- #148 removed the enormous coordinate index and made v7→v8 reuse/validate the existing chunk/time index. #149 removed the duplicate runtime SQLite driver; #150 covered safe v7 forward retry.
- Actual native SQLite temp location and managed-host account writable quota **remain unverified**. Host filesystem free and Java temp are not interchangeable.

## Automated genuine-v4 fixture

\`LargeHistoryMigrationIntegrationTest\` seeds schema-v4 tables and indexes matching released FG 26.3-1.2.0; it does not simply change a v9 database's user_version. CI uses **30,002 total events**, two historical player/causal-world examples, a block-entity BLOB, an existing completed radius rollback job and snapshots. It checks:

- v4→v5→v6→v7→v8→v9, integrity and exact source row/job/snapshot counts;
- direct player indexes, causal actor indexes, pre-existing spatial time index, and **absence** of the obsolete global coordinate index;
- SQL query plans, historical usernames, cross-world lookups, new causal TNT writes, selective player rollback planning, shutdown and reopen;
- known insufficient account-free preflight refusal at v4 and subsequent recovery without deleting data.

\`\`\`bash
./gradlew --no-daemon clean test build
\`\`\`

To opt into a larger synthetic workload on an independently provisioned **non-production** host:

\`\`\`bash
FG_VALIDATION_ROWS=250000 ./gradlew --no-daemon test \
  --tests '*LargeHistoryMigrationIntegrationTest' --info
\`\`\`

The accepted bound is **100–2,000,000 additional system records**. Fixture measurements include migration elapsed time, DB size, sampled visible WAL/journal peaks, integrity, and query plans. The sampler does **not** measure SQLite native unlinked temp or hidden hosting quotas.

## Genuine-v7 fixture and full-history offline verification (#150, #151)

\`SchemaSevenForwardRecoveryIntegrationTest\` starts from real v7-shaped tables/indexes (not v9 with a version reset) and validates:

- safe migration refusal with a deliberately low account-free setting;
- v7→v9 retry, existing historical actor/causal fields, radius rollback jobs and entity snapshots, clean shutdown and reopen;
- transactional rollback after a forced test-only failure during v8→v9 backfill;
- fail-closed chunk-coordinate inconsistency detection;
- a configurable **12,000-row default** larger v7 fixture with measured migration time and DB bytes.

\`\`\`bash
FG_V7_VALIDATION_ROWS=250000 ./gradlew --no-daemon test \
  --tests '*SchemaSevenForwardRecoveryIntegrationTest' --info
\`\`\`

The new \`scripts/validate_offline_migration.py\` creates read-only, full-row SHA-256 fingerprints of common historical columns (including entity BLOBs and v7 causal fields) for an **isolated** database snapshot and compares them against a migrated v9 copy. It never migrates or edits the database and is protected against overwriting the input file. See the final-release-gates document for commands and limitations.

**No synthetic run establishes that the actual 57M history rows will migrate within the real host quota, or that heavily edited chunks will meet query latency requirements.** Those require gate #151 on real isolated copies and finally end-of-update Paper server tests. Release PR #146 remains open and unmerged.
