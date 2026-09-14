# FragGuard

FragGuard is a Paper plugin that records world-changing block history in SQLite, gives server operators both command-based and Minecraft-native GUI tools for investigating that history, and can roll an area back to the state it was in at a chosen time.

History is retained for 30 days by default. The new `26.3-1.2.0` inventory GUI condenses related events for easier browsing without deleting, rewriting, or hiding the underlying raw history: every exact database event remains available for drill-down.

## What it tracks

- Player block placement
- Player block destruction
- Explosion block changes
- TNT priming/removal with specific cause attribution
- Dragon egg source and destination changes
- Initial fire ignition and fire spread
- Blocks destroyed by fire burn events
- Lava and water flow
- Player bucket water/lava placement and removal
- Sponge absorption and dispenser bucket placement/removal
- Blocks broken by lava/water flow
- Piston extension and retraction changes
- Blocks broken or moved by pistons
- Entity-caused block changes and block formation
- Natural block growth, fading, formation, spread, and leaves decay
- Structure growth and fertilization
- Player interactions that change structural block data
- Player name and UUID when a player caused the change
- Projectile-shooter attribution when a player caused the event
- System cause labels when the server/environment caused the change
- World and block coordinates
- Time of change
- Block data before and after the change
- Supported block-entity contents before and after the change

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

`/fg` and `/fragguard` with no arguments open the in-game GUI. Existing subcommands continue to use the command interface.

## In-game GUI

`26.3-1.2.0` adds FragGuard's first Minecraft-native inventory GUI. It uses ordinary server-side Minecraft inventory elements and requires no client mod or resource pack.

```text
/fg
```

The main menu provides GUI access to lookup/history investigation, rollback setup and confirmation, undoable rollback jobs, storage status, and command help. The existing command workflows remain available alongside the GUI.

### GUI lookup setup

The Lookup History screen currently supports radius and time presets. Radius choices are bounded by `max-lookup-radius`, and time choices are bounded by the configured history retention period.

When a lookup starts, the selected time cutoff is passed directly into SQLite. SQLite therefore counts and selects only records inside the chosen GUI time window instead of scanning the entire retention period and discarding older rows afterward. This also means `gui-lookup-max-rows` is enforced against the selected lookup window itself.

If the selected area/time window contains more than `gui-lookup-max-rows`, FragGuard refuses to present a partial result set as complete and asks the operator to narrow the query.

### Condensed activities and raw history

GUI lookup results can be browsed in two forms:

- **Condensed view** groups nearby related records into human-readable activities.
- **Raw view** exposes the exact individual history records.

Activity grouping is presentation-only. FragGuard never collapses or rewrites the SQLite history rows. The current heuristic only groups consecutive events that match the same actor identity, action, and relevant material/state while also remaining within the configured local time/distance and whole-activity duration/span thresholds.

Condensed results support inventory-based pagination. Selecting an activity opens an activity-details screen, and from there the operator can drill down to the exact raw events that make up that activity.

The GUI also protects investigation state from asynchronous races. Every lookup receives a generation identity; if an older lookup finishes after a newer lookup, after the session is reset, or after the player leaves, the obsolete callback is discarded instead of reopening or replacing the current investigation.

### Structured lookup filters

After a lookup finishes, its results screen exposes structured **Player**, **Action**, and **Material** filters. Filter choices are generated only from values present in that returned lookup, so operators do not need to type or parse free-text filter expressions.

Player filters use the stored actor identity rather than only the display label, action filters use FragGuard's stored action identifiers, and material filters use the material represented by each exact history row. Filters affect presentation only: the immutable lookup snapshot and underlying SQLite history remain unchanged, and clearing the filters restores the complete returned lookup.

## Command lookup

```text
/fg lookup r:30
```

Shows recent block-change logs in a full-height cylinder around your current position. Results are paginated. The command-based lookup path remains available alongside the GUI and continues to use the configured retention window.

Use the page argument to move through command results:

```text
/fg lookup r:30 p:2
```

## Rollback

The GUI Rollback screen provides radius and time presets plus a conflict-protected/force-mode toggle. **Preview Rollback** runs the same existing rollback preview pipeline without changing blocks. After the preview completes, reopen `/fg` → **Rollback** and use **Confirm Active Preview** to execute the operator-bound preview token. The token still expires according to `rollback-confirmation-timeout-seconds`.

The command workflow remains available:

```text
/fg rollback r:30 t:2d 7h 15m
```

Previews every logged block change in a radius of 30 blocks back to the state it was in 2 days, 7 hours, and 15 minutes ago. The radius covers the full world height, from bedrock/minimum world height to maximum build height. The preview reports the affected block count, chunk count, and target time without changing any blocks.

To execute the preview, enter the single-use token displayed by FragGuard:

```text
/fg rollback confirm <token>
```

Confirmation tokens are tied to the operator and expire after 60 seconds by default. Every confirmed rollback receives a durable job ID. Reverse a completed or recoverable failed rollback with:

```text
/fg undo <job-id>
```

The GUI **Undo Rollback** screen reads the retained rollback-job records and lists completed or failed jobs that actually applied one or more block changes. Selecting a job opens a separate confirmation screen before the existing `/fg undo` execution path is invoked. Already-undone and no-op jobs are not presented as undoable choices.

Rollback and undo progress is stored in SQLite. Interrupted jobs automatically resume after a server restart, and overlapping jobs in the same world are rejected. Completed rollback jobs remain available for `/fg undo` until `rollback-job-retention-days`; expired completed/undone and permanently failed jobs are deleted with their saved snapshots, while active and recoverable failed jobs are retained.

Changes are processed in consecutive same-chunk batches without changing saved sequence order. Existing chunks load asynchronously without generating terrain, temporary chunk tickets prevent an active chunk from unloading during audit persistence, and main-thread work respects both a per-tick time budget and block cap. Rollback/undo work pauses automatically while server TPS is below the configured minimum.

FragGuard also revalidates the live block state before mutation. Normal rollback skips conflicts. Force rollback retries from the latest observed state rather than blindly overwriting concurrent edits, and unresolved undo conflicts remain retryable.

## Storage status

```text
/fg status
```

Shows database health, bounded write/control queue usage, coalesced same-tick changes, and dropped-write warnings. Block changes are written through one long-lived SQLite connection in transactional batches; lookups and rollback previews read all changes accepted before the query was submitted.

Expected timed-query cancellation and timeout results are treated separately from genuine SQLite storage failures, so an intentionally cancelled or timed-out investigation does not by itself mark the database unhealthy.

## Database upgrades and recovery

FragGuard records its SQLite schema version in `PRAGMA user_version`. The current schema version is **4**. Older supported databases are upgraded automatically to version 4 when the plugin starts.

FragGuard now classifies migrations by what they can actually endanger. Migrations that rewrite persistent schema/data still create a consistent SQLite snapshot with `VACUUM INTO`, verify it with `PRAGMA quick_check`, and save it under a name such as:

```text
plugins/FragGuard/backups/fragguard.db.pre-migration-v<old>-to-v4-<timestamp>.bak
```

The schema-v3 to schema-v4 migration is different: the persistent same-tick coalescing index is derived data and is no longer needed. FragGuard verifies the live database, transactionally removes `idx_fg_tick_coalesce` if it still exists, verifies the database again, and only then advances `PRAGMA user_version` to 4. It does **not** create a full history backup and does **not** build a replacement multi-gigabyte index. A retry from schema v3 where a previous attempt already removed the index is explicitly supported. Because this step no longer allocates a large replacement structure, the full-backup migration free-space preflight is not used for v3→v4.

Schema v4 performs cross-flush same-tick coalescing with a small bounded in-memory row-ID cache for the current server session. Compatible changes update the exact persisted row directly by primary-key ID, incompatible actors/actions replace the candidate, net no-ops delete that exact row, and old candidates expire after a short tick window or when the cache reaches its hard cap. Eviction can only reduce coalescing efficiency; it never discards an underlying history event.

For full-backup migrations that actually change persistent schema/data, failed or unverifiable partial backups are removed when possible. Verified backups created by this policy receive a verification marker; successful migrations prune only those explicitly verified backups according to `database-migration-backup-retention-count`, preserving at least one by default. Older unmarked backups are never auto-deleted.

Full-backup migrations perform a disk-space preflight using Java's filesystem-reported usable space plus `database-migration-space-safety-mib`. On managed hosting, that filesystem value can be much larger than the account's real storage quota, so FragGuard logs it specifically as a filesystem value rather than claiming it is the usable hosting quota. `database-migration-space-preflight-enabled` can be explicitly disabled if the host filesystem value itself is unusable; SQLite failures still abort without advancing the schema version.

A database using a schema newer than the installed FragGuard version is rejected rather than silently opened by older code.

Large databases can take longer than 30 seconds to open, back up, verify, or migrate. FragGuard reports periodic startup progress instead of treating that duration as a failure. `database-startup-timeout-seconds` defaults to `0`, which disables an arbitrary hard cutoff; set it above `0` only if you explicitly want SQLite initialization aborted after that many seconds.

Before upgrading, stop the server and copy the entire `plugins/FragGuard` directory to a separate location. Do not copy only `fragguard.db` while the server is running: active SQLite changes can still be in `fragguard.db-wal`.

To restore a pre-migration backup:

1. Stop the Paper server completely.
2. Preserve the current `plugins/FragGuard/fragguard.db` separately for investigation.
3. Copy the selected `.bak` file to `plugins/FragGuard/fragguard.db`.
4. Remove any stale `plugins/FragGuard/fragguard.db-wal` and `plugins/FragGuard/fragguard.db-shm` files.
5. Install a FragGuard release that supports the restored schema and restart the server.

World history and rollback jobs use the world's UUID as their identity while retaining readable world names. Renaming a world therefore preserves its history and resumable rollback jobs as long as the world's original UUID, including its `uid.dat`, is preserved. Block-change actions use stable storage identifiers; unrecognized actions remain in the database and appear as generic changes instead of making an entire lookup fail.

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

gui-lookup-max-rows: 5000
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

- `gui-lookup-max-rows` is the maximum number of exact records a single GUI lookup may load. The count is evaluated in SQLite against the GUI's selected radius and time window.
- `gui-activity-max-gap-millis` is the maximum local time gap allowed when presentation-only activity grouping considers two consecutive records related.
- `gui-activity-max-distance` is the maximum local block distance used by that grouping heuristic.
- `gui-activity-max-duration-millis` caps the total duration of one condensed activity so locally adjacent chains cannot extend indefinitely through time.
- `gui-activity-max-span` caps the X/Y/Z bounding-box span of one condensed activity so locally adjacent chains cannot extend indefinitely through space.

Reducing the grouping thresholds creates more, smaller activities. Increasing them can condense more nearby matching events, but the exact raw records are always retained and remain available through the raw view.

## Build

Requires Java 25. The Gradle 9.7.1 wrapper is committed to the repository and its downloaded distribution is verified by SHA-256.

The current development feature version is `26.3-1.2.0`. FragGuard versions use the Paper compatibility line followed by the plugin semantic version. Release tags may optionally start with `v`, and prereleases append suffixes such as `-beta.1` or `-rc.1`.

Development is intentionally still compiled against Paper API `26.2.build.121-stable`, with `api-version: '26.2'`, while Paper 26.3 stabilizes. The `26.3-1.2.0` project version identifies the target feature/release line; it does not mean the development branch has switched its Paper API dependency to an unstable 26.3 build.

```bash
./gradlew build
```

Dependencies are pinned and the committed Gradle lock state is checked by CI so ordinary pushes, pull requests, and release builds resolve the reviewed versions.

The plugin JAR will be in:

```text
build/libs/FragGuard-26.3-1.2.0.jar
```

Put that JAR into your server's `plugins` folder and restart the Paper server.

## Notes and limitations

- The `26.3-1.2.0` inventory GUI implements lookup/investigation, structured player/action/material filtering, rollback preset/preview/confirmation controls, and a browser for undoable rollback jobs. The equivalent command workflows remain available.
- GUI activity grouping is presentation-only. It does not delete, merge, or rewrite stored history, and each activity can be opened to inspect its exact raw events.
- GUI filters are generated from the exact lookup result set and affect only the presentation of that immutable result snapshot.
- GUI time presets are applied directly in SQLite, so short windows do not count or return unrelated older retained history before filtering.
- Stale asynchronous GUI lookup and undo-job-list completions are discarded when a newer request owns the session or when the GUI session has been reset/abandoned.
- This restores block type and structural block data, including facing direction, slab state, stair shape, and similar properties, before restoring supported block-entity contents.
- Supported block entities include both sides of signs (text, color, glowing text, and wax), container inventories and their books/items, banner patterns, player-head profiles/textures, lectern books/pages, decorated-pot items/sherds, and supported custom names.
- Block entities outside those supported types, and contents from history recorded before block-entity snapshots were introduced, cannot be reconstructed.
- Explosion, ignition/fire, bucket/liquid/sponge, piston, entity, growth/form/fade/decay/fertilization, and player-interaction handlers record the before-state during the event and the actual resulting state on the next server tick so the saved log matches what the server changed.
- Fire-burn and bucket-source logging was added after the first version. Old damage that happened before installing those updates cannot be rolled back unless it was already logged.
- Rollback previews are capped inside SQLite at the configured maximum plus one and use indexed world/chunk coordinates instead of loading an entire region's history.
- Rollback previews enforce block-count, chunk-count, and aggregate block-entity snapshot-byte limits before execution; the snapshot budget defaults to 64 MiB and is checked before SQLite BLOBs are copied into server memory.
- Saved rollback jobs, restart recovery, and undo apply the same snapshot-byte budget to original, target, and expected entity data before loading their changes; recovered jobs also enforce the configured chunk-count limit.
- Rollbacks and undo operations load existing chunks asynchronously, hold only their active chunk with a plugin ticket, and apply consecutive same-chunk batches in saved sequence order within a configurable shared per-tick time budget.
- Rollback and undo work pauses when recent server TPS falls below `rollback-minimum-tps`; set the threshold to `0` to disable automatic pausing.

## Gradle / IntelliJ note

This project uses the current GradleUp Shadow plugin:

```kotlin
id("com.gradleup.shadow") version "9.6.1"
```

The older `com.github.johnrengelman.shadow` plugin line can fail when IntelliJ imports/builds the project with Gradle 9.x.
