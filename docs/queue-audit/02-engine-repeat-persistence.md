# 02 — Queue engine: repeat, navigation, errors, persistence and restore

Issues **#13 – #25**. Paths are relative to `app/src/main/java/com/v1per/synthx/` unless they
start with `app/`. See [README.md](README.md) for the severity legend and the full index.

## Architecture recap

* `QueueOrderStore` holds the full order. The ExoPlayer timeline only holds a window around the
  current song: `WINDOW_BACK = 10`, `WINDOW_FORWARD = 40` (`playback/ExoPlayerEngineWindow.kt:16-29`).
* `next()`, `previous()` and `hasNext` are answered from the order store, not from the timeline.
* Persistence is a single FIFO writer (`QueuePersistence`) over Room tables:
  * `queue_items`: the active order,
  * `queue_baseline_items`: the baseline order,
  * `playback_cursor`: current slot, position, shuffle flag and repeat mode.

---

### #13 — Unplayable tracks are never skipped automatically, so one bad file stops a long queue

**Severity:** Medium · **Type:** Poor implementation / robustness ·
**Where:** `playback/ExoPlayerEngineListener.kt:151-162` (`onPlayerError`),
`data/repository/SongRepositoryImpl.kt:190-197` (`getSongsByIdsForReconciliation` does not filter `isMissing`),
`playback/ExoPlayerEngineReconciliation.kt:43-84`

**What happens.** When a queued file is missing, corrupt or unreadable, ExoPlayer raises an error.
`onPlayerError` deliberately only logs it and emits a snackbar event; the player then sits in
`STATE_IDLE` on that song. Nothing advances to the next song. So in a long background session (screen off, headphones,
car) a single bad file silently ends playback until the user presses Next. The snackbar is only
seen if the app is in the foreground.

Songs that become *missing* mid-session (a verify pass, an SD card removed, a file deleted outside
the app) stay in the live queue as well:

* the reconciliation query returns missing rows unfiltered, so the cached `Song` just gets
  `isMissing = true` and keeps its slot;
* only a restart filters them out, because `resolveQueueSlots` filters `isMissing`.

**Suggested fix.** On an error whose cause is the source (file not found or IO errors), skip to
`nextLiveIndex()` automatically, and stop after N consecutive failures to avoid a spin. When
reconciliation finds `isMissing = true`, treat it like a deletion for queue purposes and route
it through `removeDeletedSongInternal`, or at least skip it on arrival.

---

### #14 — On the first launch each day, restoring the saved queue waits for a full backup; any Play Next / Add to Queue in that window permanently replaces the saved queue

**Severity:** Medium · **Type:** Bug (race → queue data loss) ·
**Where:** `MainActivity.kt:255-261`, `MainActivity.kt:341-355`, `playback/PlaybackViewModel.kt:316-340, 384-385`,
`playback/ExoPlayerEngineQueueLoad.kt:122-129`

**What happens.** At startup, `playback.restoreSavedQueue()` runs *after* `backupDailyIfDue(...)`
in the same sequential coroutine. On the first launch each day that means a full
`createBackup(BackupTier.Daily)` **plus** `verifyMostRecentBackup()` must finish before the mini
player comes back. During that window:

* `hasActivePlayback()` is false (no current song yet).
* **Play Next** and **Add to Queue** fall back to `playQueue(listOf(song), 0)`. This installs a
  one-song queue and persists it with the unified write, overwriting `queue_items`,
  `queue_baseline_items` and the cursor.
* When restore finally runs, it sees `ctrl.mediaItemCount > 0` and skips ("adopting existing
  running timeline").

The user's saved queue is gone. A song-row tap in that window also replaces it, which is
arguably intended, but Play Next / Add to Queue clearly mean "add to what I had".

**Suggested fix.** Start the queue restore before (or in parallel with) the backup, which only
needs the DB to be open. Or make `playNext`/`addToQueue` wait for the restore when one is pending
rather than treating "no current song yet" as "nothing playing".

---

### #15 — With Repeat All on, Up Next says "Nothing queued after this" on the last song even though playback will continue

**Severity:** Low · **Type:** UX / wrong presentation ·
**Where:** `playback/QueueOrderStore.kt:64-67` (`upcomingFrom`), `playback/ExoPlayerEngineQueueState.kt:200-202`,
`ui/player/PlayerSkeletonQueueContent.kt:225-248`

**What happens.** `upcomingQueue` is defined as "everything after the current slot" and ignores the
repeat mode. With Repeat All, playback wraps to the first song, but on the last song Up Next shows
the empty state ("Nothing queued after this"), and near the end it shows only the few remaining
songs. The engine's own `hasNext` *does* honour the wrap, so the Next button is enabled while
the list says nothing is next.

**Suggested fix.** When Repeat All is on, show the wrapped continuation, either as a second section
("Then, from the start") or by appending `live[0 until current]`.

**Resolution (#399).** Up Next still lists only the slots after the current one (moves, the duplicate
checks and Keep rely on that). Where it is empty, the text follows the repeat mode: Repeat All
"Repeat All is on. Playback continues from the start.", Repeat One "Repeating this song.", Repeat
Off "Nothing queued after this" (`emptyUpNextText` in `ui/player/PlayerSkeletonQueueContent.kt`).
See `docs/queue-accepted-behaviours.md` A17.

---

### #16 — Saved repeat and shuffle preferences are dropped when the saved queue can't be restored

**Severity:** Low · **Type:** Bug ·
**Where:** `playback/ExoPlayerEngineSession.kt:83`, `playback/ExoPlayerEngineQueueLoad.kt:94-98`,
`playback/ExoPlayerEngineQueueState.kt:66-77`

**What happens.** On connect, `_repeatMode` is overwritten with the fresh service player's value
(`REPEAT_MODE_OFF`). The persisted repeat and shuffle values are only applied inside
`restoreSavedQueueInternal`, and only when a queue is actually restored. If `loadQueue()` returns
null, the persisted values are never applied:

* no queue rows,
* every saved song is missing (an unmounted card),
* the load threw.

The UI then shows Repeat Off and shuffle off. The next fresh play writes `repeatMode = OFF` and
`shuffleEnabled = false` with the unified write, permanently overwriting the user's preference.

**Suggested fix.** Read the cursor's repeat and shuffle independently of the queue rows and apply
them on connect, even when there is no queue to restore.

---

### #17 — If the song that was playing is missing at restore, the queue restarts from the first slot at 0:00

**Severity:** Low · **Type:** Poor implementation ·
**Where:** `playback/QueuePersistence.kt:301-321` (`toRestoredQueue`)

**What happens.** `resolvedIndex = slots.indexOfFirst { it.slotId == currentSlotId }`. If the
current song was filtered out (flagged missing, or deleted by cascade), `startIndex = 0` and
`positionMs = 0`. A user who was 300 songs into a long queue is thrown back to song 1. With
shuffle on, `live[0]` is simply whichever song was current when shuffle was enabled.

**Suggested fix.** Resume at the first surviving slot that came *after* the saved slot in the
stored order. That needs the raw stored positions, which `getQueueAndCursor()` already returns
before filtering.

---

### #18 — `isQueueEnded` is only cleared by a playback-state change, so queue edits at the end leave Play Next / Add to Queue in "replace the queue" mode

**Severity:** Low · **Type:** Bug (needs device confirmation of ExoPlayer's ENDED behaviour) ·
**Where:** `playback/ExoPlayerEngineListener.kt:131-140`, `playback/PlaybackViewModel.kt:384-385`

**What happens.** `_isQueueEnded` becomes true at `STATE_ENDED` and is cleared only by a later
`onPlaybackStateChanged(!= ENDED)`. After a natural end, the user can still reshape the queue:
turning shuffle ON re-queues the baseline after the last song (`QueueOrderStore.shuffle`), and
Up Next shows those songs. If the player stays in `STATE_ENDED` until a seek, `hasActivePlayback()`
keeps returning false. The next **Play Next** or **Add to Queue** then *replaces* that visible
queue with a one-song queue (`PlaybackViewModel.kt:317-319, 332-334`) instead of adding to it.

**Suggested fix.** Derive "ended" from the order store and the cursor (`nextLiveIndex() == null` and
the player is at the end) rather than latching it, or clear the flag in `onQueueStructureChanged` and
`setShuffleEnabledInternal` whenever `nextLiveIndex()` becomes non-null.

---

### #19 — Restore reads the active queue and the baseline in two separate transactions, and baseline read errors are swallowed

**Severity:** Low · **Type:** Robustness ·
**Where:** `playback/ExoPlayerEngineQueueLoad.kt:94` and `:109`, `playback/QueuePersistence.kt:329-334`

**What happens.** The live-reconnect adoption path uses `loadQueueWithBaseline()`, which reads both
orders and the cursor in one `@Transaction` (`data/db/QueueDao.kt:175-178`). The kdoc explains
why a split read is unsafe. The cold-start restore path still does the split read:

* `persistence.loadQueue()` first,
* artwork building, which suspends,
* then `persistence.loadBaselineQueue()`.

`loadBaselineQueue()` also converts *any* exception into `emptyList()`. A torn read or a failed
second read therefore installs a full `live` with a stale or empty `baseline`, which is exactly the input
that makes shuffle collapse the queue (#7) or turns a shuffle-off into a wipe (#1).

**Suggested fix.** Use `loadQueueWithBaseline()` in `restoreSavedQueueInternal` too. On a baseline
read failure, rebuild baseline from `live` instead of using an empty list.

---

### #20 — Play Next / Add to Queue / Move / Remove write the baseline and the active queue as two separate commits

**Severity:** Low · **Type:** Robustness (contradicts the code's own atomicity rationale) ·
**Where:** `playback/QueueOrderStore.kt:119, 128, 173, 188, 279` (`persistBaseline`), then
`playback/ExoPlayerEngineQueueState.kt:19-26, 53-57` (`onQueueStructureChanged` → `persistQueue`)

**What happens.** For an unshuffled edit, the order store first enqueues `replaceBaselineQueue`.
`onQueueStructureChanged` then enqueues `replaceQueueAndCursor`. These are two Room transactions.
`saveUnifiedQueueState` exists precisely because "separate writes would be independent commits for
one logical change, and a process death between any two of them could leave them mismatched", but it is
only used for fresh plays and shuffle toggles. A kill between the two commits leaves
`baseline ≠ live` on disk. That mismatch then feeds the positional mirroring bug (#21) and the
baseline-trusting shuffle code (#7).

**Suggested fix.** Route every structural edit through one write that saves both orders and the
cursor (the DAO already has `replaceQueueBaselineAndCursor`). Let `QueueOrderStore` mark baseline as
dirty instead of persisting it itself.

---

### #21 — Mirrored moves into baseline are positional, fail silently, and a restore that drops missing songs rewrites only the active order

**Severity:** Low · **Type:** Robustness ·
**Where:** `playback/QueueOrderStore.kt:166-174` (`mirrorMoveIntoBaseline`), `playback/ExoPlayerEngineQueueLoad.kt:168-175`

**What happens.**

* `mirrorMoveIntoBaseline` replays the *display indices* of a move relative to the current slot's
  position in baseline. This only works while baseline's tail after the current slot is identical to
  live's tail. On any divergence (#19, #20, or the next bullet) it moves a **different** baseline
  row. If the indices are out of range it just `return`s without logging, so the two orders drift
  further apart. The live move uses slot identity; the mirrored move does not.
* When restore drops missing songs (`droppedCount > 0`), it rewrites `queue_items` but not
  `queue_baseline_items`. If those songs come back later (the card is re-inserted), the next restore
  has them in baseline but not in live, which creates a baseline/live divergence in the unshuffled
  state.

**Suggested fix.** Mirror by slot id: remove the moved slot from baseline and re-insert it after
the baseline slot that now precedes it in `live`. Rewrite both orders in the restore fix-up.

---

### #22 — Large queues pay O(N) database writes per edit and O(N) rehydration per track change

**Severity:** Low · **Type:** Performance ·
**Where:** `data/db/QueueDao.kt:55-72, 118-126` (DELETE-all + INSERT-all), `playback/ExoPlayerEngineQueueState.kt:182-211`,
`playback/PlaybackViewModel.kt:97-100, 384-385`

**What happens.** "Play" on the Songs screen queues the whole library (`SongsViewModel.kt:91`), which
can be thousands of songs.

* Every Play Next, Add to Queue, move or remove deletes and re-inserts **every** row of
  `queue_items`, and of `queue_baseline_items` too when unshuffled. That is up to 2×N rows per edit,
  all serialized on one writer; swiping ten rows away quickly queues 20 full-table rewrites.
* Every track transition calls `refreshUpcomingQueue()`, which allocates N `QueueSlot`s. The UI
  then copies the list again (`toPersistentList()`, `PlayerSkeletonQueueContent.kt:149`).
* `PlaybackViewModel.queue` eagerly mirrors the *full* hydrated order, and its only synchronous use
  is `queue.value.isNotEmpty()` inside `hasActivePlayback()`.

None of this is visible on small queues, but it scales linearly with library size on low-end
devices.

**Suggested fix.** Use incremental row updates (the slot id is already the primary key; positions can be
sparse) or debounce structural writes. Expose a cheap `queueSize` / `hasQueue` flow instead of the full
hydrated `queue`.

---

### #23 — Only Play Next and Add to Queue check the session connection; other edits and the repeat button act on a dropped controller without telling the user

**Severity:** Low · **Type:** Inconsistency ·
**Where:** `playback/ExoPlayerEngine.kt:340-363`, `playback/ExoPlayerEngineQueueOps.kt:130-187`

**What happens.** `playNext` and `addToQueue` refuse with `ServiceUnavailable` while a mid-session
reconnect is in progress, because "an insert into it would update the order store while media3
ignores the timeline edit". `moveQueueItem`, `removeQueueItem`, `seekToQueueItem`,
`setShuffleEnabled` and `setRepeatMode` only check `controller != null`, so during a reconnect
they behave like this:

* **move, remove, shuffle:** they mutate the store, and their timeline edits go to the dead
  controller. The reconnect's `maintainWindow(force = true)` happens to repair the window afterwards.
* **seek, repeat:** they are lost with no feedback, and the repeat button appears to do nothing.

**Suggested fix.** Use the same `connection.isConnected` gate everywhere and surface `ServiceUnavailable`.

---

### #24 — Latent desync states where the cursor is not in the order store leave navigation wedged

**Severity:** Low · **Type:** Robustness (latent) ·
**Where:** `playback/ExoPlayerEngine.kt:376-384` (`stop`), `playback/ExoPlayerEngineSession.kt:97-119`,
`playback/ExoPlayerEngineQueueState.kt:96-110`

**What happens.** Several features silently switch off when `_currentSlotId` is null or not in the
order store:

* `maintainWindow` bails,
* `nextLiveIndex()` is null, so Next is disabled and `next()` does nothing,
* Up Next is empty,
* system transport falls back to native window-only navigation.

Two ways to get there:

* `stop()` publishes a null cursor but leaves the ExoPlayer timeline loaded. If anything later
  resumes the player (a system play command from the notification, Bluetooth or the lock screen), audio
  plays with no current song in the app. No `onMediaItemTransition` fires, because the item never
  changed. Today the only in-app caller (the deletion path) also clears the timeline, so this is
  latent, but `PlaybackViewModel.stop()` is public.
* `adoptRunningTimeline` adopts the *persisted* order, which filters missing songs. If the item the
  service is still playing belongs to a song that has since been flagged missing, the cursor names
  a slot the store doesn't have.

**Suggested fix.** When the current slot is not in the store, adopt the timeline's surrounding items
into the store, or rebuild around the nearest known slot. Make `stop()` also clear the timeline, or
remove it from the public API.

---

### #25 — External controllers only ever see the ~51-item window as "the queue"

**Severity:** Low · **Type:** Limitation (informational) ·
**Where:** `playback/ExoPlayerEngineWindow.kt:16-29`, `playback/SynthXPlaybackService.kt:83-107`

**What happens.** The session publishes the ExoPlayer timeline. External queue views (Android
Auto's queue, Bluetooth "now playing list", Wear) therefore show at most 10 previous and 40 upcoming
songs, not the real queue. Choosing an item there works (the engine adopts the slot), but the
list is truncated and shifts as the window refills. This is the accepted cost of windowing; it is
listed so that it is a conscious decision.
