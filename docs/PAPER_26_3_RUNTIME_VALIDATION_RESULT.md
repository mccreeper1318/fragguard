# Paper 26.3 Runtime Validation Result

Issue: #82

Release candidate tested: `26.3-1.2.0-beta.1`

Release workflow run: `36616831389`

Release artifact: `FragGuard-26.3-1.2.0-beta.1.jar`

Release JAR SHA-256: `2dcc4cc446300618d722931a99c4000d14e4dcc458cb174602e87e3c22feaf7a`

## Environment and upgrade path

- Paper target: `26.3.build.136-beta`
- FragGuard release candidate: `26.3-1.2.0-beta.1`
- Java target: 25
- Prior FragGuard release/data: `26.2-1.1.2`
- Starting FragGuard schema: 3
- Resulting FragGuard schema: 4
- Test operator: server owner/operator
- Test world: existing upgraded server world
- World UUID: not retained in this report; no world-identity issue was observed

The live beta deployment upgraded the existing FragGuard data directory from the 26.2 release line. This exercised the production schema-3 to schema-4 path rather than only reopening an already-upgraded schema-4 database. FragGuard enabled normally after the migration, retained existing history, and continued recording/querying new history.

## Automated release validation

The GitHub release workflow completed successfully for `26.3-1.2.0-beta.1`. It built and tested the release candidate, verified the packaged plugin resources and embedded version, generated the checksum, and uploaded the verified JAR and checksum to the prerelease.

Result: **PASS**

## Runtime matrix results

The server owner reported that the beta.1 runtime-validation scenarios behaved as intended and no Paper 26.3 migration-specific FragGuard defect was observed. The release-gate results are recorded explicitly below.

1. **Startup, schema-3 upgrade, storage, and restart — PASS**  
   Existing 26.2/schema-3 data upgraded to schema 4, FragGuard enabled normally, retained history remained available, and normal operation continued after restart.

2. **Ordinary and multi-block player logging — PASS**  
   Player-attributed block changes and representative multi-block behavior worked as intended.

3. **GUI and lookup — PASS**  
   `/fg`, condensed activities, raw-event access, paging, filters, and lookup behavior worked as intended.

4. **Block-entity live validation — PASS**  
   Supported block-entity handling, including Shelf behavior introduced for 26.3, worked as intended through lookup/rollback/undo use.

5. **TNT and entity explosions — PASS**  
   Explosion-related logging behaved as intended.

6. **Fire ignition, spread, and burn destruction — PASS**  
   Fire-related history and rollback behavior worked as intended.

7. **Liquids, buckets, dispensers, sponge, and liquid-broken blocks — PASS**  
   Liquid-related logging and rollback behavior worked as intended.

8. **Piston extension/retraction and piston-caused changes — PASS**  
   Piston history behaved as intended.

9. **Growth, formation, spread, fertilization, structure growth, and entity changes — PASS**  
   Representative world-changing event paths behaved as intended.

10. **Dragon egg teleport — PASS**  
    Source/destination history behavior worked as intended.

11. **Rollback preview and normal confirmation — PASS**  
    Preview/confirm behavior worked as intended.

12. **Non-force conflict protection — PASS**  
    Conflict-protected rollback behavior worked as intended.

13. **Force rollback revalidation — PASS**  
    Force-mode behavior worked as intended without an observed stale-state overwrite issue.

14. **Undo — PASS**  
    Rollback undo behavior worked as intended.

15. **Rollback overlap protection — PASS**  
    Overlap protection behaved as intended.

16. **Multi-chunk and TPS/tick safeguards — PASS**  
    Multi-chunk/tick-budget behavior was reported working as intended. The companion checklist now documents the correct restart-based procedure for changing `rollback-minimum-tps`, because FragGuard caches configuration for the lifetime of the enabled plugin and does not hot-reload that value.

17. **Restart during active rollback / persisted recovery — PASS**  
    Restart/recovery behavior was reported working as intended.

18. **Final runtime/log review — PASS**  
    No release-blocking Paper 26.3 linkage, database-health, or FragGuard runtime error was reported.

No matrix item was recorded as `FAIL`, `BLOCKED`, or `N/A`.

## Release decision

**PASS — Paper 26.3 runtime gate satisfied.**

The successful release build/test, the live schema-3 to schema-4 upgrade, and the reported successful runtime matrix provide the release-gate evidence for issue #82. No production-code fix was required as a result of the beta.1 validation.
