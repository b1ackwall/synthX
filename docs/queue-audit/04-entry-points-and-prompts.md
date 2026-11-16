# 04 — Entry points: song taps, Keep/Clear and Replace prompts, Play Next / Add to Queue, detail-page buttons

Issues **#38 – #47**. Paths are relative to `app/src/main/java/com/v1per/synthx/` unless they
start with `app/`. See [README.md](README.md) for the severity legend and the full index.

## How a queue gets built or replaced

| Action | Code path | Shuffle flag afterwards |
|---|---|---|
| Song-row tap (Songs, Library recent, Search, Starred, Album, Artist, Playlist) | `PlaybackViewModel.requestPlayQueue` → prompt if customised → `playQueue(list, index, shuffle = null)` | unchanged (a new queue is shuffled with the tapped song first if shuffle is on) |
| Detail page **Play** | `playAll` → `playAllRequest(shuffle = false)` → Replace prompt if customised | **off** |
| Detail page **Shuffle** | `shuffleAll` → `playAllRequest(shuffle = true)` (random start) → Replace prompt if customised | **on** |
| Song actions **Play Next** / **Add to Queue** | `playNext` / `addToQueue` → dedup confirm → engine insert, or `playQueue(listOf(song))` when nothing is active | unchanged |
| Prompt **Keep** | `keepQueueAndPlayNext` → `playNext` + `next()` | unchanged |
| Prompt **Clear** / **Replace** | `playQueue(...)` | see the rows above |

A queue counts as "customised" (and so triggers the prompts) once `playNext`, `addToQueue`,
`moveQueueItem` or `removeQueueItem` has run since the last fresh play
(`playback/ExoPlayerEngineQueueState.kt:19-26`).

---

### #38 — "Keep" does not start playback when the player was paused, even though the dialog promises it will

**Severity:** Medium · **Type:** Bug ·
**Where:** `playback/PlaybackViewModel.kt:258-268`, `playback/ExoPlayerEngine.kt:293-296`, `playback/ExoPlayerEngineWindow.kt:266-280`,
string `queue_prompt_message` ("Keep plays this song now and continues with the songs you queued.")

**What happens.** `keepQueueAndPlayNext` calls `controller.playNext(song)` and then
`controller.next()`. `next()` is `seekToLiveIndex(target, play = false)`, which seeks without
calling `play()` (and `rebuildWindowAround(..., play = false)` for an unloaded target). The prompt
only appears when `hasActivePlayback()` is true, and that includes being **paused** mid-queue. So:
pause, tap a song in any list, choose **Keep**. The song becomes current but stays paused. The
non-customised path (`playQueue`) always calls `ctrl.play()`, so the same tap behaves differently
depending on whether the queue had been edited.

**Suggested fix.** After the insert, call `seekToQueueItem(newSlotId)`, which uses `play = true`, or
call `resume()`. That needs `playNext` to return the new slot id.

---

### #39 — "Keep" silently does nothing for a song that is already playing or anywhere in Up Next, and says "Already playing next"

**Severity:** Medium · **Type:** Bug ·
**Where:** `playback/PlaybackViewModel.kt:264, 343-344`, `ui/MainScaffold.kt:369-375`

**What happens.** `keepQueueAndPlayNext` returns `false` when `isAlreadyQueued(song.id)`, which is
true if the song is the current song **or appears anywhere** in Up Next, even 400 rows away. The
scaffold then shows "Already playing next" and nothing else happens.

* For a song deep in Up Next, the message is false: it is not "next", and the user's tap
  ("play this song now") is ignored.
* For the current song, the message is also wrong; it is playing, not "next".

This is the coarse "blanket dedup" the kdoc describes, but it contradicts what the dialog
promises.

**Suggested fix.** If the song is already in Up Next, jump to that occurrence (`seekToQueueItem`). If it
is the current song, just resume it. Keep the "already next" message only for the case where it
really is next.

---

### #40 — The customised-queue protection (the Keep/Clear and Replace prompts) is lost on every restart

**Severity:** Medium · **Type:** Wrong implementation (data-loss UX) ·
**Where:** `playback/ExoPlayerEngine.kt:112`, `playback/PlaybackEngine.kt:93-99`, `playback/ExoPlayerEngineQueueLoad.kt:164`,
`playback/ExoPlayerEngineSession.kt:97-109`

**What happens.** `isQueueCustomized` lives only in memory. It goes back to `false` in three cases:

* on cold start (`restoreSavedQueueInternal` sets it false explicitly),
* when the Activity is finished and recreated while the service is still alive (a new engine starts
  with `false`; the adoption path doesn't restore it),
* after any process death.

The hand-built queue itself *is* restored, but its protection is not. The user spends time
building a queue, closes the app, and the next day taps a song: the queue is replaced with no prompt.
The same happens for the Play/Shuffle buttons' Replace prompt.

**Suggested fix.** Persist the flag next to shuffle and repeat in `playback_cursor` (it is a schema
change, so follow the version-bump checklist in CLAUDE.md), or derive it from persisted state,
for example "the active order differs from the context it was created from".

---

### #41 — "Keep" after the queue has ended plays only the tapped song and drops the list it was tapped from

**Severity:** Low · **Type:** Inconsistency ·
**Where:** `playback/PlaybackViewModel.kt:259-263`

**What happens.** If the queue ends between the prompt appearing and the user answering, Keep falls back
to `playQueue(listOf(song), 0)`: a one-song queue. "Clear", in the same situation, would have
played the whole context list (`prompt.contextSongs` from `prompt.startIndex`). With nothing left to
keep, both answers should behave like an ordinary tap on the context.

**Suggested fix.** Pass the prompt's `contextSongs` and `startIndex` into the fallback.

---

### #42 — Queue prompts silently disappear on a configuration change

**Severity:** Low · **Type:** Bug (UI state) ·
**Where:** `ui/MainScaffold.kt:178-184`

**What happens.** `queuePrompt` and `queueReplacePrompt` are plain `remember` state, filled from
one-shot `SharedFlow`s with no replay. Rotation, a dark-mode switch, a locale or font-scale change,
or split-screen resizing recreates the Activity and drops the dialog. The tap that raised it is lost,
and the user has to tap again without knowing why nothing happened.

**Suggested fix.** Hold the pending prompt in `PlaybackViewModel` as a `StateFlow` (it already outlives
configuration changes) and clear it on answer or dismissal.

---

### #43 — Play Next / Add to Queue / Keep can race an in-flight queue load: edits land on the old queue and are wiped, or the fallback cancels the album the user just started

**Severity:** Low · **Type:** Race ·
**Where:** `playback/PlaybackViewModel.kt:184-189, 316-340`, `playback/ExoPlayerEngineQueueLoad.kt:15-81`

**What happens.** `playQueue` launches a job that suspends: it builds start-song artwork on IO/Default
and waits for the controller. In that window:

* `hasActivePlayback()` still describes the *old* queue. A Play Next or Add to Queue issued now is
  inserted into the old queue, and the new queue's install then clears it (`songsBySlotId.clear()`
  plus `captureFresh`), with a "Playing next" snackbar already shown.
* If there was no active playback, the Play Next / Add to Queue fallback calls
  `playQueue(listOf(song), 0)`, which **cancels** the in-flight job (`playQueueJob?.cancel()`). The
  album or playlist the user tapped a moment ago never starts; only the single song plays.

**Suggested fix.** Serialize queue commands through one engine-side queue, so that an insert issued after a play
request applies to the new queue. Or make `playNext`/`addToQueue` wait for `playQueueJob`.

---

### #44 — The duplicate checks for Play Next, Add to Queue and Keep read a lazily-shared flow (`WhileSubscribed`), which is the lazy-flow trap CLAUDE.md warns about

**Severity:** Low · **Type:** Fragility ·
**Where:** `playback/PlaybackViewModel.kt:107-108, 336, 343-350`, `ui/SynthXBottomNav.kt:151-154`

**What happens.** `upcomingQueue` is `stateIn(WhileSubscribed(5_000))`. `isNextInQueue`,
`isLastInQueue`, `isAlreadyQueued` and `addToQueue`'s `upcomingQueue.value.isNotEmpty()` all read
`.value` synchronously. They are correct today only because `BottomChromeOverlay` happens to collect
`upcomingQueue` while a song is current. CLAUDE.md flags exactly this ("reading `.value` of a
`WhileSubscribed` … StateFlow that nobody is collecting returns the stale initial value"). If the
player UI is ever moved, gated differently, or not composed (restore-maintenance surface, a future
mini-player variant), the duplicate checks silently start reading a stale list.

**Suggested fix.** Add an `Eagerly` twin like `activeSongId`, or ask the engine directly at call time.

---

### #45 — Play and Shuffle on an Artist page use alphabetical-by-title order

**Severity:** Low · **Type:** Design note ·
**Where:** `data/db/SongDao.kt:32-41` (`ORDER BY songs.title COLLATE NOCASE ASC`), `ui/screens/artistdetail/ArtistDetailScreen.kt:52-54`

**What happens.** An artist's songs are listed and queued A→Z by title. **Play** therefore plays the
artist's catalogue alphabetically. That also becomes the `baseline` order that shuffle OFF returns
to, so with #2 the "remaining" songs after shuffle OFF are the alphabetically-later titles. Most
players order an artist by album (year), then track number.

---

### #46 — "Add to Queue" appends after the entire remaining context, not after other user-queued songs

**Severity:** Low · **Type:** Design note / UX ·
**Where:** `playback/QueueOrderStore.kt:123-130`, `playback/ExoPlayerEngineQueueOps.kt:95-110`

**What happens.** There is no separate "user queue" section. Add to Queue appends to the very end of
`live`, after every remaining song of the album, playlist or (from the Songs screen) the **whole
library**. Queueing a song while listening to a 2,000-song "all songs" context puts it 2,000 songs
away, so in practice it never plays. Play Next is LIFO at the cursor, which matches Apple Music.
Under shuffle, added songs then move again on shuffle OFF (#4). The behaviour matches "Play Last",
but the label says "Add to queue". Users coming from Spotify expect it to come before the
context's remaining songs.

**Suggested fix.** Either rename it to "Play Last", or add a "user queue" segment that sits between the
current song and the remaining context, and that shuffle toggles never reorder (this also fixes #4).

---

### #47 — Up Next has no history, no "Clear", and no count or duration

**Severity:** Low · **Type:** Missing capability ·
**Where:** `ui/player/PlayerSkeletonQueueContent.kt`, `playback/PlaybackEngine.kt` (no history or clear API)

**What happens.** Queue mode shows only songs *after* the current one. Some common queue operations are
not available:

* **History:** there is no way to see or jump back to earlier songs beyond pressing Previous, and
  Previous is limited to the 10-item loaded window (`WINDOW_BACK`).
* **Clear Up Next:** removing upcoming songs means swiping them away one by one, each a permanent
  edit (#28).
* **Size:** there is no count or remaining duration.

These matter more because shuffle OFF silently deletes songs (#2). A user cannot see what was lost.
