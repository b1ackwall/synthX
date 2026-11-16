# 03 — Up Next UI: reorder, swipe-to-remove, tap-to-play, and the queue-mode transport overlay

Issues **#26 – #37**. Paths are relative to `app/src/main/java/com/v1per/synthx/` unless they
start with `app/`. See [README.md](README.md) for the severity legend and the full index.

## Layout recap (expanded sheet, queue mode)

```
y = 0      ┌───────────────────────────────┐
           │ ExpandedTopMargin (32dp)      │
           │ header: art, title, ⋮,        │   header region; the sheet's own drag detector
           │ Shuffle / Repeat row          │   owns touches above queueListOffsetY
y = 180dp  ├───────────────────────────────┤ ← queueListOffsetY = 32 + (68−44)/2 + (48+56+32)
           │ QueueList (LazyColumn)        │
           │   height = fullHeight         │   requiredHeight(fullHeight), then offset +180dp,
           │                               │   so its last 180dp lie BELOW the sheet's bottom edge
H − 300dp  ├───────────────────────────────┤ ← controls overlay top (ControlsOverlayHeight = 300dp)
           │ seek bar / ◀◀ ▶ ▶▶ / queue btn│   touch shield + DstIn mask over the list
y = H      └───────────────────────────────┘   (sheet clip)
             … QueueList continues 180dp below the visible area …
```

(`ui/player/QueueArtPositioning.kt:98-103`, `ui/player/PlayerSkeletonQueueMetrics.kt`,
`ui/player/PlayerSheetMetrics.kt:20,29`, `ui/player/PlayerSkeletonQueueContent.kt:283-313`)

---

### #26 — While dragging a row to reorder, the list never auto-scrolls downward, so a song can't be moved below the visible part of Up Next

**Severity:** Medium · **Type:** Bug ·
**Where:** `ui/components/QueueList.kt:84` (`rememberReorderableLazyListState(state)` with no
`scrollThresholdPadding`), `ui/player/PlayerSkeletonQueueContent.kt:290-303`

**What happens.** The reorder library (`sh.calvin.reorderable` 3.1.0) starts auto-scrolling forward
when the dragged handle comes within `scrollThreshold` (48dp) of
`viewportSize.height − scrollThresholdPadding.bottom`. This was checked against the library source:
`ReorderableLazyCollection.getScrollAreaOffsets`.

* `QueueList`'s viewport is `requiredHeight(fullHeight)`, but it is placed 180dp lower
  (`offset(y = queueListOffsetY)`). So the viewport's last 180dp are below the sheet's bottom edge.
* `scrollThresholdPadding` is left at 0dp.

The forward auto-scroll zone therefore sits 132–180dp **off-screen**, where no finger can reach it.
The backward (upward) zone, just below the header, works. In practice:

* You cannot drag a song further down than the bottom of what is currently visible. You have to drop
  it, scroll and drag again.
* While the transport overlay is visible, a row dragged downward also passes **under** the 300dp
  controls block, which masks it with the DstIn fade, so the user is dragging blind.

**Suggested fix.** Pass
`scrollThresholdPadding = PaddingValues(bottom = queueListOffsetY + visibleOverlayHeight)`,
where `visibleOverlayHeight` is 300dp when the controls are visible and 0 when hidden. Alternatively, give the
list the visible height (`fullHeight − queueListOffsetY`) instead of `fullHeight`. Add an
instrumented reorder test that drags a row past the bottom edge of a list longer than the screen.

---

### #27 — The list's bottom padding ignores its 180dp downward offset, so the last rows can never be scrolled above the visible controls

**Severity:** Low · **Type:** Bug (layout maths) ·
**Where:** `ui/player/PlayerSkeletonQueueContent.kt:290` (`contentPadding = PaddingValues(bottom = controlsOverlayHeight)`),
`ui/components/QueueList.kt` kdoc ("the padding extends the scroll range so the last row can still be brought clear …")

**What happens.** When the list is scrolled to its end, the last row's bottom edge sits at
`viewportHeight − 300dp` in list coordinates. On screen that is
`180dp + H − 300dp`, which is **180dp below the top of the controls overlay**. So with the
controls visible, the last ~180dp of Up Next (about 2–3 rows) can never be brought above them.
Those rows are covered by the overlay's touch shield (they can't be tapped) and faded by the mask.
They only become reachable after hiding the overlay with an upward drag. In that state the list
then ends with ~120dp of empty space under the last row.

The auto-hide on an upward drag hides most of this in normal scrolling. The bug shows up whenever
the user drags back down a little near the end, or on short queues that can't scroll at all.

**Suggested fix.** `contentPadding = PaddingValues(bottom = controlsOverlayHeight + queueListOffsetY)`.
Or better, fix the list's height as described in #26, which removes the off-screen 180dp entirely.

---

### #28 — Swipe-to-remove is instant and permanent with no undo, and any fast flick removes regardless of distance

**Severity:** Medium · **Type:** UX / data-loss risk ·
**Where:** `ui/components/QueueRow.kt:72, 140-157, 199-207`, `playback/QueueOrderStore.kt:176-191`

**What happens.**

* A settled swipe calls `onRemove` immediately. The engine strips the slot from **both** orders
  ("always permanent, regardless of shuffle state") and persists. There is no snackbar with
  **Undo**, no confirmation, and no way back short of finding and re-adding the song.
* The fling threshold is `AnchoredDraggableDefaults`' fixed 125dp/s. Above that velocity *any*
  release dismisses, however short the drag (documented in `QueueRow.kt:66-71`). A slightly
  diagonal flick while scrolling a long Up Next list can remove a song.

**Suggested fix.** Show an "Removed from queue · Undo" snackbar that re-inserts the same slot (keep
the record and its previous neighbour so it can be re-inserted in both orders). Optionally raise the
velocity threshold with a custom fling behaviour, as the kdoc already suggests.

---

### #29 — Swiping away the first Up Next row just as the track changes plays the song the user dismissed

**Severity:** Low · **Type:** Race ·
**Where:** `playback/ExoPlayerEngineQueueOps.kt:155-158`, `ui/components/QueueRow.kt:164-168`

**What happens.** The first Up Next row's slot can become the current slot (auto-advance) between the
swipe settling and `removeQueueItem` running. The engine then refuses the removal
(`if (slotId == _currentSlotId.value) return`). The row disappears anyway, because it is no
longer in `upcomingQueue`. The self-heal effect only looks for the slot *in Up Next*, so the user sees a successful
dismissal while the dismissed song is now playing.

**Suggested fix.** If the swiped slot became current, treat the swipe as "skip": call `next()`, or
remove the slot after advancing. Alternatively, show feedback that it was already playing.

---

### #30 — The reorder drop target is a position resolved against the cursor at execution time; a track change during the drop lands the row one slot off

**Severity:** Low · **Type:** Race (documented trade-off) ·
**Where:** `playback/QueueOrderStore.kt:133-164` (kdoc notes the accepted trade-off), `ui/components/QueueList.kt:109-134`

**What happens.** The *moved* row is identified by slot id, which is safe. The *destination* is a plain
index into Up Next (`to = currentIndex + 1 + toDisplayIndex`). The UI computes `endIndex` from the
list it last recomposed with. If the engine advanced a track after that recomposition but before the
drop runs, the index is applied against an Up Next that has lost its first row, so the song lands
one position lower than where the user released it. The same applies to the TalkBack "Move up" and
"Move down" actions (`QueueList.kt:139-148`).

**Suggested fix.** Identify the destination by its neighbour, "insert after slot Y" (or "before
slot Z"), using the slot ids of the row's neighbours in the dropped `localQueue`.

---

### #31 — Up Next keeps the previous queue's scroll position when a brand-new queue starts

**Severity:** Low · **Type:** UX bug ·
**Where:** `ui/player/QueueModeState.kt:172, 243-248`

**What happens.** `queueListState` lives as long as the player sheet does, and the sheet stays
composed across queue replacements. A new queue (Play or Shuffle buttons, or a song-row tap) replaces
every key. `LazyListState` then keeps its old `firstVisibleItemIndex`. A user who had scrolled to row
300 of the old queue opens Up Next for the new album and lands near the end of it, or in the middle
of a long one. Only the in-queue shuffle button arms a scroll-to-top.

**Suggested fix.** Scroll to the top when the queue identity changes: have the engine publish a "queue
generation" that is bumped by `playQueueInternal`, and key a `LaunchedEffect` on it.

---

### #32 — The overlay show/hide drag accumulator is never reset, so leftovers from earlier gestures flip the controls on tiny drags

**Severity:** Low · **Type:** Bug (gesture) ·
**Where:** `ui/player/QueueModeState.kt:66, 83-99`

**What happens.** `accumulatedScrollDelta` is zeroed only when it crosses ±28dp. It is not reset at the
start of a new gesture, when queue mode is left or re-entered, or when `controlsVisible` is
forced to true (shuffle click, empty queue). A 20dp upward nudge in one gesture plus a 9dp nudge
minutes later hides the transport controls unexpectedly. Fling deltas also feed the same
accumulator, so a fling's tail is counted against the *next* gesture.

**Suggested fix.** Reset the accumulator on each new drag. One way: track `NestedScrollSource.UserInput` and reset
it in `onPreFling` or `onPostFling`, and whenever `controlsVisible` is set programmatically.

---

### #33 — In queue mode, the band under the visible controls is dead to scrolling and to the hide/show gesture

**Severity:** Low · **Type:** UX ·
**Where:** `ui/player/PlayerSkeletonControlsOverlay.kt:193-203` (touch shield), `ui/player/PlayerSheetSkeleton.kt:308-312`

**What happens.** While the controls overlay is visible, it covers the bottom 300dp of the list.

* Its background shield is the topmost hit target there, so the `QueueList` underneath gets no
  pointer events at all.
* The sheet's own detector also bails for touches below `queueListOffsetY`.

So a drag that starts anywhere on the overlay's empty area does nothing: it doesn't scroll the
list, doesn't hide the controls, and doesn't move the sheet. The usable scroll strip is
`H − 180dp − 300dp`, which on a ~700dp-tall sheet is ~220dp, roughly three rows.

**Suggested fix.** Let vertical drags on the shield fall through to the list (consume taps only), or
forward them to the list's scroll state.

---

### #34 — With the controls hidden, collapsing and re-expanding the sheet lands in queue mode with no transport and no hint; Back collapses the sheet instead of leaving queue mode

**Severity:** Low · **Type:** UX ·
**Where:** `ui/player/QueueModeState.kt:46-61`, `ui/player/PlayerSheetSkeleton.kt:122-124, 179-183`

**What happens.**

* `controlsVisible` (not saveable, but it outlives collapse and navigation) and `isQueueMode`
  (saveable) both persist across collapse and expand. If the user hid the controls with an upward
  drag and then collapsed the sheet, the next expand shows Up Next with **no seek bar, no
  play/pause and no queue button**. The only way back is the undocumented downward drag on the list.
* The queue button, which is the only way out of queue mode, lives inside that hidden overlay.
* System Back collapses the sheet rather than returning from queue mode to the player.

**Suggested fix.** Reset `controlsVisible = true` when the sheet settles expanded or when queue mode is
entered. Let Back leave queue mode before it collapses the sheet.

---

### #35 — The queue list stays composed (offscreen layer and mask included) while the sheet is collapsed in queue mode

**Severity:** Low · **Type:** Performance ·
**Where:** `ui/player/PlayerSkeletonQueueContent.kt:225, 249`

**What happens.** `QueueList` is composed whenever `queueProgressValue > 0f`. That value depends only on the
queue toggle, not on whether the sheet is expanded. Queue mode survives collapse, so a user who
left the sheet in queue mode keeps a full-height `LazyColumn` (with `CompositingStrategy.Offscreen`
when its alpha is > 0, and a DstIn mask) composed behind the mini player. It recomposes on every
track change for the rest of the session.

**Suggested fix.** Also gate composition on the sheet's expand progress, for example
`queueProgressValue > 0f && queueFadeAlpha > 0f`. Keep the `LazyListState` so the scroll position
survives.

---

### #36 — Every visible row receives the whole Up Next list, and rows deviate from the design system

**Severity:** Low · **Type:** Performance / consistency ·
**Where:** `ui/components/QueueList.kt:98-101`, `ui/components/QueueRow.kt:164, 234, 250`

**What happens.**

* `QueueRow(queue = queue, …)` passes the entire list to each row, only for the self-heal check
  in `LaunchedEffect(queue)`. Any queue change, including every track transition, gives each visible
  row a new parameter, so all of them recompose and relaunch their effect. The check only needs a
  boolean ("is my slot still in the queue?") or a slot-id set.
* The row's `.clickable(onClick = onClick)` uses the theme's default ripple. The design system
  reserves an **Accent** ripple for clickable rows, and `SongRow` uses `ripple(color = Accent)`.
* The swipe reveal icon uses a raw `Color.White` instead of a token from `ui/theme/Color.kt`.

---

### #37 — The Shuffle and Repeat buttons don't expose their on/off state to accessibility services

**Severity:** Low · **Type:** Accessibility ·
**Where:** `ui/components/QueuePlaybackModeRow.kt:54, 66`

**What happens.** The state is shown only through tint. The `contentDescription` is always "Shuffle" or
"Repeat", with no `stateDescription`, `Role.Switch` or `toggleable` semantics. A TalkBack user cannot tell
whether shuffle is on or which of Off/All/One repeat is active, so cannot predict what a tap
will do. That matters because the toggle can destroy queue content (#1, #2).

**Suggested fix.** Use `Modifier.toggleable(value = shuffleEnabled, role = Role.Switch)` for shuffle, and a
`stateDescription` of "Off", "All" or "One" for repeat.
