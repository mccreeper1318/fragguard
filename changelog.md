# FG Changelog

## 26.3-3-beta.3

### Added

- Added configurable `rollback-max-millis-per-tick`, `rollback-minimum-tps`, and `rollback-max-chunks-per-command` safeguards for rollback and undo execution.
- Added regression coverage for chunk grouping, negative chunk boundaries, separate-world chunk identities, preview chunk counting, asynchronous no-generation loads, shared chunk tickets, TPS pause/resume, per-tick budget sharing, and next-tick budget resets.
- Added rollback-planner and SQLite-backed regression coverage for interleaved chunk sequences, repeated chunk visits, and undo execution in the exact reverse persisted order.
- Added regression coverage ensuring only the currently executing rollback or undo slice publishes audit history, low-TPS and exhausted-budget pauses do not expose deferred blocks as changed, and already-committed audit slices finish before throttling resumes.

### Changed

- Processed rollback and undo changes in consecutive same-chunk batches without reordering their persisted sequence, asynchronously loaded only existing chunks without generating new terrain, and held reference-counted plugin chunk tickets only while their active chunk is being processed.
- Applied a shared millisecond-per-tick work budget across block-state preparation, world mutations, and force-mode retries while retaining the configured maximum blocks per batch.
- Automatically paused rollback and undo jobs when server TPS falls below the configured threshold, resumed them after recovery, and rejected previews or saved jobs that exceed the maximum affected chunk count.
- Prepared and durably recorded rollback or undo audits in bounded 16-block slices immediately before applying each slice, with later slices remaining unaudited while work is deferred.

### Fixed

- Fixed #8 by preventing large rollback and undo jobs from synchronously loading many chunks or monopolizing the server thread with fixed-size block batches.
- Fixed interleaved chunk visits being globally regrouped out of sequence, which caused physics-enabled rollbacks and undo operations to update cross-chunk neighbors in the wrong order.
- Fixed deferred rollback candidates appearing in `/fg lookup` or later time-based rollback calculations before their blocks were actually changed when a tick-budget limit or low TPS paused the job.

## 26.2-3-beta.2

### Added

- Added persisted same-tick coalescing keyed by server session and Paper tick, allowing later writes in the same tick to merge correctly even after an earlier batch has already been written to SQLite.
- Added a nested, exception-safe logging suppression scope for deterministic physics-disabled rollback writes so future programmatic-change listeners cannot create duplicate ordinary history entries.
- Added read-only GitHub Actions build checks for every branch push, pull request, and manual dispatch; checks compile and test the plugin, verify the expected JAR contents/version, and upload the verified JAR plus SHA-256 checksum as a workflow artifact.
- Added regression coverage for same-tick changes that cross wall-clock buckets, distinct server ticks inside the same 50 ms bucket, pressure draining without advancing the tick, coalescing across separate database flushes, cross-flush coalesce metrics, net no-op cleanup, and matching tick numbers across server restarts.
- Added regression coverage for rollback audit attribution, job identification, block transitions, undo labeling, suppression restoration, required audit persistence outside the bounded gameplay queue, and separate requested/observed physics transitions.
- Added regression coverage for snapshot-bounded rollback planning, expected live-state capture, conflict decisions, force decisions, stale audit retraction, and overlap rejection.
- Added regression coverage for crash-window rollback mutations whose world change succeeded before the batch's `applied` progress marker was committed.
- Added regression coverage for stale undo coordinates remaining unresolved and retryable until a later undo attempt succeeds.
- Added shutdown regression coverage proving queued current-tick logs drain to SQLite before close and the database worker completes its WAL checkpoint after the drain.
- Added shutdown accounting regression coverage ensuring abandoned writes are not reported as successfully drained, still-running in-flight write batches are surfaced as unconfirmed, and a live worker with work already removed from the operation queue cannot report zero remaining operations.
- Added regression coverage for degraded-storage warning throttling so the first operator alert is immediate while repeated alerts honor the configured interval.
- Added regression coverage for atomic pre-shutdown write accounting, including batches already claimed by the database worker when shutdown begins.
- Added regression coverage ensuring write loss earlier in the server session still prevents shutdown from being reported as clean even when no additional writes are lost during disable.

### Changed

- Preserved the original event tick for deferred next-tick block snapshots so unrelated changes from different ticks cannot be merged.
- Restored pressure-driven database draining during the current server tick so large explosions or other bursty events do not turn the bounded queue capacity into a per-tick data-loss limit.
- Rollback audit entries retain the initiating operator UUID/name, the exact before/after block data, and identify the originating rollback job in the stored/displayed actor label.
- Rollback and undo batches now wait for required audit records to commit before changing blocks, retrying transient database-operation queue backpressure instead of allowing an unaudited world change.
- Physics-enabled rollback writes now leave normal block listeners active and record a second reliable `ROLLBACK` transition whenever the block's observed state differs from the requested state before the job marks the batch processed.
- Rollback plans now persist an upper snapshot timestamp and the expected state of each target coordinate; normal rollbacks skip and count conflicting newer changes, while an explicit `force` option revalidates and retries against the latest live state before overwriting it.
- Stale rollback audit rows are retracted when live-state revalidation fails, and `/fg undo` now operates only on coordinates the rollback actually changed or durably prepared for mutation before an interrupted progress commit; force retries completed by another actor are marked conflicting and excluded.
- Undo conflicts now remain unresolved instead of being marked `undone`; an incomplete undo attempt stays retryable and reports the number of coordinates that still need another `/fg undo <job-id>` attempt.
- Shutdown now reports queued, drained, remaining, and lost writes; after all accepted work drains, the database worker runs `wal_checkpoint(TRUNCATE)` before closing its SQLite connection and warns if the worker or checkpoint does not complete.
- Shutdown cancellation now waits through a configurable second grace period after cancelling the active SQLite statement; if the worker still does not stop, queued writes are explicitly abandoned/count as lost, pending database operations are failed, and any active write batch is reported as unconfirmed.
- Shutdown `drained` reporting is now derived from successfully completed write batches instead of queue-depth shrinkage, so failed or abandoned writes cannot be presented as persisted.
- If the database worker is still alive after the cancellation grace period with no active write batch, shutdown conservatively accounts for one unconfirmed active database operation in the remaining-operation total so dequeued work cannot disappear from the report.
- Shutdown now stops write/operation admission and captures queued, in-flight, completed, and dropped write accounting in one database snapshot before draining; batch claim/completion transitions use the same accounting lock so a write cannot appear queued in one counter snapshot and completed in another.
- Any confirmed dropped log or unavailable SQLite worker now keeps storage visibly degraded for the session; the first operator warning is immediate and all repeated warnings obey `database-operator-warning-interval-seconds` even while the dropped-write count continues increasing.

### Fixed

- Fixed #27 by using the actual Paper server tick as the database coalescing key instead of 50 ms wall-clock buckets.
- Counted SQLite cross-flush same-tick upserts in the `/fg status` coalesced-write metric so status reporting includes both in-memory and persisted coalescing.
- Removed persisted same-tick net no-op rows when a coordinate returns to its original state after separate flushes.
- Fixed release builds failing version validation by aligning the Gradle project base version with the `26.2-3` release line; prerelease workflows can now apply tags such as `26.2-3-beta.2` correctly.
- Fixed GitHub Actions test execution by adding the JUnit Platform launcher to the test runtime classpath for JUnit 6.1.3.
- Fixed #6 by recording every block actually changed by a rollback or undo as a `ROLLBACK` history entry before the programmatic world change is applied.
- Fixed rollback audit loss when the bounded gameplay write queue is full by persisting rollback/undo audit batches through an acknowledged database-operation path that is committed before world mutation.
- Fixed inaccurate physics-enabled rollback history by no longer suppressing listener-generated transitions and by auditing the observed post-physics state when it differs from the requested target.
- Fixed #7 by bounding rollback history queries to the planning snapshot, persisting each coordinate's expected snapshot state, revalidating immediately before mutation, skipping/reporting conflicts unless force mode was explicitly chosen, and retaining the existing durable overlap lock for active rollback regions.
- Fixed the post-audit race where a block could change while required rollback history was being committed; FragGuard now revalidates the audit's before-state, retracts stale audit rows, and in force mode retries with a newly audited live state instead of applying a stale transition.
- Fixed crash-window rollback mutations being permanently omitted from `/fg undo` when the world change succeeded but shutdown or failure occurred before the batch could commit `applied = 1`; durably prepared, non-conflicted rows remain undo-recoverable even when that progress marker is missing.
- Fixed externally completed force retries being incorrectly eligible for `/fg undo`; when another actor reaches the rollback target after a stale force audit is retracted, FragGuard now records the retry as conflicted/non-applied so the saved pre-retry state cannot later overwrite that external change.
- Fixed stale undo coordinates being silently finalized as `undone = 1`; revalidation conflicts now stay recoverable, prevent the job from falsely becoming `UNDONE`, and are reported with a retry instruction.
- Fixed #9 by making shutdown durability observable: FragGuard drains accepted bounded-queue work before close, checkpoints the WAL on the database worker, explicitly counts still-queued logs as dropped if the worker fails, reports incomplete shutdown state, preserves degraded health after known log loss/database failure, and notifies online operators when logging is unhealthy.
- Fixed the shutdown timeout path returning immediately after cancellation while accepted writes were still owned by a live worker; FragGuard now waits for cancellation and explicitly accounts for queued losses plus any in-flight writes whose durability remains unconfirmed.
- Fixed abandoned shutdown writes being counted as both `drained` and `lost`; only write batches that actually complete successfully contribute to the drained count.
- Fixed long non-timed database operations disappearing from shutdown accounting after they were removed from `operationQueue`; a still-running worker now contributes an unconfirmed active operation to the remaining-operation count unless it is known to be processing an in-flight write batch.
- Fixed degraded-storage warnings bypassing their configured repeat interval whenever `droppedWrites` increased; repeated operator alerts are now rate-limited by the configured interval regardless of how quickly losses accumulate.
- Fixed pre-shutdown counters being captured from different moments while the database worker was concurrently completing a batch; shutdown now returns one atomic accounting snapshot so `queued`, `drained`, `remaining`, and clean-status reporting stay internally consistent.
- Fixed prior session write loss being hidden by an otherwise successful shutdown; `totalDroppedWrites > 0` now always prevents the shutdown report from being classified as clean, ensuring `onDisable()` emits a warning even when the worker later caught up and no additional writes were lost during shutdown.

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
