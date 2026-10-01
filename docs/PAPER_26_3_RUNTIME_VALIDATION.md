# Paper 26.3 Runtime Validation

This checklist is the release gate for FragGuard `26.3-1.2.0` on Paper `26.3.build.136-beta`.

Run it on a disposable copy of a real server. Preserve the complete `plugins/FragGuard` directory while the server is stopped before beginning.

## Record before testing

Capture:

```text
Paper build: 26.3.build.136-beta
FragGuard build: 26.3-1.2.0-beta.1 or newer candidate
Java: 25
Starting FragGuard version:
Starting FragGuard schema:
Test operator:
World name:
World UUID:
```

For the migration release gate, at least one run must begin from an actual FragGuard `26.2-1.1.2` data directory using **schema 3**. A second startup/restart after migration verifies normal schema-4 reopening.

Use one of these beside every section:

- `PASS` — behaved as expected.
- `FAIL` — reproducible FragGuard defect.
- `BLOCKED` — test could not be performed; state why.
- `N/A` — only when the scenario genuinely does not apply, with a reason.

For a failure, capture the console/log excerpt, exact command or action, world/coordinates, expected result, actual result, and whether restart reproduces it.

## 1. Startup, schema upgrade, storage, and restart

### Schema-3 upgrade run

1. Start from a preserved `26.2-1.1.2` FragGuard data directory with `PRAGMA user_version=3`.
2. Install the Paper 26.3 FragGuard release candidate and start the server.
3. Confirm FragGuard enables without `NoSuchMethodError`, `ClassNotFoundException`, `NoClassDefFoundError`, `LinkageError`, or plugin-disable messages.
4. Confirm the v3→v4 migration completes successfully.
5. Run `/fg status` and confirm storage is healthy.
6. Verify existing retained history is still queryable.
7. Verify representative retained block-entity/history data remains readable.

### Schema-4 reopen run

8. Make several normal changes and confirm they are logged.
9. Stop the server cleanly and start it again.
10. Confirm FragGuard reopens schema 4 without another migration and the newly accepted history remains present.

Expected:

- the production schema-3→4 path is exercised;
- retained history is preserved;
- startup and restart succeed;
- SQLite remains healthy;
- Paper 26.3 alone does not trigger another schema change after schema 4 is reached.

## 2. Ordinary and multi-block player logging

1. Place and break a normal full block.
2. Inspect raw history and verify actor, world, coordinates, timestamp, action, and before/after block data.
3. Repeat with representative multi-block/attached structures such as a door and bed.
4. Verify all affected coordinates are represented and unrelated coordinates are not added.

## 3. GUI and lookup

1. Open `/fg`.
2. Run short and large lookups.
3. Browse Condensed Activities and then View All Raw Events.
4. Verify every represented raw row remains reachable.
5. Exercise Player, Action, and Material filters.
6. Page forward/backward through results.
7. Start a newer GUI request while an older async request is still resolving and verify the older completion cannot replace newer state.
8. Open a block-entity raw event and verify before/after persistent details load correctly.

## 4. Block-entity live validation

Create a recognizable persistent-state change for each applicable type, inspect it, roll it back, then undo the rollback:

- chest or barrel;
- Shelf;
- sign;
- banner;
- player head;
- lectern;
- decorated pot.

For each, verify history, raw details, rollback restoration, undo restoration, and normal-mode conflict protection.

### Shelf-specific checks

1. Test an unpowered Shelf item swap.
2. Test a powered connected three-Shelf chain.
3. Verify every changed Shelf is logged.
4. Put a second independent chain directly beside the first and verify the neighboring chain is not attributed to the clicked chain.
5. If practical, test two near-simultaneous actors on the same Shelf/chain and verify actor/state boundaries remain correct.

## 5. Explosions

### TNT

1. Prime TNT by a player.
2. Allow it to destroy blocks.
3. Verify TNT priming/removal and destroyed blocks are logged with the most specific available attribution.

### Entity explosion

Trigger a representative entity-caused explosion and verify destroyed blocks and available causal attribution.

Expected: no missing destroyed blocks and no duplicate contradictory rows for one mutation.

## 6. Fire

Test:

- initial ignition;
- fire spread;
- a block destroyed by fire;
- a player-fired flaming projectile when practical.

Verify persistent transitions are logged, destroyed blocks can be restored, and player shooter attribution is retained when Paper exposes it.

## 7. Liquids and buckets

Test:

- water flow;
- lava flow;
- liquid source/level retraction or decay;
- player bucket placement/removal;
- dispenser bucket placement/removal;
- sponge absorption;
- a block broken by liquid flow.

Verify source/destination transitions required for reconstruction are retained and rollback restores/removes liquid states correctly.

## 8. Pistons

Test piston extension, retraction, multiple moved blocks, and a piston-caused block break where applicable. Verify source/destination/moved states are complete and rollback reconstructs the prior arrangement.

## 9. Growth, formation, spread, fertilization, and entity changes

Exercise representative examples of:

- natural growth;
- block form/fade;
- block spread;
- leaves decay;
- bonemeal/fertilization;
- structure/tree growth;
- entity-caused block formation/change.

Verify specialized events retain their intended action/cause and one mutation is not duplicated under competing inherited handlers.

## 10. Dragon egg teleport

1. Trigger a dragon egg teleport.
2. Verify source removal and destination placement are both recorded under the teleport action.
3. Roll it back and verify the prior state is restored.

## 11. Rollback preview and normal confirmation

Create known changes and run a preview, for example:

```text
/fg rollback r:10 t:5m
```

Verify:

- preview does not mutate the world;
- affected blocks/chunks and target time are sensible;
- confirmation applies only the previewed scope;
- token binding/expiry/single-use behavior works;
- the completed rollback receives a durable job ID.

## 12. Non-force conflict protection

1. Create a rollback preview.
2. Make a conflicting live edit before the target is applied.
3. Confirm the rollback.

Verify the conflict is skipped/rejected, unrelated targets continue normally, and job/audit state reports the conflict consistently.

## 13. Force rollback revalidation

Repeat the conflict scenario in force mode. Verify FragGuard revalidates from current live state rather than blindly applying a stale prepared state, and audit/undo data represents what actually changed.

## 14. Undo

1. Complete a rollback containing ordinary blocks and at least one supported block entity.
2. Run `/fg undo <job-id>`.
3. Verify the exact pre-rollback state returns.
4. On a fresh job, make a later conflicting edit before undo and verify normal undo does not overwrite that edit and leaves the conflict retryable as designed.

## 15. Overlap protection

1. Start a rollback job that remains active.
2. Attempt a conflicting rollback in the same world.
3. Verify it is rejected.
4. Verify unrelated work is not incorrectly blocked.

## 16. Multi-chunk and TPS/tick safeguards

Use an already-generated disposable area spanning several chunks.

### Multi-chunk/tick-budget pass

1. Create enough history to require multiple slices/chunks.
2. Use normal `rollback-blocks-per-tick` and `rollback-max-millis-per-tick` values.
3. Start the rollback and verify the server remains responsive.
4. Verify existing chunks are loaded without generating unrelated terrain.
5. Verify chunk tickets are released as work advances/completes.

### TPS pause/resume pass

FragGuard does **not** hot-reload `config.yml`; `rollback-minimum-tps` is read from the cached configuration loaded when the plugin enables. Do not edit the YAML while the server is running and expect the active plugin instance to see it.

Use this reproducible restart-based sequence:

1. Stop the server cleanly.
2. Set `rollback-minimum-tps` above attainable server TPS (for example `21.0`) before startup.
3. Start the server and begin or recover a sufficiently large rollback.
4. Verify rollback mutation work remains paused because current TPS is below the configured threshold.
5. Stop the server cleanly while the job remains persisted.
6. Set `rollback-minimum-tps` back to the normal value (for example `18.0`, or `0` to disable TPS gating for the recovery check).
7. Start the server again.
8. Verify the persisted rollback is recovered and resumes without losing progress.
9. Restore the server's intended normal configuration after the test.

Expected:

- per-tick work remains bounded;
- TPS gating pauses mutation work;
- restart with the lowered threshold allows persisted work to resume;
- no rollback progress is fabricated or lost.

## 17. Restart during active rollback

1. Start a rollback large enough to remain active for multiple slices.
2. Stop/restart while it is active.
3. Verify persisted job state loads on restart.
4. Verify prepared-but-unapplied work is not exposed as completed history.
5. Verify already-applied mutations remain represented for undo.
6. Allow recovery/resume to finish.
7. Undo the recovered job and verify the expected pre-rollback state returns.

## 18. Final log review

Review the complete test log for:

```text
NoSuchMethodError
ClassNotFoundException
NoClassDefFoundError
LinkageError
SQLException
FragGuard
```

Expected:

- no Paper 26.3 linkage/runtime compatibility error;
- no unexplained storage-health degradation;
- no unexpected dropped-write warning.

## Release decision

`26.3-1.2.0` is release-ready only when all issue #82 acceptance items are explicitly represented by `PASS`, or by a justified `N/A` when genuinely non-applicable.

Store the completed outcomes in `PAPER_26_3_RUNTIME_VALIDATION_RESULT.md`. Any reproducible runtime defect discovered during this gate must be fixed or filed as release-blocking before #82 is closed.
