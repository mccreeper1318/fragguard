# Changelog

## Multi-block placements are only partially logged fix

- Added dedicated handling for `BlockMultiPlaceEvent` so every replaced block state is logged at its own coordinate with the corresponding after-state.
- Prevented the inherited ordinary `BlockPlaceEvent` path from logging the primary block a second time.
- Added regression coverage for beds, doors, tall plants, pointed dripstone, and ordinary single-block placement.
