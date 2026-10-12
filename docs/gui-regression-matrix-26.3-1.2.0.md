# FragGuard 26.3-1.2.0 — deep GUI regression matrix

Tracks **#112** under **#107**. Do not mark the release regression sign-off complete until
both automated tests and the live Paper 26.3 matrix below have passed.

## Original failure / fix history

- **#108:** Valid world blocks such as `WATER_CAULDRON`, `MOVING_PISTON`, and
  `WATER` could throw `IllegalArgumentException: ... isn't an item` when a
  GUI history entry was converted into an `ItemStack`. Later pagination made
  these look like "page 3" bugs, although any page could contain the material.
  Render a safe fallback icon only; never rewrite the recorded material or event.
- **#109:** A GUI SQL timeout or rendering exception should leave the previous
  inventory and its page/cursors recoverable instead of closing the lookup or
  reopening a stale async view.
- **#110:** Replace the redundant legacy count with a lightweight write barrier.
- **#111:** Target indexed `chunk_x` / `chunk_z` equality seeks for GUI
  count and paged queries. Live-server retest at the original active location:
  1,338 changes at 15 blocks/1 day counted in 13 ms (pages 17–21 ms), and
  56,402–56,502 changes at 30 blocks/1 day counted in 57–320 ms (pages 53–58 ms).
  The previous 30-block count timed out at 15 seconds.

## Automated test coverage

- `GuiDeepHistoryRegressionTest`: fixed snapshot, mixed world/time rows,
  actor/material/action filtering, equal timestamp/id cursors, 36-slot raw
  and condensed pages 1/2/3/5/10+, backwards reentry via saved cursors,
  later writes excluded, and a 109-row activity drilled down through 45/45/19
  exact-event pages.
- `GuiPagedInventoryRegressionTest`: Bukkit-inventory-compatible mock
  construction of grouped and raw page-three screens containing
  `WATER_CAULDRON`, `MOVING_PISTON`, `WATER`, `LAVA`, `FIRE`,
  `REDSTONE_WIRE`, `AIR`, and a legacy/unknown block identifier. Verifies
  the underlying event/material labels are still displayed. Paper's
  `Material.isItem()` registry is unavailable in a unit test and is exercised
  through the explicit predicate seam, **not** a running Paper server.
- `GuiRecoveryStateRegressionTest`: direct `FragGuardGui` state recovery on
  an injected render exception, asynchronous SQLite timeout and stale callback
  after disconnect. The page/cursor and visible inventory must remain unchanged,
  errors must be logged and retry must be possible.
- Existing regression tests: `GuiMaterialIconTest`, `GuiLookupPaginationTest`
  (5,260 rows), `GuiIndexedAreaQueryTest` (10,201 source rows), and
  `GuiLookupOptimizationTest` (including end-to-end timeout diagnostic).

**Test limitations:** mocked inventories and scheduled callbacks are not the
full Paper runtime. Automated assertions establish SQL and state invariants,
but in-game inventory opening, real registry behavior, loading feedback,
filter navigation, and exact-event details still require manual verification.

## Live Paper 26.3 test checklist

Use an operator account with `fragguard.admin` in a controlled test window.
For the actual reported high-activity location, verify each item in **both**
the 15-block/1-day and 30-block/1-day searches when practical.

- [ ] Run `/fg` and start each lookup. Observe a loading message, retention
  of the current GUI while asynchronous SQL runs, a nonempty event count,
  and an actual inventory of records.
- [ ] In **Condensed Activities**, visit pages **1, 2, 3, 5, and 10 or later**,
  use Previous Page, and reopen page 3. Ensure items/icons do not crash the
  inventory; verify expected actor/material/action labels.
- [ ] Toggle **Exact Raw Events**. Repeat pages **1, 2, 3, 5, and 10+**,
  moving forward and backward. Open an exact event and return.
- [ ] From condensed results open an activity, select **View All Raw Events**,
  go forward/back across its 45-slot raw-event pages if the activity has
  more than 45 events, and return to the activity/results views.
- [ ] Open **Filters** (catalog now lazy-loaded on first access); choose a
  Player, Action, and Material filter independently and in combination.
  Verify filtered event totals, Reset/Clear Filters, page navigation,
  and returning to unfiltered history.
- [ ] Browse around water, fire, pistons, cauldrons, redstone and blocks
  changed by physics; check both grouped and raw icon fallbacks and that
  event details still show original before/after data.
- [ ] Generate new block changes during a separate lookup; already-open
  pages/counts should remain bounded to the initial snapshot. A fresh
  lookup should see those new events.
- [ ] Verify the console has no new `FragGuard GUI rendering failed`,
  `FragGuard GUI database lookup timed out`, or unexplained exceptions.
  If a failure occurs, retain the log and verify Back/Retry can recover.
- [ ] Confirm ordinary page navigation does not introduce server-thread
  stalls and that pages are responsive at 56,000+ matching events.

**Report:** Paper build, plugin build/commit, area/radius/time, page numbers
visited in each view, whether filters/drilldown succeeded, elapsed timings,
and any relevant console excerpts. Keep #112 and parent #107 open if any
manual requirement is unverified.
