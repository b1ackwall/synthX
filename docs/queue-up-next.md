# Up Next: geometry, queue mode, the queue generation and accessibility

The Up Next screen is the player sheet's queue mode: a header (the playing song, Shuffle, Repeat),
the Up Next list under it, and the transport controls over the bottom of the list. This document
says where everything sits, which state drives it, when the list scrolls on its own, what
accessibility services are told, and which tests pin each rule. Paths are relative to
`app/src/main/java/com/v1per/synthx/` unless they start with `app/`. Accepted behaviours and
deferred work are in `docs/queue-accepted-behaviours.md`.

## 1. Geometry

All numbers are for the expanded sheet; H is its height (the sheet's `maxHeight`).

| Item | Value | Where |
|---|---|---|
| List top (`queueListOffsetY`) | 180dp = 32 (top margin) + 12 ((68 − 44) / 2) + 136 (48 + 56 + 32, the header region) | `ui/player/QueueArtPositioning.kt` |
| Controls overlay height | min(300dp, H − 84dp), bottom-anchored | `ui/player/PlayerSheetSkeleton.kt` (`actualOverlayHeight`) |
| Controls top | H − 300dp | |
| List viewport | H − 180dp: the list ends at the sheet's bottom edge | `ui/player/PlayerSkeletonQueueContent.kt` |
| Strip between the header and the controls | H − 480dp (220dp on a 700dp sheet, about three rows) | |
| Row height | about 64dp | `ui/components/QueueRow.kt` |

**The list's height.** `requiredHeight(fullHeight − queueListOffsetY)`, top-anchored at 180dp, so its
viewport is the visible strip down to the sheet's bottom edge. The value is constant while the sheet
morphs (it derives from the expanded height), so the list's layer keeps one size; the sheet's clip
cuts it while the sheet is shorter. Floored at 0 for a sheet shorter than 180dp.

**The bottom padding follows the controls.** `contentPadding.bottom = overlayHeight × overlayVisibleAlpha`,
where `overlayVisibleAlpha` is the controls' existing show/hide animation (100ms tween,
`Motion.DurationInstant`; always 1 in player mode).

- Controls showing: the last row scrolls up to the controls' top (H − 300dp) and no further.
- Controls hidden: the padding is 0 and the last row reaches the sheet's bottom edge, with no gap.
- Hiding the controls while the list is at its end moves the rows down by up to 300dp, in step with the
  controls' fade, so the gap closes. Showing them again leaves the rows where they are; the controls
  cover the end of the list until it is scrolled. Away from the end, nothing moves.
- The padding change is applied by the list's measure pass, not by a scroll, so it sends no
  nested-scroll deltas and cannot feed back into the show/hide gesture.

**The reorder auto-scroll zones.** The reorder library (`sh.calvin.reorderable` 3.1.0) scrolls forward
while a dragged row's handle is within 48dp of `viewport − scrollThresholdPadding.bottom`.
`scrollThresholdPadding.bottom` is the overlay height while `overlayVisibleAlpha > 0.5` (the touch
shield's own switch point) and 0 otherwise, so the forward zone is the 48dp just above the controls,
or just above the sheet's bottom edge while they are hidden. The backward zone (48dp below the list's
top) is unchanged. The library rebuilds its state whenever this padding changes, and a rebuild ends the
drag in progress (it calls the drop handler where the row is). `QueueList` therefore keeps the padding a
drag started with until the drop; a toggle during a drag moves the zone afterwards.

**Unchanged on screen.** The DstIn mask, the offscreen compositing strategy, the fade over the controls
and the touch shield keep their positions: the mask's fade end derives from the live sheet height, the
overlay's height and offset and the list's top, never from the list's own height, and the mask's
rectangle covers the list's own layer, which the sheet clips at its bottom edge either way.

## 2. Queue mode and the controls overlay

### 2.1 State

| State | Kind | Written by | Survives collapse and navigation | Survives a configuration change |
|---|---|---|---|---|
| `isQueueMode` | `rememberSaveable` | `QueueModeState.onQueueClick` only | yes | yes |
| `queueProgress` (crossfade 0 to 1) | `Animatable` | `onQueueClick` (300ms tween) | yes | recreated from `isQueueMode`: 1 in queue mode, 0 otherwise |
| `controlsVisible` | state in the remembered `QueueModeState` | the drag gesture, `onShuffleButtonClick` (true), the empty-queue effect (true) | yes | no: comes back true |
| drag count (`accumulatedScrollDelta`, `userDragActive`) | plain fields | the nested-scroll callbacks, every `controlsVisible` write | yes | no |
| `queueListState` | `rememberLazyListState` (saveable) | the list, the generation effect | yes | yes |
| `shownGeneration` | `rememberSaveable` | the generation effect | yes | yes |
| `overlayVisibleAlpha` | `animateFloatAsState` of `!isQueueMode \|\| controlsVisible` | derived | derived | derived |
| sheet position | `AnchoredDraggableState`, `remember` | the sheet's drag detector, Back, collapse requests | n/a | no: comes back collapsed |

`ui/player/QueueModeState.kt` holds the queue-mode state; `ui/player/PlayerSheetSkeleton.kt` holds the
sheet position and `overlayVisibleAlpha`. On routes outside the bottom-bar routes the sheet is
zero-sized, not removed, so all of this lives on.

### 2.2 Showing and hiding the controls

- A net upward drag of 28dp on the list hides the controls; a net downward one of 28dp shows them.
- The count belongs to one gesture. The first finger-driven delta (`NestedScrollSource.UserInput`)
  after a release starts it at zero. The release is the list's `onPreFling`; the fling that follows
  (`SideEffect` deltas) still counts toward its own gesture, but nothing it or an earlier gesture left
  behind counts toward the next drag.
- Every write to `controlsVisible`, from the gesture or from code, sets the count back to zero. The
  writes from code: the shuffle button shows the controls; an empty queue shows them.
- Programmatic scrolls and the reorder auto-scroll send no nested-scroll deltas and never toggle the
  controls.

### 2.3 Back and collapse

| State | System Back |
|---|---|
| Sheet expanded, player mode or queue mode | collapses the sheet; queue mode stays on |
| Sheet collapsed (any mode) | not handled by the sheet; ordinary navigation |
| Sheet hidden on a non-bottom-bar route | not handled by the sheet |

Controls hidden in queue mode stay hidden across a collapse and a re-expand (`controlsVisible` lives
on). Both are accepted behaviours (`docs/queue-accepted-behaviours.md` A15).

### 2.4 Recreation

After a configuration change in queue mode, `queueProgress` starts at 1: Up Next is composed from the
first frame, the queue button is drawn on, and its first tap leaves queue mode. The list comes back at
its saved scroll position. The sheet comes back collapsed and the controls visible.

## 3. The queue generation

`PlaybackEngine.queueGeneration` (`StateFlow<Long>`) goes up by one each time the play order is
replaced as a whole. `PlaybackController` and `PlaybackViewModel` pass the engine's own flow through,
never a `stateIn` copy, so a screen reads the real value on its first frame.

### 3.1 Bump points

| Bumped | Where |
|---|---|
| A fresh play that installed its queue: a song tap on an untouched queue, page Play and Shuffle, Clear, Replace, Keep's fallback when nothing is playing, the one-song Play Next and Add to Queue with nothing playing | `playQueueInternal` (`playback/ExoPlayerEngineQueueLoad.kt`), after the new Up Next is published |
| A shuffle toggle that was applied, ON or OFF: the in-app button, a controller's shuffle command through the routing, the confirmed shuffle-off question | `setShuffleEnabledInternal` (`playback/ExoPlayerEngineQueueOps.kt`), after every refusal check and after the new Up Next is published |

| Not bumped | Why |
|---|---|
| Play Next, Add to Queue, a move, a remove, Keep's insert | edits of the existing order |
| A track change (natural or Next/Previous), a tap on an Up Next row, an auto-skip | the cursor moves, the order does not |
| A toggle that changes nothing, with no session, with no current slot, or refused by the order store | `setShuffleEnabledInternal` returns first |
| The shuffle-off question pending or dismissed; a controller's OFF refused on a reordered queue | the toggle never runs |
| A fresh play refused for lack of a session | it returns before installing |
| The restore of the saved queue, the adoption of a running session | the user's own queue comes back |

### 3.2 What it drives

Up Next scrolls to its first row (`queueListState.scrollToItem(0)`) when the generation changes, in
`rememberQueueModeState`:

- `shownGeneration` starts at the current value, so the first composition never scrolls; it is saved
  next to the list's own saved position, so a recreation with an unchanged generation keeps the place,
  and a generation that moves while the composition is away brings the restored list to the top.
- The engine publishes Up Next before the generation, in the same main-thread step, so the screen's
  composition already holds the new order when the effect runs; a reorder that keeps every key (a
  shuffle) lands on the new first row, not on wherever the old first row moved to.
- A finger on the list holds its scroll at a priority `scrollToItem` cannot take over; the effect
  waits until the finger and any fling have let go, then scrolls.
- Nothing else scrolls the list on its own. The shuffle button only toggles (and shows the controls);
  there is no pending scroll flag.

## 4. Accessibility

| Control | Semantics | Strings |
|---|---|---|
| Shuffle | `Role.Switch` with `toggleableState` On or Off | `cd_shuffle` ("Shuffle"); the state is read by the service |
| Repeat | `stateDescription` Off, All or One | `cd_repeat` ("Repeat"), `cd_repeat_off` ("Off"), `cd_repeat_all` ("All"), `cd_repeat_one` ("One") |

Both are `semantics { }` blocks on the modifier given to `DragResistantIconButton`
(`ui/components/QueuePlaybackModeRow.kt`), outermost on the node, so `Role.Switch` takes the place of
the button's `Role.Button`. The click stays `dragResistantClickable`'s, for touches and for the
accessibility click. The project has one locale (`res/values`), so there are no translations to add.

## 5. Design-system choices

- **Row ripple.** The Up Next row's `clickable` uses `ripple(color = Accent)` with its own interaction
  source, the pattern every clickable row uses (`SongRow`, `PlaylistRow`).
- **Reveal icon.** The swipe's Delete icon is drawn in `Background` on the `Error` reveal. Contrast
  against `Error` (#EE6363), by the WCAG formula: `Background` 6.19:1, `Surface` 5.69:1, `OnAccent`
  4.65:1, `TextPrimary` 2.94:1, `TextSecondary` 2.71:1, white 3.18:1. A non-text cue needs 3:1, so
  `TextPrimary` is not suitable; `Background` is the strongest existing token.

## 6. Decisions and implementation choices

The maintainer's decisions: the list's visible height and the padding that follows the controls,
with the on-screen positions of the mask, fade and shield unchanged (#410, #411); the reorder
threshold padding that follows the controls (#410); the drag-count resets (#416); the queue generation,
its bump points and the scroll to the top, replacing the shuffle-only scroll flag (#395, #415); queue
mode consistent after a recreation (#418 part 3); the state semantics of Shuffle and Repeat without
replacing their click handling (#421); the Accent ripple and a token for the reveal icon (#420 part 1);
and the accepted behaviours of #413, #414, #417 and #418 parts 1 and 2.

Implementation choices:

- The count resets on the first `UserInput` delta after `onPreFling`, and in the `controlsVisible`
  setter.
- The padding animates through `overlayVisibleAlpha`; the threshold padding steps at 0.5 and is held
  for the length of a drag.
- The generation's scroll waits for a finger to let go.
- The handled generation is saved with `rememberSaveable`.
- The reveal icon uses `Background`.
- The shuffle button keeps showing the controls.

## 7. Out of scope

Swipe-to-remove and Undo, and the fling threshold (#412); a "from the start" list of the wrapped
songs (Up Next on the last song shows a marker instead: under Repeat All "Repeat All is on. Playback
continues from the start.", under Repeat One "Repeating this song.", under Repeat Off "Nothing
queued after this"; the text follows the repeat mode at once and the list itself is unchanged, see
`docs/queue-accepted-behaviours.md` A17, #399); the composition and recomposition cost of the list and its rows, the list staying composed
while the sheet is collapsed (#419), and every row receiving the whole list (#420 part 2); the O(N)
queue rewrites (#406); Back handling; the overlay's touch shield; the hide/show threshold itself;
reorder destination identity; shuffle, persistence, entry-point and engine semantics; Add to Queue
placement; history, Clear and count display (#431).

## 8. Traceability

Device tests are in `app/src/androidTest`, JVM tests in `app/src/test`, under `com.v1per.synthx`.

| Decision | Tests |
|---|---|
| Visible-height list; last row at the controls' top or the sheet's bottom edge; hiding at the end closes the gap; a one-row list stays at the top; list bounds and mask unchanged (#410, #411) | `ui.player.QueueUpNextGeometryTest` (`withTheControlsShowing…`, `withTheControlsHidden…`, `hidingTheControlsAtTheEnd…`, `aOneRowUpNext…`, `theListsOnScreenBounds…`, `theMaskHidesRows…`) |
| Forward auto-scroll zone above the controls or the bottom edge; a toggle mid-drag does not end the drag (#410) | `ui.player.QueueUpNextGeometryTest` (`aHandleHeldJustAboveTheControls…`, `withTheControlsHiddenTheForwardZone…`, `theControlsTogglingMidDrag…`) |
| Drag-count resets; one deliberate drag still toggles; a fling counts in its own gesture only (#416) | `ui.player.QueueModeControlsVisibilityTest` |
| Generation bump points and non-bump points; Up Next published first (#395, #415) | `playback.QueueGenerationInstrumentedTest`, `playback.QueueGenerationRestartInstrumentedTest` |
| The view model passes the engine's flow through (#415) | JVM `playback.PlaybackViewModelQueueGenerationTest` |
| Scroll to the top on a change only; not for edits, a no-op toggle, a track change, a return with the same generation; after a return with a moved one; after a finger lets go (#395, #415) | `ui.player.QueueModeGenerationScrollTest` |
| End to end on the real engine: a shuffle toggle and a fresh play open at the top, Play Next keeps the place (#395, #415) | `playback.QueueGenerationScrollInstrumentedTest` |
| End to end in the shell: a new generation scrolls, an edit does not; recreation keeps queue mode and the place (#415, #418 part 3) | `ui.UpNextShellInstrumentedTest` |
| `queueProgress` from the saved flag (#418 part 3) | `ui.player.QueueModeRecreationTest` |
| Accepted: hidden controls across collapse and re-expand; Back collapses in queue mode (#418 parts 1, 2) | `ui.UpNextShellInstrumentedTest` (`controlsHiddenByAnUpwardDrag…`, `backWithTheSheetOpenInQueueMode…`) |
| Shuffle switch and Repeat state semantics; clicks still reach the handlers (#421) | `ui.components.QueuePlaybackModeRowSemanticsTest`; `ui.QueueModeTogglesShellInstrumentedTest` |
| Row tap still plays; reveal icon not white (#420 part 1) | `ui.components.QueueRowRevealTest` |
| Accepted: positional reorder destination (#414) | JVM `playback.QueueOrderStoreTest.aMoveRunAfterTheCursorAdvancedLandsOneSlotLowerThanReleased` |
| Accepted: swipe races (#413) | `ui.player.QueueSwipeRemoveTest`; the engine refuses to remove the current slot (`ExoPlayerEngineQueueOps.removeQueueItemInternal`) |
| Accepted: the controls' shield (#417) | `ui.PlayerSheetGestureInstrumentedTest.draggingDownOnTheQueueControlsLeavesTheSheetOpen` |

## 9. Checked by hand on a phone

These cannot be judged by a test and are checked on the maintainer's phone:

- how fast the drag auto-scroll runs near the controls;
- how the controls' hide and show feel with the count reset;
- the end of Up Next with the controls visible and hidden, including the rows moving down as the
  controls hide at the end;
- the Accent ripple on bright album art;
- the reveal icon;
- the TalkBack wording;
- the scroll to the top after a new queue and after a shuffle toggle.
