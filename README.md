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
/fg lookup u:Builder p:2
/fg lookup u:Builder t:2d p:2
/fg lookup u:Builder w:world_nether t:6h
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

### Global player lookup

Player scope is separate from nearby radius lookup. Use **one** scope at a time:
`r:<radius>` or `u:<username-or-uuid>`; combining them is rejected.

```text
/fg lookup u:Builder
/fg lookup u:Builder p:2
/fg lookup u:Builder t:2d p:2
/fg lookup u:Builder w:world_nether t:6h
/fg lookup u:11111111-2222-3333-4444-555555555555
```

A player lookup searches all worlds by default, regardless of the operator's location.
`w:<world>` limits it to one world and `t:<duration>` limits it to a recent
period (such as `t:1d` or `t:6h`); when `t:` is omitted, the full retained
history is searched, **never older than `retention-days`**. `p:<page>` selects
a page. Subsequent-page suggestions keep the same player, world and time scope.
Names are case-insensitive, include historical renames, and can be replaced with
canonical or compact UUIDs. An ambiguous reused username is rejected so
FragGuard never selects a player by guesswork.

Results include the latest player name, world, coordinates, action, before/after
block transition, timestamp and age. They include both directly authored
changes and **confidently attributed indirect changes** from TNT, projectiles
and supported piston/flying-machine chains. Indirect rows show the immediate
physical actor (for example, `Explosion: TNT` or `Piston Extend`) and the
stored causal type instead of pretending that the player directly broke blocks.

The global player query runs on FragGuard's bounded, cancellable database
worker after earlier writes are flushed. It uses UUID/time indexes, deterministic
timestamp+row-ID ordering, and bounded lightweight pages without fetching
container/other block-entity BLOBs. A new query or logout invalidates an older
player-lookup completion. It does not alter the existing radius lookup or
the GUI area lookup's separate Player/Action/Material filters. A future issue
(#104) will add a dedicated global-player scope to the GUI.

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

## Causal player attribution for TNT and machinery

FragGuard's schema **v6** preserves the immediate physical source in
`actor_uuid`/`actor_name` and records optional, separate
`causal_player_uuid`, `causal_player_name`, `causal_chain_id`, and
`causal_cause_type`. The v5-to-v6 migration adds nullable columns and
indexed causal UUID/time queries **without rewriting older history or rollback jobs**.
Unattributed historical rows remain valid and their causal fields are null.
Player-wide lookup and rollback in follow-up issues #101/#102 will match both
the direct actor UUID and the causal player UUID; the persisted physical cause
will remain available for inspection.

Supported evidence includes direct player TNT ignition, player-launched
projectiles, TNT-to-TNT priming with an observable entity source, and TNT
entities spawned at an **exact** recently observed priming location.
Actual player-operated levers, buttons and pressure plates (not passive
right-clicks on pistons or observers), directly altered mechanism components,
live redstone updates, and piston movements form a bounded graph of
actual component and block movement positions. An active, connected component
can attribute a piston or TNT at that component, including an observable
piston-driven TNT duplicator. A detached or ambiguously powered machine, a
different player's restart/modification, or a source absent from Paper's events
fails closed to system/physical attribution. Merely being near a player, or
happening after an earlier interaction, does not confer ownership.

Causal links are maintained **only in memory**; they are never resumed after
restart. Mechanism links expire after 4 seconds idle or 30 seconds total.
An individually observed TNT entity is retained up to 60 seconds from its
own spawn to accommodate slow fuse progression at reduced TPS. No entity
can restart expired mechanism ownership merely by existing nearby.
Tracked mechanisms cannot extend farther than 96 blocks on any axis from
the original activation, and are capped to 2,048 block links, 512 TNT
entity links, and 256 recent prime locations. A world unload, plugin reload/disable or memory pressure
clears relevant links. Cross-session or conflicting ownership invalidates the
claim instead of guessing. Fires, unobservable redstone components,
untraceable TNT duper cycles, and delayed environmental propagation are not
automatically assigned to the triggering player. Missing attribution is
preferable to blaming the wrong player.

Causal attribution is now included in command-based global player lookup.
Player-scoped rollback, historical conflict protection, and the dedicated
global-player GUI workflow follow in later 26.3-1.2.1 issues. Final integration tests on the
live Paper server are reserved for the end of the update.

## Historical player identity and actor indexes

FragGuard resolves players from **retained block history** rather than online player
sessions. Its shared database resolver accepts `u:PlayerName`, `PlayerName`, a
hyphenated UUID, or a 32-character UUID. Historical names resolve to the player's
canonical UUID, and the latest recorded name is returned for display. Reused
names belonging to more than one UUID are rejected instead of guessed; unknown
players also produce a clear error. Environmental/system actors are not treated
as players.

Database schema **v5** added actor UUID/time, actor UUID/world/time,
and case-insensitive direct usernames. Schema **v6** added nullable causal
identity fields and indexed causal UUID/time lookups. Schema **v7** adds an
index for historical causal usernames. Each migration preserves existing
history, container snapshots and saved rollback jobs.
Index creation may take time on large databases; a normal startup migration
must complete before the server begins using FragGuard's database.

The shared resolver now supports command-based global player lookup with
`u:`, including names appearing only in causal history. Selective player
rollback commands remain part of later 26.3-1.2.1 issues.

## SQLite native access on Java 24+

FragGuard uses Xerial SQLite JDBC's JNI-based native SQLite library. Java 24 and later
warn when code on the class path loads a native library without explicit permission.
Java 25 still permits the call with a warning by default, but a future Java version
may deny it. Upgrading SQLite JDBC does not remove this requirement.

For Paper servers running Java 24+, include **`--enable-native-access=ALL-UNNAMED`**
as a **Java launcher option before `-jar`**:

```bash
java --enable-native-access=ALL-UNNAMED -jar paper.jar nogui
```

Use your server's actual Paper JAR filename and keep your existing memory and
other startup options. If a hosting panel cannot edit Java launcher arguments,
the equivalent `JDK_JAVA_OPTIONS` environment variable can be used where supported.
This option authorizes unnamed-module native access for the **whole JVM**, not only
FragGuard. It cannot be enabled by placing a manifest entry inside the plugin JAR:
Paper starts the JVM, and FragGuard is loaded afterward as a plugin.

On Java 24/25, leaving the option out causes a warning rather than an immediate
SQLite failure under the default JVM policy. The precise future Java release
that will deny access by default has **not** been announced. To verify the
future stricter policy today, launch with both
`--illegal-native-access=deny` and `--enable-native-access=ALL-UNNAMED`.
Gradle tests run with both options on Java 25, checking that SQLite initializes
and supports normal reads and writes with native-access denial enabled.

## Rollback area boundary preview

After a successful rollback preview from `/fg rollback` or the rollback GUI,
FragGuard displays a **player-only** circular particle boundary in the previewed
world. Cyan marks the radius, yellow guides show its full vertical extent,
and green marks the original rollback center. The preview is a cylinder:
the radius is measured on X/Z and applies throughout the world's build height.
It is a visual guide to the actual block-coordinate radius, not a change to
rollback selection or history.

Only the operator who created the preview receives these particles. They make
no block or entity changes and require no client mod. The boundary refreshes
while the confirmation token is valid. The outline stops emitting on expiration,
confirmation, replacement, cancellation, player logout, or plugin shutdown.
Previously emitted particles fade naturally. It is not shown in other worlds;
returning to the previewed world restores the display only while the token
remains valid.

Use `/fg rollback cancel` (or **Cancel Active Preview** in the rollback GUI)
to invalidate your pending preview without starting a rollback job. Cancelling
also invalidates any outstanding asynchronous rollback-preview search so a
late query result cannot recreate a cancelled preview. Cancelling when nothing
is active is harmless.

### Boundary visualization settings

The particle outline is presentation-only and **does not change the rollback radius**.
All settings are optional; existing server configurations use these defaults:

| Setting | Default | Behavior |
| --- | --- | --- |
| `rollback-preview-visualization-enabled` | `true` | Disable visual particles without disabling rollback previews |
| `rollback-preview-perimeter-quality` | `1` | 0=sparse, 1=standard, 2=detailed; clamped to 0–2 |
| `rollback-preview-vertical-spacing` | `64` | Requested blocks between vertical guides; clamped to 16–192 |
| `rollback-preview-operator-ring-enabled` | `true` | Show a ring at the operator's current height |
| `rollback-preview-update-ticks` | `20` | Refresh interval (10–100 ticks) |
| `rollback-preview-border-color` | `"#00FFFF"` | Cyan perimeter ring; use `#RRGGBB` |
| `rollback-preview-height-color` | `"#FFFF00"` | Yellow vertical/top/bottom guides |
| `rollback-preview-center-color` | `"#00FF00"` | Green origin marker |

With standard density, the perimeter uses **24 points for radius 1–10,
32 for 11–25, 48 for 26–50, and 64 for 51+**. Quality adjusts this by
a small amount and always caps it at 96. Vertical columns use up to 12
interior samples each, even in unusually tall worlds. The operator-height
ring follows the operator as they fly or walk, using the refresh interval
rather than rebuilding the boundary geometry every tick. Colors with
invalid hex strings revert to the built-in defaults. The renderer uses
player-targeted extended-range particles; other players never receive
them, and no world blocks or entities change.

## Build

Requires Java 25. The Gradle 9.8.1 wrapper is committed to the repository and its downloaded distribution is verified by SHA-256.

The current development version is `26.3-1.2.1`. Development targets Paper API `26.3.build.177-stable`, with `api-version: '26.3'`.

```bash
./gradlew build
```

The plugin JAR is produced under:

```text
build/libs/FragGuard-26.3-1.2.1.jar
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
