# Paper 26.3 Runtime Validation

This checklist is the release gate for FragGuard `26.3-1.2.0` on Paper `26.3.build.136-beta`.

Run it on a disposable copy of a real server, not the production world. Use an existing `plugins/FragGuard` directory so startup, schema, history, and rollback recovery are exercised against real retained data.

## Record before testing

Capture these values before the first test:

```text
Paper build: 26.3.build.136-beta
FragGuard build: 26.3-1.2.0
Java: 25
Existing FragGuard schema: 4
Test operator:
World name:
World UUID:
```

Also preserve the complete pre-test `plugins/FragGuard` directory while the server is fully stopped.

## Result notation

Use one of these beside every test:

- `PASS` — behaved exactly as expected.
- `FAIL` — reproducible FragGuard defect.
- `BLOCKED` — test could not be performed; state why.
- `N/A` — only when the scenario genuinely does not apply.

For any failure, capture:

```text
Paper console/log excerpt:
Exact command or action:
World + coordinates:
Expected result:
Actual result:
Whether restart reproduces it:
```

## 1. Startup, upgrade, and storage

1. Start Paper with the existing FragGuard data directory and the new plugin JAR.
2. Confirm FragGuard enables without `NoSuchMethodError`, `ClassNotFoundException`, linkage errors, or plugin-disable messages.
3. Run `/fg status`.
4. Confirm storage reports healthy and queue/drop counters are normal.
5. Confirm the database remains schema 4; Paper 26.3 alone must not trigger another schema migration.
6. Perform several normal block changes, stop the server cleanly, and start it again.
7. Confirm FragGuard starts cleanly after restart and the newly recorded history is still present.

Expected:

- startup succeeds;
- no Paper-version-only schema rewrite occurs;
- SQLite remains healthy;
- restart does not lose accepted history.

## 2. Ordinary and multi-block player logging

Use a quiet test area.

1. Place and break a normal full block such as stone.
2. Open raw history for the location.
3. Verify actor, world, coordinates, timestamp, action, before block data, and after block data.
4. Repeat with representative multi-block or attached structures, for example a door and a bed.
5. Verify every affected block coordinate is represented and no unrelated coordinate is added.

Expected:

- player attribution is correct;
- before/after states are exact;
- both halves/affected blocks of multi-block structures are retained.

## 3. GUI and lookup

1. Open `/fg`.
2. Run a short lookup around the test area.
3. Browse Condensed Activities.
4. Open an activity and then View All Raw Events.
5. Verify all represented raw rows remain reachable.
6. Exercise Player, Action, and Material filters.
7. Page forward and backward through results.
8. Run a substantially larger lookup containing enough history to require multiple database pages.
9. While one large lookup/page/filter request is still resolving, immediately start a newer one.
10. Verify the older async completion does not replace the newer GUI state.
11. Open raw details for a block-entity event and verify before/after block-entity details are shown.

Expected:

- condensed grouping never hides access to raw rows;
- filters apply correctly;
- paging is deterministic;
- stale async work is discarded;
- block-entity BLOB details load only when the exact event is opened.

## 4. Block-entity live validation

For each type below, create a known persistent-state change, verify lookup details, roll it back, and then undo that rollback.

Test at least:

- chest or barrel with recognizable items;
- Shelf, including an item swap;
- sign with recognizable text;
- banner with a recognizable pattern;
- player head with a known profile/texture;
- lectern with a book and page state;
- decorated pot with recognizable sherds/items.

For every block entity verify:

1. History records the expected before and after persistent state.
2. Raw-event details display the persistent state correctly.
3. A rollback restores the earlier persistent state.
4. Undo restores the state that existed immediately before rollback.
5. A conflicting live edit is not silently overwritten in normal mode.

### Shelf-specific checks

1. Test an unpowered Shelf item swap.
2. Test a powered connected three-Shelf chain.
3. Verify all changed Shelves are logged.
4. Place a second independent three-Shelf chain directly beside the first and verify interacting with one chain does not attribute changes from the neighboring chain.
5. If possible, have two players interact with the same Shelf/chain during the same server tick or as close together as practical; verify history remains state-contiguous and actor attribution does not collapse both swaps into the first player.

## 5. Explosions

### TNT

1. Prime TNT by a player.
2. Let it explode blocks.
3. Verify TNT removal/priming and explosion block changes are logged with the most specific available player attribution.

### Entity explosion

Trigger a representative entity-caused explosion and verify destroyed blocks are recorded with the correct entity/player cause when available.

Expected:

- no missing destroyed blocks;
- no duplicate contradictory rows for the same mutation;
- attribution is preserved where Paper exposes a causal player/projectile source.

## 6. Fire

Test separately:

1. initial player ignition;
2. fire spread to another block;
3. a block destroyed by fire.

Verify each persistent block transition appears in history and rollback restores destroyed blocks correctly.

Also test a player-fired flaming projectile if practical and confirm the player, not merely the projectile UUID, is retained as actor when Paper exposes the shooter.

## 7. Liquids and buckets

Test:

- natural/placed water flow;
- lava flow;
- source/level retraction or decay;
- player bucket placement;
- player bucket removal;
- dispenser bucket placement/removal;
- sponge absorption;
- a block broken by liquid flow.

Expected:

- source and destination changes needed to reconstruct the liquid state are retained;
- removal/retraction is not lost;
- player/dispenser/environment attribution is correct;
- rollback removes/restores liquids and liquid-broken blocks correctly.

## 8. Pistons

Test:

1. piston extension moving multiple blocks;
2. piston retraction;
3. a piston-caused block break where applicable.

Verify source/destination/moved states are represented without partial history and that rollback reconstructs the earlier arrangement.

## 9. Growth, formation, spread, and entity changes

Exercise representative examples of:

- natural block growth;
- block form/fade;
- block spread;
- leaves decay;
- bonemeal/fertilization;
- structure/tree growth;
- entity-caused block formation/change.

Expected:

- specialized events keep their intended FragGuard action/cause instead of being relabeled by a generic inherited Bukkit event handler;
- no duplicate competing rows describe one mutation.

## 10. Dragon egg teleport

1. Trigger a dragon egg teleport.
2. Verify the source removal and destination placement are both present under the dedicated teleport action.
3. Roll back the event and confirm the egg returns to the expected prior state.

## 11. Rollback preview and normal confirmation

Create several known changes in a small radius, then run a rollback preview using either the GUI or command path.

Example command:

```text
/fg rollback r:10 t:5m
```

Verify:

1. Preview does not mutate the world.
2. Preview reports the expected affected blocks/chunks and target time.
3. Confirmation applies only the previewed scope.
4. The confirmation token is operator-bound, single-use, and expires normally.
5. A completed rollback receives a durable job ID.

## 12. Non-force conflict protection

1. Create history that would be targeted by rollback.
2. Generate the preview.
3. Before confirmation reaches a target block, make a conflicting live edit to that block.
4. Confirm the rollback.

Expected:

- the conflicting live edit is skipped/rejected rather than overwritten;
- unaffected targets still apply normally;
- the job/audit state reports the conflict consistently.

## 13. Force rollback revalidation

1. Repeat a conflict scenario with force mode enabled.
2. Change the live block after preview.
3. Confirm the force rollback.

Expected:

- FragGuard revalidates from the latest observed live state;
- it does not blindly apply a stale prepared state;
- resulting audit/undo data describes what was actually changed.

## 14. Undo

1. Complete a rollback that changes multiple ordinary blocks and at least one supported block entity.
2. Record its job ID.
3. Run:

```text
/fg undo <job-id>
```

Verify the exact pre-rollback state is restored, including block-entity contents.

Then edit one rolled-back target after the rollback but before undo and repeat on a fresh job.

Expected:

- normal undo does not overwrite a conflicting later player edit;
- unresolved conflicts remain retryable as designed.

## 15. Overlap protection

1. Start a rollback job in one world that remains active long enough to overlap another request.
2. Attempt a second rollback whose area/job conflicts with the first.

Expected:

- conflicting jobs in the same world are rejected;
- an unrelated operation in another world or non-overlapping allowed scope is not incorrectly blocked.

## 16. Multi-chunk and TPS/tick safeguards

Use a disposable area spanning several already-generated chunks.

1. Create enough history to require a multi-slice/multi-chunk rollback.
2. Keep `rollback-blocks-per-tick` and `rollback-max-millis-per-tick` at normal values first.
3. Confirm the server remains responsive while the rollback progresses.
4. Verify required chunks are loaded without generating unrelated terrain.
5. Raise `rollback-minimum-tps` temporarily above the server's current TPS so work pauses.
6. Lower it again and verify the job resumes.

Expected:

- chunk tickets are released as work advances/completes;
- no unintended terrain generation occurs;
- time/block budgets split work across ticks;
- TPS pause/resume works without losing job progress.

Restore the original configuration after this test.

## 17. Restart during active rollback

1. Start a rollback large enough to remain active for multiple ticks/slices.
2. Stop/restart the server while the job is active. Prefer a normal stop first; if a crash-window test is performed, use only a disposable copy of the server.
3. After restart, verify FragGuard loads the persisted job state.
4. Verify prepared/unapplied work is not falsely exposed as completed history.
5. Verify already-applied mutations remain represented for later undo.
6. Allow recovery/resume to finish.
7. Undo the recovered completed job and verify the expected pre-rollback state returns.

## 18. Final log review

Search the server log from the entire run for:

```text
NoSuchMethodError
ClassNotFoundException
NoClassDefFoundError
LinkageError
SQLException
FragGuard
```

Expected:

- no Paper 26.3 linkage/runtime compatibility errors;
- no unexplained database-health degradation;
- no dropped-write warning unless deliberately induced during a separate stress test.

## Release decision

`26.3-1.2.0` is release-ready only when every issue #82 acceptance item is `PASS`, or an explicitly non-applicable item is documented as `N/A` with a reason.

Any reproducible migration/runtime defect discovered here should be fixed on the issue #82 branch or filed as a release-blocking issue before #82 is closed.
