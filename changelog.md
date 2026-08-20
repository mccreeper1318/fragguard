# FG Changelog

## 26.2-3-beta.2

### Fixed

- Fixed #27 by using the actual Paper server tick as the database coalescing key instead of 50 ms wall-clock buckets.
- Preserved the original event tick for deferred next-tick block snapshots so unrelated changes from different ticks cannot be merged.
- Restored pressure-driven database draining during the current server tick so large explosions or other bursty events do not turn the bounded queue capacity into a per-tick data-loss limit.
- Added persisted same-tick coalescing keyed by server session and Paper tick, allowing later writes in the same tick to merge correctly even after an earlier batch has already been written to SQLite.
- Counted SQLite cross-flush same-tick upserts in the `/fg status` coalesced-write metric so status reporting includes both in-memory and persisted coalescing.
- Removed persisted same-tick net no-op rows when a coordinate returns to its original state after separate flushes.
- Added regression coverage for same-tick changes that cross wall-clock buckets, distinct server ticks inside the same 50 ms bucket, pressure draining without advancing the tick, coalescing across separate database flushes, cross-flush coalesce metrics, net no-op cleanup, and matching tick numbers across server restarts.
- Fixed release builds failing version validation by aligning the Gradle project base version with the `26.2-3` release line; prerelease workflows can now apply tags such as `26.2-3-beta.2` correctly.
- Fixed #6 by recording every block actually changed by a rollback or undo as a `ROLLBACK` history entry before the programmatic world change is applied.
- Rollback audit entries retain the initiating operator UUID/name, the exact before/after block data, and identify the originating rollback job in the stored/displayed actor label.
- Added a nested, exception-safe logging suppression scope around programmatic rollback writes so rollback-triggered physics or future programmatic-change listeners cannot create duplicate ordinary history entries.
- Added regression coverage for rollback audit attribution, job identification, block transitions, undo labeling, and suppression restoration.

## 26.2-3-beta.1

### Added

- Added regression coverage for waterlogged blocks, ice becoming water, doors, beds, propagated physics changes, and multiple same-tick breaks.
- Added dedicated handling for `BlockMultiPlaceEvent` so every replaced block state is logged at its own coordinate with the corresponding after-state.
- Added regression coverage for beds, doors, tall plants, pointed dripstone, and ordinary single-block placement.
- Added expiring, operator-bound `/fg rollback confirm <token>` confirmation and durable rollback job IDs.
- Added `/fg undo <job-id>`, persisted per-block original states and batch progress, rejected overlapping jobs, and automatically resumed interrupted rollback/undo jobs after restart.
- Added exact indexed region filtering, cancellable query timeouts, and visible rollback-preview progress updates.
- Added bounded write and operation queues, transactional write batches, same-tick coordinate coalescing, fair query scheduling, and read-your-writes barriers.
- Added queue-depth/storage-health warnings, configurable capacity and timeout settings, and the `/fg status` operator command.

### Changed

- Deferred player break logging until the next server tick so FragGuard records the block data that actually remains after the break instead of assuming air.
- Captured adjacent paired or attached blocks and incorporated related physics events into the same player break snapshot.
- Coalesced multiple break and physics events from the same player during one tick so each changed coordinate is recorded once.
- Prevented the inherited ordinary `BlockPlaceEvent` path from logging the primary block a second time.
- Changed `/fg rollback` to show the affected blocks, chunks, and target time without modifying the world.
- Persisted indexed world UUID and chunk coordinates, including automatic migration of existing block history.
- Selected each coordinate's earliest rollback state directly in SQLite with `ROW_NUMBER()` and enforced `rollback-max-blocks-per-command + 1` in the SQL query.
- Replaced per-change SQLite connections with one dedicated database worker and long-lived connection.
