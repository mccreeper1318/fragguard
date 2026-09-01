# FG Changelog

## 1.1.0

### Added

- Added comprehensive history coverage for ignition, sponge absorption, dispenser buckets, entity changes, natural growth/fading/forming/spread, leaves decay, structure growth, fertilization, and player interactions that persistently mutate block state.
- Added accurate multi-block placement and break tracking, including paired blocks, attached blocks, waterlogging, and propagated physics changes.
- Added expiring, operator-bound rollback confirmations, durable rollback job IDs, `/fg undo <job-id>`, overlap prevention, restart recovery, and visible preview progress.
- Added configurable rollback safeguards for per-tick execution time, minimum TPS, affected chunks, affected blocks, snapshot bytes, and cancellable query timeouts.
- Added compressed, versioned block-entity snapshots for signs, containers and books, banners, player heads, lecterns, decorated pots, and supported custom names.
- Added bounded transactional SQLite write and operation queues, same-tick coalescing, fair query scheduling, read-your-writes barriers, storage-health warnings, and `/fg status`.
- Added explicit SQLite schema versioning, verified pre-migration backups, stable action identifiers, and stable world UUID handling across renames.
- Added read-only CI builds, verified release packaging and checksums, dependency locking, pinned GitHub Actions, and weekly Dependabot checks.
- Added broad regression coverage for event capture, rollback/undo ordering and recovery, database pressure and shutdown, migrations, snapshots, parsing limits, and release packaging.

### Changed

- Adopted plain semantic release versions beginning with `1.1.0`; release tags may use `1.1.0`, `v1.1.0`, or matching `-beta.N` and `-rc.N` suffixes.
- Event handlers now capture the before-state during the event and the actual resulting state on the next server tick while preserving the original event tick for coalescing.
- Player, projectile-shooter, entity, and environmental changes now retain the most specific available actor and cause attribution.
- Interaction logging now ignores temporary buttons, pressure plates, tripwires, and bed occupancy, and snapshots only containers whose structure can change through the interaction.
- Rollback is now a preview-first workflow that reports affected blocks and chunks, selects each coordinate's earliest state in SQLite, and applies indexed region and block limits in the query.
- Rollback and undo preserve persisted sequence order, load only existing chunks asynchronously, retain chunk tickets only while needed, and share TPS and time budgets across preparation and mutations.
- Rollback and undo audits are committed in bounded slices before world mutation; physics corrections are recorded durably before later work can pause.
- Normal rollback revalidates expected live state and skips conflicts, while force mode revalidates and retries from the latest state; unresolved undo conflicts remain retryable.
- SQLite now uses one dedicated worker and long-lived connection, drains under queue pressure, and reports atomic queued, drained, remaining, and lost-write shutdown accounting before checkpointing the WAL.
- Updated Paper API to `26.2.build.62-beta`, SQLite JDBC to `3.53.2.1`, Shadow to `9.6.1`, and SLF4J NOP to `2.0.18`.

### Fixed

- Fixed #16 by covering world mutations that previously bypassed lookup and rollback, including player-fired projectile ignition attribution and inherited growth/formation listener conflicts.
- Fixed structural inventory interactions losing their before/after snapshots, lectern book removal bypassing history, and unchanged ordinary container opens creating false positives or expensive snapshots.
- Fixed temporary button, pressure-plate, tripwire, and bed states being recorded as lasting changes that later caused false rollback conflicts.
- Fixed #8 by preventing large rollback and undo jobs from synchronously loading many chunks, monopolizing the server thread, reordering interleaved chunk visits, or retaining completed chunk tickets during pauses.
- Fixed deferred or corrected rollback work appearing in history before it was applied, and ensured physics-normalized states retain compatible block-entity contents.
- Fixed #14 by making migrations transactional and resilient to world renames, legacy or unknown action names, future schema versions, unavailable backup locations, and partial upgrades.
- Fixed #15 by restoring supported block-entity contents during rollback and undo and enforcing per-snapshot, collection, and aggregate memory limits before history or job data is loaded.
- Fixed migrated or interrupted jobs losing prepared block-entity snapshots needed for restart recovery and later undo.
- Fixed #27 by coalescing on the actual Paper server tick across queue flushes, preserving distinct ticks, counting persisted coalesces, and removing net no-op rows.
- Fixed #6 by reliably committing operator-attributed rollback and undo audit records before every programmatic world change, even when the gameplay queue is full.
- Fixed #7 by bounding plans to a snapshot, revalidating immediately before mutation, retracting stale audit rows, and excluding externally completed force retries from undo.
- Fixed crash-window rollback mutations being omitted from undo and stale undo conflicts being incorrectly finalized instead of remaining retryable.
- Fixed #9 by draining accepted writes before shutdown, checkpointing SQLite, accounting for queued and in-flight losses, preserving degraded health after any known loss, and rate-limiting operator warnings.
- Fixed #10, #11, and #12 by making dependency resolution reproducible, isolating release upload permissions and tokens, verifying the executable Gradle wrapper, and correcting build documentation.
- Fixed #13 by rejecting overflowing durations, over-limit retention expressions, and malformed or overflowing lookup radii instead of accepting unsafe values or silently using defaults.
- Fixed #36 by preventing paired fertilization events from relabeling bonemealed structures while retaining non-overlapping fertilization history.
- Fixed #18, #19, and #20 by updating and locking the identified runtime and build dependencies and validating the shaded plugin through CI.
