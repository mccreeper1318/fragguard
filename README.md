# FragGuard

FragGuard is a Paper plugin that logs block changes for 30 days, lets server operators inspect block history in a radius, and rolls an area back to the state it was in at a chosen time.

## What it tracks

- Player block placement
- Player block destruction
- Explosion block changes
- Fire spread
- Blocks destroyed by fire burn events
- Lava and water flow
- Player bucket water/lava placement and removal
- Blocks broken by lava/water flow
- Piston extension and retraction changes
- Blocks broken or moved by pistons
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

- This restores block type and block data, including things like facing direction, slab state, stair shape, and similar block data.
- It does not restore inventories inside containers, sign text, books, or other tile-entity contents.
- Explosion, fire burn, bucket, liquid, and piston handlers record the before-state during the event and the after-state on the next server tick so the saved log matches what the server actually changed.
- Fire-burn and bucket-source logging was added after the first version. Old damage that happened before installing this update cannot be rolled back unless it was already logged.
- Rollback previews are capped inside SQLite at the configured maximum plus one, and use indexed world/chunk coordinates instead of loading an entire region's history.
- Rollback previews and recovered jobs enforce both block-count and chunk-count limits before execution.
- Rollbacks and undo operations load existing chunks asynchronously, hold only their active chunk with a plugin ticket, and apply consecutive same-chunk batches in saved sequence order within a configurable shared per-tick time budget.
- Rollback and undo work pauses when recent server TPS falls below `rollback-minimum-tps`; set the threshold to `0` to disable automatic pausing.


## Gradle / IntelliJ note

This project uses the current GradleUp Shadow plugin:

```kotlin
id("com.gradleup.shadow") version "9.4.2"
```

The older `com.github.johnrengelman.shadow` plugin line can fail when IntelliJ imports/builds the project with Gradle 9.x.
