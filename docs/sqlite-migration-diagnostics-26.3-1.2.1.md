# FragGuard 26.3-1.2.1 — SQLite index migration diagnostics (#138)

## Confirmed incident (November 4, 2026)

On Paper 26.3 build 241, upgrading a large schema-v4 FragGuard database to 26.3-1.2.1 failed at `Database.migrateHistoricalActorIndexes` with `SQLITE_FULL`.

- Original `fragguard.db`: 85.11 GiB; 67.52 GiB (79.33%) SQLite freelist pages.
- The read-only diagnostic and a guarded `VACUUM INTO` operation produced a verified 17.03 GiB copy (SQLite `integrity_check: ok`).
- Table counts matched: `block_changes=57,009,208`, `rollback_jobs=0`, `rollback_job_changes=0`.
- FG 1.2.1 **still failed** on the compacted database; FG 1.2.0 **starts** using its schema-v4 database.
- BisectHosting's panel reported 107.42 GB usage while the older 85 GiB database backup remained. The filesystem reported 2.45 TiB available, but **neither reading establishes the container's write quota or SQLite native temporary storage limit**.

**Root cause remains unconfirmed.** This issue adds evidence collection, not an index redesign or a storage-limit workaround; those are tracked by #139 and #140.

## What FG now records

When the v4→v5 migration starts, each of the three historical indexes gets its own `starting`, `completed`, or `FAILED` message:

- `idx_fg_actor_time`
- `idx_fg_actor_world_time`
- `idx_fg_actor_name_uuid`

Messages include the exact index, elapsed time, SQLite error code/SQLState where available, and a **before/after** storage snapshot. Snapshots contain:

- main SQLite DB, `-wal`, `-shm`, and `-journal` file sizes (or `absent`);
- host-reported usable bytes for the *database directory* filesystem and Java's temp-directory filesystem;
- whether `SQLITE_TMPDIR` and `TMPDIR` environment variables are set (never their values);
- read-only `PRAGMA temp_store`, the compile-time `TEMP_STORE` setting when exposed, `journal_mode`, `page_count`, and `freelist_count`.

**No absolute paths, player names, actor UUIDs, block data, or SQL row contents are logged.** The log does not print the SQLite SQL statement. If a PRAGMA cannot be queried safely after a failure, its value is reported `unavailable`, without hiding the original SQL error.

**No SQLite configuration is changed.** Index definitions, transaction boundaries, schema versions, retention, and rollback behavior remain unchanged in #138. The existing enclosing migration transaction continues to own the schema version update and rollback.

## Interpreting disk and temp reports

SQLite may use temporary storage for sorting large `CREATE INDEX` operations, besides database and WAL/journal writes. `PRAGMA temp_store` is not sufficient to identify the actual native temp directory: the compiled `SQLITE_TEMP_STORE` setting, the OS-specific SQLite VFS, and the size/type of temporary operations also matter.

On Unix/Linux, SQLite's documented temporary-directory search order includes an explicitly configured native directory (if any), `SQLITE_TMPDIR`, `TMPDIR`, and system temporary directories. **Java's `java.io.tmpdir` is not proof of the directory SQLite uses.** We therefore record Java temp capacity only as a separate *host filesystem signal*. We do not call or change the deprecated `PRAGMA temp_store_directory`: it is process-global/unsafe with concurrent SQLite use.

Files created by the SQLite VFS may be unlinked while open, so they might not be visible in the Minecraft file manager. Furthermore, host-reported `getUsableSpace()` can omit managed-host account/container quotas; a large number is **not proof that the migration can write that much**. `SQLITE_FULL` alone does not identify whether the main DB, WAL/journal, native temp filesystem, quota, or file size/inode limits were reached.

Official references:

- https://www.sqlite.org/tempfiles.html
- https://www.sqlite.org/pragma.html#pragma_temp_store
- https://www.sqlite.org/pragma.html#pragma_temp_store_directory

## Reproduction and testing

The automated `HistoricalIndexMigrationDiagnosticsTest` creates a synthetic block history database, intentionally constrains `PRAGMA max_page_count` just above the current page count, and attempts to build the same actor/time index inside a transaction. This reproduces **main-database allocation** `SQLITE_FULL` without accessing any production data or filling the machine's actual disk. It verifies:

- the precise failing index and SQLite error are reported;
- host paths and player data are not exposed;
- original schema version 4 and history rows remain unchanged after rollback;
- an unsuccessful index creation is not committed;
- successful indexes report completion.

This test **does not simulate** a BisectHosting account quota or native temp-directory exhaustion. Those remain distinct possibilities for #139/#140 to investigate with constrained test environments.

## Operator procedure / release gate

1. Keep the verified original backup and production FG **26.3-1.2.0** in service while code fixes are prepared.
2. Do not delete `fragguard.db` or SQLite `-wal`/`-shm` files. Do not repeatedly restart the failing migration.
3. If `SQLITE_FULL` repeats in a controlled test environment, collect the `Schema v4->v5 index ...` start/failure lines and ask the host about **account/container quotas, native temp storage, inode/file-size limits, and writable filesystem restrictions**. Avoid exposing full host paths.
4. Complete #139 (index storage strategy), #140 (storage-aware preflight and safe recovery), and #141 (large-fixture end-to-end tests) before production rollout.
5. All **live Paper server tests stay at the very end** of 26.3-1.2.1 as previously agreed.

No live production migration is requested by #138.
