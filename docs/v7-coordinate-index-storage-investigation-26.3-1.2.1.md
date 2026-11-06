# FragGuard 26.3-1.2.1 — Beta 2 v7→v8 SQLITE_FULL investigation (#147)

## Observed on November 6, 2026

A **17.03 GiB** production FragGuard database, originally holding **57,009,208** block changes, was upgraded with `26.3-1.2.1-beta.2` on Paper 26.3 build 241, Java 25:

| Migration | Reported outcome |
| --- | --- |
| v4→v5 | Passed; all three actor indexes built individually and checkpointed |
| v5→v6 | Passed |
| v6→v7 | Passed |
| v7→v8 | **SQLITE_FULL** creating `idx_fg_coordinate_time` |
| v8→v9 | Never reached |

The v7→v8 index includes `(y, world_uuid, x, z, happened_at DESC, id DESC)` on rows where `rollback_pending=0`. The prior 1.2.1-alpha failure at v4→v5 is no longer the active blocker.

The log reports:
- Host data filesystem usable: **~2,472.17 GiB** (does **not** establish remaining BisectHosting account/container quota).
- Java temp filesystem usable: **998.8 MiB**, **not authoritative** for SQLite's native temp mount.
- `PRAGMA temp_store=0`, SQLite compiled with `TEMP_STORE=1`, indicating SQLite defaults to file-backed sort temporary storage in this build.
- `SQLITE_TMPDIR` and `TMPDIR` were both unset.
- WAL: **0 B after failure**; this does not measure unlinked native temp files or instantaneous index write needs.
- The stack says **sqlite-jdbc 3.49.1.0**, while Gradle declares **3.54.1.0**. Investigate separately under #149; no causal claim has been established.

**The actual failed storage location and root cause are still unknown.** We cannot rule out sort-temp filesystem limits, hosting account quota, per-file/inode restrictions, or the SQLite main DB/WAL allocation path.

Official SQLite sources:
- https://www.sqlite.org/tempfiles.html
- https://www.sqlite.org/pragma.html#pragma_temp_store

## Diagnostic additions in #147

The v7→v8 index uses the same per-index, before/after diagnostic wrapper introduced for v4→v5. It records the failing index, elapsed time, SQL error code/SQLState, SQLite DB/WAL/SHM/journal sizes, journal/temp-store modes, occupied/free page counts, `max_page_count`, and `user_version`.

A separate **read-only** probe reports possible native-temp filesystem candidates in SQLite's documented Unix search order: `SQLITE_TMPDIR`, `TMPDIR`, `/var/tmp`, `/usr/tmp`, `/tmp`, current working directory. Java's temp directory appears only as an additional *advisory* signal. Each item reports only whether it exists and appears writable/executable, approximate host-usable/total space, filesystem type, and whether it appears to share the database's file store.

The probe **does not print private environment-variable values, arbitrary filesystem paths, player identities or block history**. These are candidates, not a report of which SQLite VFS directory was actually used. The VFS can choose a native global temp override and can unlink temporary files while open. The preflight is an estimate, not a quota guarantee.

No index SQL, schema version transition, SQLite `temp_store`, global `temp_store_directory`, cache or temporary-file configuration is changed in #147. The v7 index is still created inside its existing transaction; on `SQLITE_FULL`, migration fails closed.

## Reproduction coverage and limits

`CoordinateIndexMigrationDiagnosticsTest` constructs a synthetic schema-v7 block history, limits `PRAGMA max_page_count` on its **test connection**, then attempts the exact v7 coordinate index creation inside a transaction. It asserts that:

- `SQLITE_FULL` identifies the index and v7→v8 stage;
- diagnostics contain size/page/temp candidate information, with no raw host paths;
- the failed transaction leaves **user_version=7**, all source rows, and no committed coordinate index;
- a new connection with normal page limits can retry the index, advance to v8, and pass `integrity_check`.

**This is a simulated SQLite main-database page limit, not reproduction of BisectHosting temp/quota exhaustion or 57M real rows.** The provider must confirm native temp and account limits, and a large *isolated* fixture/copy is still needed for root-cause verification.

## Priority recovery rule: NO verified schema-v4 backup

The server owner has confirmed **there is no verified v4 backup** available after Beta 2 reached v7. This supersedes earlier suggestions to restore a v4 backup. There is **no supported, lossless JAR-only downgrade** to FG 26.3-1.2.0 (it supports database schema v4). Merely replacing the JAR may cause FG 1.2.0 to refuse startup; manually setting `user_version=4` does not reverse prior migrations.

**Do not retry Beta 2 or FG 1.2.0 against the sole existing database.**

1. Stop server fully before any backup operations. Preserve a coherent offline, *unaltered* copy/snapshot of `fragguard.db` and its matching `-wal`/`-shm` files **if present**. Do not mix these with files from a different backup. If the hosting quota blocks a safe copy, request a host-side snapshot or restore point rather than deleting or changing SQLite files.
2. Retain the original data in place, including any schema v7 changes. Verify `user_version`, `integrity_check`, relevant row counts, and indexes **only on a separate verified copy** once one exists.
3. Ask BisectHosting to identify the **remaining writable account/container quota**, the actual native SQLite temp mount and free space (including `/var/tmp`, `/usr/tmp`, `/tmp`), inode/file-size limits and whether that temp mount is a ~1 GiB tmpfs. Also ask whether there are host snapshots predating the migration, but do not assume one exists.
4. #148 will redesign/reduce the costly coordinate index. #150 must supply a **non-destructive forward-recovery path for existing schema v7 without requiring a v4 backup**; if safe, use a corrected newer FG build on a copy to complete v7→v9 rather than attempting to downgrade.
5. #151 validates on large isolated copies and then performs the final authorized live-server tests **at the end of 1.2.1**, after approval. Until FG enables successfully, new block mutations are not logged.

No plugin in this issue automatically modifies or recovers the actual production database.
