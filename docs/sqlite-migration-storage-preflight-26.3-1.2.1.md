# FragGuard 26.3-1.2.1 — SQLite migration storage preflight and recovery (#140)

FragGuard never deletes block history to make a migration fit. This update adds
**advisory, conservative disk estimates** and fail-closed checks to index-only and
metadata upgrades, in addition to existing full-backup migrations.

## Production context

- A valid schema-v4 database with **57,009,208** history records failed
  upgrading to v5 with `SQLITE_FULL`, even after compaction from 85.11 GiB
  to 17.03 GiB.
- The server's reported host-filesystem free space was about **2.45 TiB**,
  but this is **not** evidence of the hosting account's writable quota.
- After the November 6 Beta 2 attempt, schema v4→v7 migrations succeeded but
  v7→v8 failed on a 57M-row coordinate index. The owner has **no verified v4
  backup**, so recovery must preserve the current schema-v7 state and move
  forward through a tested newer build (#148/#150/#151).

## What is checked

For existing databases, before a schema step FragGuard obtains SQLite's
`page_size`, `page_count`, and `freelist_count` and estimates the
**logical occupied bytes** (pages that aren't reusable freelist pages).
This avoids assuming the file size of an un-vacuumed database equals its live
data volume.

- v3→v4: lower, bounded allowance for index removal/metadata.
- v4→v5: **before each missing actor index** from #139. If an index was
  successfully committed during an interrupted earlier attempt, it is not
  charged again. Each completed index is checkpointed to limit WAL growth.
- v5→v6 and v6→v7: estimate twice the active database bytes (minimum
  512 MiB) for index creation, WAL and SQLite sort/temp work.
- v7→v8: no new all-history coordinate index is created (#148). Verify the
  existing world/chunk/time index and all persisted chunk coordinates using
  a read-only scan. Reserve 128 MiB plus the safety margin for metadata only.
- v8→v9: bounded metadata allowance (minimum 128 MiB).
- Legacy full-backup migrations: budget for the backup file, table-migration
  working space, later index-heavy work, and the safety margin.
- New empty databases and already up-to-date schemas do not require
  existing-database migration preflight.

The default **additional safety margin** is 256 MiB, configurable with
`database-migration-space-safety-mib`. Values are *planning estimates, not
reliable upper bounds*.

## Config: optional operator-supplied free space

```yaml
database-migration-space-preflight-enabled: true
database-migration-space-safety-mib: 256
database-migration-account-free-mib: 0
database-migration-sqlite-temp-free-mib: 0
```

`0` for the last two options means **unknown**, NOT unlimited. Only specify
numbers when your hosting provider confirms **currently free space** in MiB,
not plan capacity or currently used space.

- Account free space: remaining bytes that the container/account can write,
  regardless of what the shared host filesystem reports.
- SQLite-native-temp free space: remaining bytes on the **actual** native
  SQLite temporary-file filesystem. This may not be Java's
  `java.io.tmpdir`/the server's /tmp directory.

If the host-reported data filesystem or a declared capacity is *known* to
be below the conservative requirement, FG refuses to begin that migration
stage. If the host data-filesystem value is unavailable and no operator
account-free figure is provided, preflight refuses instead of assuming
unlimited space.

**A passing preflight is not a guarantee.** A managed host may enforce
different account, cgroup, tmpfs, inode or file-size limits without exposing
them to Java; SQLite's sort files may live on a different mount. Passing
host-visible capacity values is explicitly reported as *unverified quota*.

Only disable preflight if you deliberately accept that risk. Disabling the
check does not make SQLite errors recoverable or safe to ignore.

## Failure diagnostics and preservation

On `SQLITE_FULL`, the plugin reports:

- Schema version being upgraded and **exact startup stage**;
- DB/WAL/journal file sizes (no absolute file paths);
- Host-reported available filesystem space, marked as such;
- Whether a confirmed account figure was supplied;
- Warning that the actual failed write location is unconfirmed.

The original SQL error remains available as the cause, and a rollback failure
is attached as a *suppressed* exception rather than replacing the original
error.

Failures stop FG startup; they do not bypass the database worker's fatal
health handling, advance an incomplete schema step, or delete user-managed
database/WAL/SHM files. Some completed #139 indexes may safely remain while
`user_version=4` on restart; subsequent attempts skip those indexes.

**Do not manually delete SQLite `-wal`, `-shm`, or `-journal` files.**
The owner has confirmed **no verified pre-upgrade v4 backup exists**. Never
suggest using FG 1.2.0 against the existing v7 database or manually changing
`user_version`. Preserve current database and matching sidecars as a coherent
snapshot, and perform forward repair only on isolated copies until validated.
If a migration fails, retain the database and send the exact FG schema
diagnostic lines plus hosting-provider account/native-temp free-space limits.

## Test coverage

Automated checks cover:

- Logical occupied pages versus a large freelist;
- Stage-specific conservative estimates, saturation/overflow, and full backups;
- Insufficient host filesystem, confirmed account quota, confirmed SQLite
  temp quota, and unknown quota outcomes;
- Correct SQLite-full classification including nested SQL exceptions;
- Rejected existing schema-v4 migration before index creation, preserving
  schema version 4, history rows, and lack of new backups;
- Rejected later v7 index migration with a small operator temp quota;
- New database/current-schema startup with quota values that would otherwise
  fail a migration step;
- Existing #138 simulated SQLITE_FULL rollback and #139 index recovery tests.

The quota values in these tests are **synthetic limits**, not an actual
exhaustion of BisectHosting disk or SQLite's native tempfs. Large fixture
performance measurements and all live-server testing belong to #141,
at the end of the 26.3-1.2.1 development cycle.
