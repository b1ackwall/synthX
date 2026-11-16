# 01 — Shuffle (toggle, Play/Shuffle buttons, interaction with an already-shuffled queue)

Issues **#1 – #12**. Paths are relative to `app/src/main/java/com/v1per/synthx/` unless they
start with `app/`. See [README.md](README.md) for the severity legend and the full index.

## How shuffle works today (needed to read the issues)

The engine keeps two orders in `QueueOrderStore` (`playback/QueueOrderStore.kt`), each a list of
`(slotId, songId)` records:

* **`live`**: the order that actually plays. While shuffle is on, this is the shuffled order.
* **`baseline`**: the "unshuffled" order that is supposed to survive shuffle toggles.

The engine (`playback/ExoPlayerEngineQueueOps.kt:40-74`, `setShuffleEnabledInternal`) handles the toggle like this:

* **Shuffle ON** → `QueueOrderStore.shuffle` (`:239-246`): `live = [current] + shuffled(baseline − current)`.
  Baseline is left as it is.
* **Shuffle OFF** → `QueueOrderStore.unshuffle` (`:260-277`):
  `live = baseline = [current] + baseline.after(current) + sessionExtras`. Here `sessionExtras` means every
  `live` slot that is neither the current slot nor in baseline (the Play Next / Add to Queue
  items added while shuffled). Everything that comes **before** the current slot in baseline is
  deleted from both orders. The engine then prunes `songsBySlotId` to the new live order (`:60`),
  rebuilds the player window and persists the result atomically (`:71`).
* **Play Next / Add to Queue / Keep while shuffled** insert into `live` only
  (`mirrorToBaseline = !shuffleEnabled`, `ExoPlayerEngineQueueOps.kt:89,106`).
* **Reorder while shuffled** touches `live` only (`:136`). **Remove** is permanent in both orders.
* **Fresh plays**: the Play button passes `shuffle = false`, the Shuffle button passes `shuffle = true`
  (start song chosen at random), and a song-row tap passes `shuffle = null`, which keeps the current flag
  (`PlaybackViewModel.kt:184-206`, `PlayAllRequest.kt:23-28`, `ExoPlayerEngineQueueLoad.kt:51-62`).

All of the scenarios below were run against a line-for-line Java port of `QueueOrderStore`. The
port and its raw output are in [appendix-shuffle-simulation.md](appendix-shuffle-simulation.md).

---

### #1 — Turning shuffle OFF while a song queued during shuffle is playing wipes the entire queue

**Severity:** Critical · **Type:** Bug (queue data loss) ·
**Where:** `playback/QueueOrderStore.kt:260-277` (`unshuffle`), `playback/ExoPlayerEngineQueueOps.kt:52,60,64,71`

**What happens.** Shuffle is on. The user adds a song with **Play Next**, **Add to Queue** or the
**Keep** answer of the "Keep the songs in your Up Next queue?" prompt. Each of these inserts the
song into `live` only. Later that song becomes the current song: it is reached naturally, tapped
in Up Next, or (with Keep) jumped to straight away. If the user now turns shuffle OFF, the
whole queue shrinks to that one song, plus any other songs still pending from the same shuffle session.

**Why.** `unshuffle` looks the current slot up in `baseline`:

```kotlin
val baselineIndex = baseline.indexOfFirst { it.slotId == current.slotId }   // -1: never mirrored
val afterCurrentInBaseline = if (baselineIndex >= 0) baseline.subList(...) else emptyList()
...
val target = listOf(current) + afterCurrentInBaseline + sessionExtras
live = target; baseline = target
```

A slot added while shuffled is never in baseline. So `afterCurrentInBaseline` is empty, and the
entire baseline (the album or playlist the user was listening to) disappears from both orders.
Then the engine:

1. prunes `songsBySlotId` to the one-song live order (`ExoPlayerEngineQueueOps.kt:60`),
2. removes every other item from the player (`maintainWindow(force = true)`, `:64`),
3. persists the result with the unified atomic write (`:71`).

The loss is therefore permanent, and it survives an app restart.

**Repro (simulation S1).** Play an 8-track album in order. Turn shuffle ON. Use Play Next on song X.
Skip to X, or tap it in Up Next. Turn shuffle OFF. Result: `live=[X] baseline=[X]`.
The most common real-world path is the Keep prompt. The user has a hand-edited queue with shuffle
on, taps a song, picks **Keep** (the song becomes current immediately), then turns shuffle off.

**Impact.** The user's whole queue (for example a 500-song playlist) silently becomes one song.
There is no undo and no warning. Up Next shows "Nothing queued after this".

**Suggested fix.** Make `unshuffle` total: it must never drop a slot that is in `live` and has not
played yet. If the current slot is not in baseline, anchor it after the closest baseline slot that
precedes it in `live`, or fall back to `live`'s own order. Add a unit test
`unshuffleWithCurrentSlotOutsideBaselineKeepsEveryUnplayedSlot` to `QueueOrderStoreTest`, plus an
engine-level instrumented test for the Keep → shuffle-off path. No such test exists today.

---

### #2 — Turning shuffle OFF permanently deletes every song that precedes the current one in the original order, including songs that were never played

**Severity:** High · **Type:** Wrong implementation (documented as "accepted, intentional", but the consequences are not acceptable) ·
**Where:** `playback/QueueOrderStore.kt:248-277`, test that locks it in: `app/src/test/.../playback/QueueOrderStoreTest.kt:378-392`

**What happens.** Turning shuffle OFF rebuilds the queue as "the current song, then the original
order after it". Every song that sits *before* the current song in the original (baseline) order
is deleted from the queue, and nothing checks whether it was actually played. With shuffle on,
the current song is usually somewhere random in the original order. So a large, random chunk of
**never-played** songs disappears.

**Repro (simulation S5).** Play a 12-track album in order and start at t6. Turn shuffle ON; the order
becomes `[t6, t2, t12, t11, …]`. Listen to two songs, so the current song is t12. Turn shuffle OFF.
Result: `live=[t12]`. Nine songs the user never heard (t1–t5, t7–t11) are gone. Turning shuffle ON
again to get "a fresh shuffle" now shuffles a one-song queue.

**Knock-on effects.**

* **Repeat All shrinks.** After shuffle OFF, the Repeat All loop only covers `[current … end]`, not the
  album or playlist the user started. In S5 Repeat All loops t12 forever.
* **Re-shuffling destroys the queue.** No "reshuffle" action exists. The only way to get a new random
  order is OFF→ON, and every OFF truncates further.
* **Previous can't go back.** After OFF the current song is at index 0, so Previous only restarts it
  (`SystemNavigationRules.kt:44-51`).
* **The truncation is persisted** by the unified write, so it survives restarts.

**Why it is wrong, not just a design choice.** It is normal for the songs before the current one to
stop being *upcoming* when shuffle is turned off; Spotify and Apple Music both continue from the
current song in context order. What is not normal is deleting them from the queue entirely. Those
songs should become **history**: reachable with Previous, included in Repeat All, and
re-shufflable. Here they are deleted.

**Suggested fix.** On OFF, set `live = baseline` (the full context in original order, plus Play-Next
items, see #4) and leave the cursor on the current slot at its baseline position. Songs before it
then become history instead of being dropped. `previous()` and Repeat All then cover the whole
context, and a later shuffle ON reshuffles the whole context again. Update the
`unshuffleRestoresBaselineTailAndFoldsInSessionAdditions` test, which currently asserts the
deletion.

---

### #3 — The detail-page **Shuffle** button followed by shuffle OFF loses a random portion of the album/artist/playlist

**Severity:** High · **Type:** Bug (interaction of #2 with the Shuffle button's random start) ·
**Where:** `playback/PlayAllRequest.kt:23-28`, `playback/ExoPlayerEngineQueueLoad.kt:51-62`, `playback/QueueOrderStore.kt:260-277`

**What happens.** The Shuffle button on Album, Artist and Playlist pages
(`DetailPlayShuffleRow` → `PlaybackViewModel.shuffleAll`) picks a **random start song** with
`random.nextInt(playable.size)`. The engine then builds `live = [start] + shuffled(rest)` and keeps
`baseline` in list order. If the user then turns shuffle OFF (for example, they changed their mind
and want the album in order), the songs before the random start are deleted by #2, even if
**zero songs have played**.

**Repro (simulation S4).** On a 12-track album, the Shuffle button picks t7. The user turns shuffle OFF
straight away. Result: `[t7 … t12]`, and t1–t6 are gone. On average, half of the list is lost.

**Impact.** Very easy to trigger: tap Shuffle, then tap the shuffle toggle. The Shuffle button
has no visible side effect that would warn the user.

**Suggested fix.** Fixed by the #2 fix. Also add an instrumented test next to
`PlayQueueShuffleInstrumentedTest.turningShuffleOffAfterAShuffledPlayRestoresListOrderAfterTheCurrentSong`
that asserts the *songs before* the start are kept as history.

---

### #4 — Songs queued while shuffled jump to the very end when shuffle is turned off, and songs already heard are queued again

**Severity:** High · **Type:** Wrong implementation ·
**Where:** `playback/QueueOrderStore.kt:268-272` (`sessionExtras`), `playback/ExoPlayerEngineQueueOps.kt:76-93`

**What happens.** `unshuffle` appends every shuffle-session addition (`sessionExtras`) **after the
whole remaining baseline**. Two problems follow:

1. **Play Next is broken by a shuffle toggle.** With shuffle on, the user does "Play Next" on X,
   then turns shuffle OFF before X plays. X moves from "next" to "after the last song of the
   album/playlist" (simulation S2: `[t1 … t8, X]`). With a 500-song playlist, X effectively never
   plays.
2. **Already-heard extras are re-queued.** `sessionExtras` is built from *all* of `live`, including
   slots **before** the cursor (history). A Play-Next song that already played comes back at the
   end of Up Next (simulation S3: `[t8, X]`, where X was heard before t8). Meanwhile, the
   baseline history before the cursor is deleted (#2). Played baseline songs are dropped while
   played extras are resurrected, which is the opposite of what a user expects.

**Suggested fix.** Keep user-queued items in their own "Playing Next" section that shuffle toggles never
reorder (Apple Music's model). At minimum, place *unplayed* extras directly after the current
song in their `live` order, and leave *played* extras in history.

---

### #5 — External controllers (car/Bluetooth/Assistant/Wear) can switch on ExoPlayer's own shuffle, and the app neither prevents nor follows it

**Severity:** High · **Type:** Bug (playback order diverges from the app's queue) ·
**Where:** `playback/SynthXPlaybackService.kt:75-107`, `playback/SystemNavigationPlayer.kt:20-21`
("Everything else, including seek-to-queue-item and shuffle, is deliberately still the raw player"),
`playback/ExoPlayerEngineListener.kt` (there is no `onShuffleModeEnabledChanged` handler)

**What happens.** App shuffle is fully app-managed: the order store is reordered and ExoPlayer's
own `shuffleModeEnabled` is never used. However, the media session exposes the raw ExoPlayer's
`COMMAND_SET_SHUFFLE_MODE`. Several things can call it:

* a car head unit or Bluetooth (AVRCP) shuffle button,
* "Hey Google, shuffle",
* Wear OS or any other `MediaController`.

Any of them flips **ExoPlayer's native shuffle** on. ExoPlayer then auto-advances through its own
random `ShuffleOrder` *within the loaded ~51-item window*.

* `onMediaItemTransition` adopts whatever slot ExoPlayer jumps to, so the cursor leaps around the
  order store. Up Next, `hasNext`, the in-app Next/Previous buttons and the window maintenance all
  assume timeline order (`previousAction` uses `loadedIndex - 1`) and now disagree with what
  actually plays.
* `prefetchArtwork` follows `ctrl.nextMediaItemIndex` (shuffle-aware) and fills artwork for the wrong item.
* Nothing ever turns the native shuffle back off. The engine never writes `shuffleModeEnabled`, so
  the flag stays on until the service dies. The app's own shuffle button shows "off" the whole
  time.

**Impact.** In-car use (the most likely place to press a hardware shuffle button) produces a
playback order that matches neither the app's queue nor its shuffle state.

**Suggested fix.** In `SystemNavigationPlayer`:

* override `setShuffleModeEnabled` to route to the engine's app-managed shuffle through `SystemNavigationBridge`, or to ignore it,
* override `getShuffleModeEnabled` to report the app's flag,
* rewrite `COMMAND_SET_SHUFFLE_MODE` availability accordingly.

As a safety net, force `player.shuffleModeEnabled = false` in the service and reset it in the
engine whenever it sees the native flag change. Add an instrumented test in
`SystemNavigationPlayerInstrumentedTest` that calls `setShuffleModeEnabled(true)` through a session
controller.

---

### #6 — Reorders made while shuffled are silently discarded when shuffle is turned off

**Severity:** Medium · **Type:** Poor implementation / UX (documented "per spec") ·
**Where:** `playback/ExoPlayerEngineQueueOps.kt:127-141`, `playback/QueueOrderStore.kt:149-164`

**What happens.** A drag-reorder while shuffled edits `live` only. On shuffle OFF, `live` is
rebuilt from `baseline`, so every reorder the user made disappears without notice. Removals made
while shuffled, by contrast, are permanent (`QueueOrderStore.remove` strips both orders). So two
edits made on the same Up Next screen behave differently depending on an unrelated toggle, and
nothing in the UI hints at it (`QueueList` kdoc only calls the reorder "temporary").

**Suggested fix.** Either carry the user's relative order into the unshuffled order (move the
dragged slot to just after the same neighbour in baseline), or tell the user ("Turning shuffle
off restores the original order") when a shuffled queue has been hand-reordered.

---

### #7 — Shuffle and unshuffle trust `baseline` blindly; an empty or partial baseline collapses the queue

**Severity:** Medium · **Type:** Robustness (same root cause as #1) ·
**Where:** `playback/QueueOrderStore.kt:239-246, 260-277`, `playback/QueuePersistence.kt:329-334`,
`playback/ExoPlayerEngineQueueLoad.kt:94, 109`

**What happens.** `shuffle()` builds the new order from `baseline`, not from `live`:
`live = [current] + shuffled(baseline − current)`. If `baseline` is empty or missing slots
that are in `live`, turning shuffle ON silently drops those slots. With an empty baseline the
queue becomes `[current]`.

**How baseline can be empty or partial while `live` is not.**

* `loadBaselineQueue()` swallows *any* exception and returns `emptyList()` (`QueuePersistence.kt:329-334`).
  The restore path reads the active queue and the baseline in two separate calls
  (`ExoPlayerEngineQueueLoad.kt:94` and `:109`; see #19). A failure on the second read restores a
  full `live` with an empty `baseline`.
* A torn write between the separate baseline and active commits (#20).
* A restore that drops missing songs rewrites only the active order (#21).

**Suggested fix.** Treat `baseline ⊇ live − sessionExtras` as an invariant: on restore, when
baseline is empty or does not contain the current slot, rebuild it from `live`. Make `shuffle()` and
`unshuffle()` preserve every unplayed `live` slot (assert `size` is unchanged in tests).

---

### #8 — Turning shuffle ON puts songs already heard this session back into Up Next

**Severity:** Low · **Type:** Design note ·
**Where:** `playback/QueueOrderStore.kt:232-246`

**What happens.** "Everything else in baseline (played or not) is reshuffled after it." If the user
is at song 50 of 100 in order and turns shuffle on, songs 1–49 (already heard) are mixed back into
Up Next. Spotify does something similar, but Apple Music shuffles only what is still upcoming. This is a product decision; it
is recorded here because, combined with #2, every ON/OFF cycle changes which songs are "left" in
ways the user can't predict.

---

### #9 — Repeat All with shuffle on replays the identical shuffled order every loop

**Severity:** Low · **Type:** Design note ·
**Where:** `playback/ExoPlayerEngineQueueState.kt:154-161` (`nextIndexInOrder` wraps to index 0 of the same `live`)

**What happens.** The shuffled `live` order is fixed when shuffle is enabled. Repeat All wraps to
`live[0]` and replays the same permutation forever. Many players reshuffle at the end of each pass.
There is no way to get a new order without OFF→ON, which truncates (#2).

---

### #10 — Shuffle and Repeat taps are computed from UI-captured state; a fast second tap is lost, and rejected toggles give no feedback

**Severity:** Low · **Type:** Poor implementation ·
**Where:** `ui/SynthXBottomNav.kt:185-186`, `playback/ExoPlayerEngineQueueOps.kt:42-44`, `playback/ExoPlayerEngine.kt:340-342`

**What happens.**

* `onShuffleClick = { setShuffleEnabled(!shuffleEnabled) }` and
  `onRepeatClick = { setRepeatMode(repeatMode.next()) }` read the values captured at the last
  recomposition. Two taps before the next recomposition send the *same* target. The second shuffle
  tap is then dropped by the `enabled == _shuffleEnabled.value` early return, and the second repeat
  tap re-sets the same mode. The user expected two toggles and gets one.
* `setShuffleEnabledInternal` silently returns when `controller == null` or there is no current
  slot. `setRepeatMode` silently does nothing when `controller == null`. The button simply doesn't
  change and there is no message.

**Suggested fix.** Add `toggleShuffle()` and `cycleRepeat()` in the ViewModel, computed from the engine's
own current value at execution time, and surface a refusal through `errors` the way
`playNext`/`addToQueue` already do.

---

### #11 — A shuffle tap that doesn't change the order leaves a "scroll to top" pending, which fires on the next unrelated queue change

**Severity:** Low · **Type:** Bug (UI) ·
**Where:** `ui/player/QueueModeState.kt:143-147, 243-248`

**What happens.** `onShuffleClickWithScrollReset` always sets `pendingShuffleScrollReset = true`. The
reset only runs (and clears the flag) when `upcomingQueue` actually changes. Several cases produce
no change:

* the engine rejects the toggle (#10),
* shuffle ON on a one-song Up Next,
* shuffle OFF where the resulting upcoming list equals the current one.

In those cases the flag stays armed. The next unrelated change (a track transition, a swipe-to-remove, a
Play Next) yanks the list back to the top, even if the user was deep in a 500-row list. The kdoc
acknowledges the "identical ordering" case but not the rejection case, and not the surprise
scroll.

**Suggested fix.** Clear the flag when the toggle is refused, or key the reset on a shuffle "generation"
counter published by the engine instead of on list identity.

---

### #12 — Stale rationale and dead cancellation scaffolding around `setShuffleEnabled`

**Severity:** Low · **Type:** Code quality ·
**Where:** `playback/PlaybackViewModel.kt:290-299`

**What happens.** The comment says "a large-queue reshuffle dispatches its MediaItem build off Main ...
a rapid second toggle can land before the first's off-main work finishes". That is no longer true.
`setShuffleEnabledInternal` runs entirely inside `withContext(Dispatchers.Main.immediate)` with no
suspension point, and `viewModelScope` is `Main.immediate`, so the launched job always runs to
completion synchronously and `setShuffleEnabledJob?.cancel()` can never cancel anything. The
comment misleads anyone who has to reason about #10.
