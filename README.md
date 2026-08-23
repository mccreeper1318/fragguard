# FragGuard

FragGuard is a Paper plugin that logs block changes for 30 days, lets server operators inspect block history in a radius, and rolls an area back to the state it was in at a chosen time.

## What it tracks

- Player block placement
- Player block destruction
- Explosion block changes
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
- System cause labels when the server/environment caused the change
- World and block coordinates
- Time of change
- Block data before and after the change

FragGuard stores data in `plugins/FragGuard/fragguard.db` using SQLite.

## Commands

Only server operators with `fragguard.admin` can use these commands.

```text
/fg help
/fg lookup r:30
/fg lookup r:30 p:2
/fg rollback r:30 t:2d 7h 15m
/fg rollback confirm <token>
/fg undo <job-id>
/fg status
```

### Lookup

```text
/fg lookup r:30
```

Shows recent block-change logs in a full-height cylinder around your current position. Results are paginated.

### Rollback

```text
/fg rollback r:30 t:2d 7h 15m
```

Previews every logged block change in a radius of 30 blocks back to the state it was in 2 days, 7 hours, and 15 minutes ago. The radius covers the full world height, from bedrock/minimum world height to maximum build height. The preview reports the affected block count, chunk count, and target time without changing any blocks.

To execute the preview, enter the single-use token displayed by FragGuard:

```text
/fg rollback confirm <token>
```

Confirmation tokens are tied to the operator and expire after 60 seconds by default. Every confirmed rollback receives a durable job ID. Reverse a completed or failed rollback with:

```text
/fg undo <job-id>
```

Rollback and undo progress is stored in SQLite. Interrupted jobs automatically resume after a server restart, and overlapping jobs in the same world are rejected. Changes are processed in consecutive same-chunk batches without changing saved sequence order, existing chunks load asynchronously without generating terrain, and temporary chunk tickets prevent an active chunk from unloading during audit persistence. Main-thread work respects both a per-tick time budget and block cap, and pauses automatically while server TPS is below the configured minimum.

### Storage status

```text
/fg status
```

Shows database health, bounded write/control queue usage, coalesced same-tick changes, and dropped-write warnings. Block changes are written through one long-lived SQLite connection in transactional batches; lookups and rollback previews read all changes accepted before the query was submitted.

## Database upgrades and recovery

FragGuard records its SQLite schema version in `PRAGMA user_version`. Existing unversioned and version-1 databases are upgraded automatically to version 2 when the plugin starts. Before changing an existing schema, FragGuard creates a consistent SQLite snapshot with `VACUUM INTO`, verifies it with `PRAGMA quick_check`, and saves it under:

```text
plugins/FragGuard/backups/fragguard.db.pre-migration-v1-to-v2-<timestamp>.bak
```

The filename uses the database's actual starting version, so an unversioned database instead creates a `v0-to-v2` backup. Every migration and its schema-version update run in a single transaction. If the backup cannot be created or verified, or if the migration fails, FragGuard refuses to start instead of continuing with a partially upgraded database. A newer database schema is also rejected rather than silently opened by an older FragGuard release.

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
database-write-queue-capacity: 20000
database-operation-queue-capacity: 256
database-write-batch-size: 500
database-query-timeout-seconds: 15
database-shutdown-timeout-seconds: 15

log-explosions: true
log-fire-spread: true
log-liquid-flow: true
log-pistons: true

lookup-page-size: 15
max-lookup-radius: 150

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

## Build

Requires a JDK that supports the Paper 26.2 toolchain, currently Java 25 in the project setup.

```bash
gradle build
```

If your IDE creates a Gradle wrapper for the project, you can use `./gradlew build` instead.

The plugin JAR will be in:

```text
build/libs/FragGuard-1.0.1.jar
```

Put that JAR into your server's `plugins` folder and restart the Paper server.

## Notes and limitations

- This restores block type and structural block data, including facing direction, slab state, stair shape, and similar properties, before restoring supported block-entity contents.
- Supported block entities include both sides of signs (text, color, glowing text, and wax), container inventories and their books/items, banner patterns, player-head profiles/textures, lectern books/pages, decorated-pot items/sherds, and supported custom names.
- Block entities outside those supported types, and contents from history recorded before block-entity snapshots were introduced, cannot be reconstructed.
- Explosion, ignition/fire, bucket/liquid/sponge, piston, entity, growth/form/fade/decay/fertilization, and player-interaction handlers record the before-state during the event and the actual after-state on the next server tick so the saved log matches what the server changed.
- Fire-burn and bucket-source logging was added after the first version. Old damage that happened before installing this update cannot be rolled back unless it was already logged.
- Rollback previews are capped inside SQLite at the configured maximum plus one, and use indexed world/chunk coordinates instead of loading an entire region's history.
- Rollback previews enforce block-count, chunk-count, and aggregate block-entity snapshot-byte limits before execution; the snapshot budget defaults to 64 MiB and is checked before SQLite BLOBs are copied into server memory.
- Saved rollback jobs, restart recovery, and undo apply the same snapshot-byte budget to original, target, and expected entity data before loading their changes; recovered jobs also enforce the configured chunk-count limit.
- Rollbacks and undo operations load existing chunks asynchronously, hold only their active chunk with a plugin ticket, and apply consecutive same-chunk batches in saved sequence order within a configurable shared per-tick time budget.
- Rollback and undo work pauses when recent server TPS falls below `rollback-minimum-tps`; set the threshold to `0` to disable automatic pausing.


## Gradle / IntelliJ note

This project uses the current GradleUp Shadow plugin:

```kotlin
id("com.gradleup.shadow") version "9.4.2"
```

The older `com.github.johnrengelman.shadow` plugin line can fail when IntelliJ imports/builds the project with Gradle 9.x.
