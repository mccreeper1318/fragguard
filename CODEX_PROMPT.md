Create or continue a Paper 26.2 plugin named FragGuard using the included project as the starting point. Keep the core behavior:

- Log every player block placement and block break/destruction event.
- Log explosion block changes.
- Log fire spread and blocks destroyed by fire burn events.
- Log lava and water flow.
- Log player bucket water/lava placement and removal, because bucket empty/fill events are not normal block place/break events.
- Log blocks broken by lava/water flow.
- Log piston extension/retraction changes, including moved blocks, piston heads, and piston base state changes when the server changes them.
- Store the log in SQLite under plugins/FragGuard/fragguard.db.
- Retain logs for 30 days, then automatically delete older records.
- Add /fg lookup r:<radius> to show paginated logs in a full-height radius around the operator.
- Add /fg rollback r:<radius> t:<duration> to restore the area to the state it was in that duration ago.
- Example command: /fg rollback r:30 t:2d 7h 15m
- Only server operators should be able to use the commands.
- Use config.yml for retention, radius limits, page size, rollback batch size, physics settings, and toggles for environmental logging.

Preserve the safety limits and asynchronous SQLite access. Environmental changes should be labeled as system-caused changes, not player-caused changes. Player bucket water/lava placement/removal should use the player name and UUID.
