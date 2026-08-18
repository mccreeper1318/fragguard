# FG Changelog

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
