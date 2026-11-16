# Queue, shuffle and repeat audit

**Scope:** everything that builds, changes, orders, persists or displays the play queue:

* the queue engine: `QueueOrderStore`, the windowed ExoPlayer timeline, `next` / `previous` / `hasNext`;
* shuffle and repeat (the in-queue toggles, and how they interact with an already-shuffled queue);
* the Play and Shuffle buttons on Album, Artist and Playlist pages, and song-row taps;
* Play Next, Add to Queue, and the Keep/Clear and Replace prompts;
* persistence and restore;
* system transport (notification, Bluetooth, car);
* the Up Next UI: drag-reorder, swipe-to-remove, tap-to-play, and the queue-mode swipe that hides
  or shows the transport overlay.

**Baseline:** branch `public-transition` at `7dff23f`, audited on `claude-branch`. Audit date: 2026-11-03.

| File | Issues | Area |
|---|---|---|
| [01-shuffle.md](01-shuffle.md) | #1 – #12 | Shuffle toggle, Play and Shuffle buttons, shuffle × Play Next / reorder / repeat, external shuffle |
| [02-engine-repeat-persistence.md](02-engine-repeat-persistence.md) | #13 – #25 | Repeat, navigation, playback errors, persistence, restore, system transport |
| [03-up-next-ui-and-gestures.md](03-up-next-ui-and-gestures.md) | #26 – #37 | Up Next list: reorder, swipe-to-remove, overlay show/hide gesture, accessibility |
| [04-entry-points-and-prompts.md](04-entry-points-and-prompts.md) | #38 – #47 | Song taps, Keep/Clear and Replace prompts, Play Next / Add to Queue, detail pages |
| [appendix-shuffle-simulation.md](appendix-shuffle-simulation.md) | — | Reproducible simulation behind #1 – #4 |

## Method and limits

* A complete read of every file in `playback/` and `ui/player/`, plus the queue-related parts of:
  * `ui/components/` (`QueueList`, `QueueRow`, `QueuePlaybackModeRow`, `DetailHeader`, `SongActionsHost`, `SongRow`),
  * `ui/MainScaffold.kt`, `ui/SynthXBottomNav.kt`, `MainActivity.kt`,
  * the detail screens and their ViewModels,
  * `data/db/QueueDao.kt` and the queue entities,
  * `data/repository/SongRepositoryImplQueue.kt`,
  * the existing queue tests.
* **The shuffle findings were run, not only read.** `QueueOrderStore`'s list logic was ported to Java
  and the scenarios executed (see the appendix).
* **The reorder auto-scroll finding (#26) was checked against the library's source**
  (`sh.calvin.reorderable:reorderable-android:3.1.0`).
* **No device or emulator was available, and Gradle was not run.** Findings that depend on media3 or
  Compose runtime behaviour are reasoned from source and say so where they need on-device
  confirmation (#18). Recommended: reproduce #1, #3, #26 and #38 on the Redmi before fixing.
* Only documentation was added in this change; no app code was modified.

Paths in the issue files are relative to `app/src/main/java/com/v1per/synthx/` unless they start
with `app/`.

## Severity legend

| Severity | Meaning |
|---|---|
| **Critical** | Silent, permanent loss of the user's queue in a normal flow |
| **High** | Core queue/shuffle behaviour is wrong or loses songs, reachable in everyday use |
| **Medium** | Clear bug or bad UX with a workaround, or a data-loss path that needs a specific window |
| **Low** | Edge case, robustness, performance, accessibility, design note or missing capability |

**Totals:** 1 Critical · 4 High · 9 Medium · 33 Low = **47 issues**.

## Suggested fix order

1. **#1 + #7.** Make `shuffle()` and `unshuffle()` total: they must never drop a slot that has not been played,
   whatever is in `baseline`. Add the invariant test. This is the queue-wipe bug.
2. **#2, #3, #4.** Redesign shuffle OFF:
   * keep the full context in original order, with the songs before the cursor kept as history instead of deleted;
   * keep user-queued (Play Next) items directly after the current song;
   * leave already-played extras in history.

   This also fixes the Repeat All shrink and "reshuffle shrinks the queue".
3. **#5.** Intercept ExoPlayer's native shuffle in `SystemNavigationPlayer` and force it off.
4. **#14, #40.** Queue loss across launches: restore before the daily backup, and persist the "customised" flag.
5. **#26, #28.** Reorder auto-scroll downward, and Undo for swipe-to-remove.
6. **#38, #39.** Make Keep actually "play this song now".
7. **#13.** Auto-skip unplayable tracks.

## Full index

| # | Title | Severity | Area / main location |
|---|---|---|---|
| 1 | Turning shuffle OFF while a song queued during shuffle is playing wipes the entire queue | Critical | Shuffle · `QueueOrderStore.unshuffle` |
| 2 | Shuffle OFF permanently deletes every song before the current one, including never-played songs; Repeat All loop shrinks | High | Shuffle · `QueueOrderStore.unshuffle` |
| 3 | Detail-page Shuffle button + shuffle OFF loses a random part of the album/artist/playlist | High | Shuffle · `PlayAllRequest`, `unshuffle` |
| 4 | Songs queued while shuffled jump to the very end on shuffle OFF; already-heard ones are re-queued | High | Shuffle · `unshuffle` `sessionExtras` |
| 5 | External controllers can switch on ExoPlayer's native shuffle; the app neither prevents nor follows it | High | Shuffle · `SystemNavigationPlayer`, service |
| 6 | Reorders made while shuffled are silently discarded on shuffle OFF | Medium | Shuffle · `moveQueueItemInternal` |
| 7 | Shuffle/unshuffle trust `baseline` blindly; an empty or partial baseline collapses the queue | Medium | Shuffle · `QueueOrderStore`, restore |
| 8 | Shuffle ON re-queues songs already heard this session | Low | Shuffle · design |
| 9 | Repeat All with shuffle replays the identical order every loop | Low | Shuffle × repeat · design |
| 10 | Shuffle/Repeat taps use UI-captured state; fast second tap lost; refusals give no feedback | Low | Shuffle/repeat · `SynthXBottomNav` |
| 11 | A no-op shuffle tap leaves a scroll-to-top pending that fires on a later unrelated change | Low | Up Next · `QueueModeState` |
| 12 | Stale rationale and dead cancellation around `setShuffleEnabled` | Low | Code quality · `PlaybackViewModel` |
| 13 | Unplayable tracks are never auto-skipped; songs that go missing stay queued | Medium | Engine · `onPlayerError`, reconciliation |
| 14 | First launch of the day: restore waits for the backup; Play Next / Add to Queue in that window overwrite the saved queue | Medium | Restore · `MainActivity` |
| 15 | Up Next ignores Repeat All's wrap ("Nothing queued after this" on the last song) | Low | Repeat · Up Next |
| 16 | Saved repeat/shuffle preferences dropped when the saved queue can't be restored | Low | Restore · repeat |
| 17 | Missing current song at restore → restart from slot 0 at 0:00 | Low | Restore |
| 18 | `isQueueEnded` latch keeps Play Next / Add to Queue in "replace" mode after end-of-queue edits | Low | Engine · ended state |
| 19 | Restore reads active and baseline orders in two transactions; baseline errors swallowed | Low | Restore · persistence |
| 20 | Mirrored edits persist baseline and active order as two separate commits | Low | Persistence |
| 21 | Baseline move mirroring is positional and silent on failure; restore fix-up rewrites only the active order | Low | Persistence · `QueueOrderStore` |
| 22 | O(N) table rewrites per edit and O(N) rehydration per track change on large queues | Low | Performance |
| 23 | Connection checks inconsistent; seek/repeat taps lost silently during reconnect | Low | Engine |
| 24 | Latent cursor-not-in-store states wedge navigation | Low | Engine · robustness |
| 25 | External controllers only see the ~51-item window as the queue | Low | System transport · info |
| 26 | Drag-reorder never auto-scrolls downward (scroll zone is off-screen) | Medium | Up Next · `QueueList` |
| 27 | Bottom padding ignores the list's 180dp offset; last rows stuck under the controls | Low | Up Next · layout |
| 28 | Swipe-to-remove is instant, permanent, has no Undo, and any fast flick removes | Medium | Up Next · `QueueRow` |
| 29 | Swiping the first row as the track changes plays the dismissed song | Low | Up Next · race |
| 30 | Reorder destination is positional; a concurrent track change lands the row one slot off | Low | Up Next · race |
| 31 | Up Next keeps the old scroll position when a brand-new queue starts | Low | Up Next · `QueueModeState` |
| 32 | Overlay show/hide drag accumulator is never reset | Low | Overlay gesture |
| 33 | Band under the visible controls is dead to scrolling and to the hide/show gesture | Low | Overlay gesture |
| 34 | Hidden controls persist across collapse/expand with no hint; Back doesn't leave queue mode | Low | Overlay / queue mode |
| 35 | Queue list stays composed (offscreen layer + mask) while the sheet is collapsed | Low | Performance |
| 36 | Every row receives the whole list; ripple/colour deviate from the design system | Low | Up Next · perf/consistency |
| 37 | Shuffle/Repeat buttons expose no on/off state to accessibility | Low | Accessibility |
| 38 | "Keep" doesn't start playback when paused, despite the dialog promising it | Medium | Prompts · `keepQueueAndPlayNext` |
| 39 | "Keep" ignores a song already playing or anywhere in Up Next and says "Already playing next" | Medium | Prompts |
| 40 | Customised-queue protection (prompts) is lost on every restart | Medium | Prompts · `isQueueCustomized` |
| 41 | "Keep" after the queue ended plays only the tapped song, dropping its list | Low | Prompts |
| 42 | Queue prompts vanish on a configuration change | Low | Prompts · `MainScaffold` |
| 43 | Play Next / Add to Queue / Keep race an in-flight queue load | Low | Entry points · race |
| 44 | Duplicate checks read a `WhileSubscribed` flow (CLAUDE.md lazy-flow trap) | Low | Entry points · fragility |
| 45 | Artist page Play/Shuffle order is A→Z by title | Low | Detail pages · design |
| 46 | Add to Queue appends after the entire remaining context | Low | Entry points · design |
| 47 | Up Next has no history, no Clear, no count or duration | Low | Missing capability |

## Checked and found correct

These were examined specifically and need no action. They are listed so that reviewers know they
were covered.

* **Stale index protection.** Remove, move and tap-to-play are identified by slot id and re-resolved
  by the engine at execution time. A stale UI index cannot hit the wrong song. This is covered by
  `QueueSwipeRemoveTest`, `QueueReorderStaleClosureTest` and `QueueEditInstrumentedTest`.
* **Removal under shuffle** is permanent in both orders. Deleting a library song strips every queued
  copy, skips the player past it, and does not mark the queue as customised.
* **Fresh plays and the shuffle flag.**
  * A fresh play while shuffle is on shuffles the new list with the tapped song first.
  * **Play** forces shuffle off. **Shuffle** forces it on, with a uniformly random start song and a uniform
    (`shuffled()`, Fisher–Yates) order for the rest.
  * The Replace prompt replays the exact tap-time request, including the same random start.
* **Play Next** goes directly after the current song in both orders when unshuffled. Repeated Play Next
  is LIFO, which matches Apple Music. The duplicate confirmation only fires when the song already
  occupies the exact target slot.
* **Navigation.** `next()`, `previous()` and `hasNext` come from the full order, not the loaded window.
  * Repeat All wraps at the true queue end for queues bigger than the window (modular window).
  * Repeat One still allows skipping.
  * Previous restarts after 3 s, matching media3.
  * Notification and headset Next/Previous share the same rules through `SystemNavigationPlayer`.
* **Window maintenance** never removes and re-adds the playing item, and its cost is bounded by the
  window size. The deletion path handles several copies of the same song in a row.
* **Persistence.** One process-wide FIFO writer serializes all writes. A fresh play and a shuffle
  toggle are each a single atomic write.
* **Disabled states.** A drop while the list is disabled restores the engine order. A swipe on a
  disabled row snaps back. Emptying Up Next forces the controls overlay visible, so the user can't
  be locked out.

## Test gaps

* There is no unit test for `unshuffle` or `shuffle` when the current slot is not in baseline, or
  when baseline is empty (#1, #7). A property test such as "shuffle ↔ unshuffle never loses an unplayed slot"
  would have caught #1 – #4.
* `QueueOrderStoreTest.unshuffleRestoresBaselineTailAndFoldsInSessionAdditions` asserts the
  deletion behind #2 as correct, and will need updating with the fix.
* There is no test of Keep with the player paused (#38), or with the tapped song deep in Up Next (#39).
* There is no test that sends `setShuffleModeEnabled` through the media session (#5).
* There is no UI test that drags a row past the visible bottom of a long list (#26), or that checks
  the last row can clear the visible controls (#27).
* There is no test of startup ordering between the saved-queue restore and the daily backup (#14).
