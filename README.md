# FragGuard

FragGuard is a Paper plugin that records world-changing block history in SQLite, gives server operators command-based and Minecraft-native tools for investigating that history, and can roll an area back to the state it was in at a chosen time.

History is retained for 30 days by default. The `26.3-1.2.0` inventory GUI condenses related events for easier browsing without deleting, rewriting, or hiding the underlying raw history. Large investigations are database-backed and paged, so the operator can browse far more than 5,000 matching events without loading the complete result set into one GUI session.

## What it tracks

- Player block placement and destruction
- Explosion block changes and TNT priming/removal
- Dragon egg source and destination changes
- Initial fire ignition, fire spread, and blocks destroyed by fire
- Lava and water flow
- Player bucket water/lava placement and removal
- Sponge absorption and dispenser bucket placement/removal
- Blocks broken by liquid flow
- Piston extension/retraction, movement, and break changes
- Entity-caused block changes and block formation
- Natural growth, fading, formation, spread, and leaves decay
- Structure growth and fertilization
- Player interactions that persistently change structural block data
- Player UUID/name and projectile-shooter attribution when available
- System/environment cause labels when no player caused the change
- World, coordinates, time, before/after block data, and supported block-entity contents

FragGuard stores data in `plugins/FragGuard/fragguard.db` using SQLite.

## Commands

Only server operators with `fragguard.admin` can use FragGuard administration controls.

```text
/fg
/fragguard
/fg help
/fg lookup r:30
/fg lookup r:30 p:2
/fg rollback r:30 t:2d 7h 15m
/fg rollback confirm <token>
/fg undo <job-id>
/fg status
```

`/fg` and `/fragguard` with no arguments open the in-game GUI. Existing subcommands remain available.

## In-game GUI

The GUI uses ordinary server-side Minecraft inventories and requires no client mod or resource pack. The main menu provides access to history lookup, rollback setup/confirmation, undoable rollback jobs, storage status, and command help.

### GUI lookup setup

Lookup History provides deterministic radius and time presets. Radius choices are bounded by `max-lookup-radius`, and time choices are bounded by the configured retention period.

The selected world, center, radius, cutoff time, and an upper database row boundary are captured for the investigation. This keeps paging deterministic even while new history continues to be written after the lookup begins.

### Large lookup behavior

GUI lookups are **not rejected because they contain more than 5,000 events**.

Raw events are fetched from SQLite one GUI page at a time using deterministic keyset ordering:

```text
happened_at DESC, id DESC
```

The session retains only the current bounded page plus lightweight paging cursors. Block-entity BLOBs are not part of list pages and are loaded only when one exact event is opened.

The condensed activity view is also bounded. FragGuard scans lightweight rows from SQLite in chunks controlled by `gui-lookup-fetch-size`, builds only the activity summaries required for the current GUI page, and keeps cursor boundaries for later drill-down. Opening an activity pages its exact represented rows directly from SQLite.

This means a busy base can contain tens or hundreds of thousands of matching retained events without requiring all of those exact rows to live in the player's GUI session at once.

### Condensed activities and raw history

Results can be browsed in two forms:

- **Condensed Activities** groups nearby consecutive records into human-readable runs.
- **Exact Raw Events** exposes the individual stored history records.

Grouping is presentation-only. FragGuard never collapses or rewrites SQLite history. Activities only combine consecutive rows with the same actor identity, action, and relevant material/state while respecting local time/distance and whole-activity duration/span limits.

Selecting an activity opens its details, and **View All Raw Events** pages every exact row represented by that activity from the database.

### Structured lookup filters

The results screen provides structured **Player**, **Action**, and **Material** filters. Filter choices are discovered from the selected lookup window without requiring the full exact result set to be materialized.

Active filters are applied in SQLite before raw paging or activity streaming. Player filters use the stored actor identity, action filters use FragGuard action identifiers, and material filters use the material represented by each history row.

Filtering changes presentation/query selection only. It never deletes or rewrites stored history.

### Async safety

Lookup work stays off the Bukkit server thread. Each GUI request has a generation identity; if an older page/filter/detail request finishes after a newer request, after the session resets, or after the player leaves, the stale completion is discarded rather than replacing the current investigation.

## Command lookup

```text
/fg lookup r:30
```

Shows recent block-change logs in a full-height cylinder around the current position. Command results remain independently paginated using `lookup-page-size`.

```text
/fg lookup r:30 p:2
```

## Rollback

The GUI Rollback screen provides radius/time presets plus a conflict-protected/force-mode toggle. **Preview Rollback** runs the existing rollback preview pipeline without changing blocks. After the preview completes, reopen `/fg` → **Rollback** and use **Confirm Active Preview** to execute the operator-bound preview token.

The command workflow remains available:

```text
/fg rollback r:30 t:2d 7h 15m
/fg rollback confirm <token>
```

A preview reports affected blocks/chunks and the target time before any mutation. Confirmation tokens are single-use, tied to the operator, and expire according to `rollback-confirmation-timeout-seconds`.

Every confirmed rollback receives a durable job ID. Reverse a completed or recoverable failed rollback with:

```text
/fg undo <job-id>
```

The GUI **Undo Rollback** screen lists retained completed/failed jobs that actually applied changes. Selecting a job opens a separate confirmation screen before the existing undo execution path is invoked. Already-undone and no-op jobs are not shown as undoable.

Rollback and undo progress is persisted in SQLite. Interrupted jobs resume after restart, overlapping jobs in the same world are rejected, and terminal jobs/snapshots expire according to `rollback-job-retention-days`.

FragGuard loads existing chunks asynchronously without generating terrain, holds only required chunk tickets, and respects per-tick time/block and TPS safeguards. Normal rollback skips conflicts. Force rollback revalidates/retries from the latest observed state rather than blindly overwriting concurrent edits. Unresolved undo conflicts remain retryable.

## Storage status

```text
/fg status
```

Shows database health, bounded write/control queue usage, coalesced same-tick changes, and dropped-write warnings.

Block changes use one long-lived SQLite worker connection and transactional batches. Expected timed-query cancellation and timeout results are treated separately from genuine storage failures.

## Database upgrades and recovery

FragGuard stores its schema version in `PRAGMA user_version`. The current schema version is **4**.

Migrations that rewrite persistent schema/data create and verify a consistent SQLite backup before advancing the schema. The v3→v4 migration is intentionally different: it verifies the live database, transactionally removes the obsolete persistent same-tick coalescing index, verifies again, and advances to v4 without building another multi-gigabyte index or making a full copy solely for that derived-index removal.

Schema v4 performs cross-flush same-tick coalescing with a small bounded in-memory row-ID cache for the current server session. Eviction can reduce coalescing efficiency but never discards an underlying history event.

A database with a schema newer than the installed FragGuard build is rejected rather than silently opened by older code.

Large databases can take longer than 30 seconds to initialize or perform migration maintenance. FragGuard emits progress warnings. `database-startup-timeout-seconds: 0` disables an arbitrary hard startup cutoff.

Before upgrades, stop the server completely and preserve the entire `plugins/FragGuard` directory off-host. Do not copy only `fragguard.db` while the server is running because active SQLite changes may still be in `fragguard.db-wal`.

To restore a verified database backup:

1. Stop the Paper server completely.
2. Preserve the current `plugins/FragGuard/fragguard.db` separately.
3. Copy the selected backup to `plugins/FragGuard/fragguard.db`.
4. Remove stale `fragguard.db-wal` and `fragguard.db-shm` only while the server is fully stopped.
5. Install a FragGuard release compatible with the restored schema and restart.

World history and rollback jobs use world UUIDs as identity while retaining readable names, so world renames remain traceable when the original world UUID is preserved.

## Config

`plugins/FragGuard/config.yml`

```yaml
retention-days: 30
cleanup-interval-minutes: 60
rollback-job-retention-days: 30

database-write-queue-capacity: 20000
database-operation-queue-capacity: 256
database-write-batch-size: 500
database-query-timeout-seconds: 15
database-startup-warning-seconds: 30
database-startup-timeout-seconds: 0
database-migration-space-preflight-enabled: true
database-migration-space-safety-mib: 256
database-migration-backup-retention-count: 1
database-shutdown-timeout-seconds: 15
database-shutdown-cancel-timeout-seconds: 5
database-health-check-interval-seconds: 5
database-operator-warning-interval-seconds: 60

log-explosions: true
log-fire-spread: true
log-liquid-flow: true
log-pistons: true

lookup-page-size: 15
max-lookup-radius: 150

gui-lookup-fetch-size: 1000
gui-activity-max-gap-millis: 2500
gui-activity-max-distance: 6
gui-activity-max-duration-millis: 15000
gui-activity-max-span: 24

max-rollback-radius: 100
rollback-blocks-per-tick: 500
rollback-max-millis-per-tick: 4.0
rollback-minimum-tps: 18.0
rollback-max-blocks-per-command: 50000
rollback-max-snapshot-bytes-per-command: 67108864
rollback-max-chunks-per-command: 256
rollback-confirmation-timeout-seconds: 60
apply-physics-during-rollback: false
```

### GUI settings

- `gui-lookup-fetch-size` controls how many **lightweight** rows grouped GUI browsing scans per SQLite fetch. It is a per-fetch work/memory bound, **not a cap on total history that can be browsed**. The default is `1000`.
- `gui-activity-max-gap-millis` is the maximum local time gap between consecutive records in one presentation activity.
- `gui-activity-max-distance` is the maximum local block distance used by the grouping heuristic.
- `gui-activity-max-duration-millis` caps the total duration of one activity.
- `gui-activity-max-span` caps each X/Y/Z bounding-box span of one activity.

The old `gui-lookup-max-rows` hard refusal is no longer used. Existing server configs that still contain it may remove it; if `gui-lookup-fetch-size` is absent, FragGuard uses the default of `1000`.

Changing grouping thresholds changes only presentation. Exact raw history remains stored and reachable.

## Build

Requires Java 25. The Gradle 9.7.1 wrapper is committed to the repository and its downloaded distribution is verified by SHA-256.

The current development feature version is `26.3-1.2.0`. Development remains compiled against Paper API `26.2.build.121-stable`, with `api-version: '26.2'`, while Paper 26.3 stabilizes.

```bash
./gradlew build
```

The plugin JAR is produced under:

```text
build/libs/FragGuard-26.3-1.2.0.jar
```

Put the JAR in the server's `plugins` folder and restart Paper.

## Notes and limitations

- The `26.3-1.2.0` GUI implements investigation, structured filtering, rollback preset/preview/confirmation controls, and a browser for undoable rollback jobs.
- Large GUI lookups use bounded database-backed pages/streaming rather than a total-result refusal.
- GUI activity grouping is presentation-only and every represented exact event remains reachable.
- GUI filters are pushed into SQLite rather than filtering an already-materialized full snapshot.
- Block-entity payloads remain lazy and are fetched only for the exact event being inspected.
- Supported block entities include signs, containers and their items/books, banners, player-head profiles/textures, lecterns, decorated pots, and supported custom names.
- History recorded before block-entity snapshots were introduced cannot reconstruct block-entity contents that were never saved.
- Rollback previews remain independently bounded by configured block, chunk, and aggregate snapshot-byte limits.
- Rollback/undo work pauses below `rollback-minimum-tps`; set it to `0` to disable TPS pausing.
