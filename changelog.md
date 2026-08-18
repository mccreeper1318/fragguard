# Changelog

## Player block breaks always record air as the result fix

- Deferred player break logging until the next server tick so FragGuard records the block data that actually remains after the break instead of assuming air.
- Captured adjacent paired or attached blocks and incorporated related physics events into the same player break snapshot.
- Coalesced multiple break and physics events from the same player during one tick so each changed coordinate is recorded once.
- Added regression coverage for waterlogged blocks, ice becoming water, doors, beds, propagated physics changes, and multiple same-tick breaks.

## Multi-block placements are only partially logged fix

- Added dedicated handling for `BlockMultiPlaceEvent` so every replaced block state is logged at its own coordinate with the corresponding after-state.
- Prevented the inherited ordinary `BlockPlaceEvent` path from logging the primary block a second time.
- Added regression coverage for beds, doors, tall plants, pointed dripstone, and ordinary single-block placement.
