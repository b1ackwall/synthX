# Queue entry points: song taps, Play / Shuffle, Play Next, Add to Queue and their prompts

How the app starts a queue or adds to one from the library, what it asks before it throws a
hand-built queue away, and how those actions wait for a restore or a queue load that is still
starting. Paths are relative to `app/src/main/java/com/v1per/synthx/`. Shuffle rules are in
`docs/queue-shuffle-design.md`, storage and the launch restore in `docs/queue-persistence.md`, and
known open behaviours in `docs/queue-accepted-behaviours.md`.

Terms:

- **context**: the playable songs of the list the user tapped in (missing songs removed), and
  **start index** the tapped song's position in it.
- **active playback**: a current song, a non-empty queue, and a queue that has not ended. A paused
  queue is active.
- **customised**: the queue was hand-edited (Play Next, Add to Queue, a move, a remove) since its
  last fresh play. The flag is stored (`playback_cursor.queue_customized`) and survives restarts.
- **exactly next**: the first Up Next slot (the slot right after the current one in the live order)
  holds the tapped song.
- **fresh play**: any `PlaybackViewModel.playQueue` call. It replaces the whole queue.

## 1. The entry points

| Action | Entry | What happens |
|---|---|---|
| Song-row tap (Songs, Library recent, Search, Starred, Album, Artist, Playlist) | `requestPlayQueue` | Customised and active: the Keep/Clear prompt. Otherwise a fresh play of the context from the start index; the shuffle flag stays as it is. |
| Page **Play** | `playAll` | Customised and active: the Replace prompt. Otherwise a fresh play from the first playable song, shuffle off. |
| Page **Shuffle** | `shuffleAll` | As Play, with a random start song and shuffle on. The start song is chosen at tap time. |
| Song actions **Play Next** | `playNext` | Active: the song is inserted after the current slot, or the Play Next duplicate confirmation when it is already exactly next. Not active: a fresh one-song queue. |
| Song actions **Add to Queue** | `addToQueue` | Active: the song is appended, or the Add to Queue duplicate confirmation when it is already the last Up Next slot. Not active: a fresh one-song queue. |

Play Next, Add to Queue and Keep wait first when a restore or a queue load is pending (section 5).
Song taps and the page Play and Shuffle buttons wait only for a pending launch restore, and only with
nothing playing; they never wait for a queue load (section 5).

## 2. The Keep/Clear prompt

Title "Keep the songs in your Up Next queue?", message "Keep plays this song now and continues with
the songs you queued. Clear replaces the queue with the list you tapped from.", buttons **Keep** and
**Clear**. Back or a tap outside dismisses it and changes nothing.

### Keep

Keep is decided when it is answered, on the queue as it stands then (not as it stood when the prompt
appeared). With active playback (Q = the queue, c = the current slot):

| Case | Resulting queue | Plays |
|---|---|---|
| The tapped song X is anywhere but exactly next | `… c, X, <the rest of Up Next> …`, X a new slot | the new X |
| X is exactly next | unchanged | the existing next slot |
| X is the current song | `… c(X), X, <the rest> …`, a new X slot after the current one | the new X, from the start; the earlier X stays in history |
| X is also later in Up Next, or in history | as the first row: a new X after c; the other copies stay where they are; nothing between is skipped | the new X |
| Shuffle on | the new X is a Play Next extra (shuffle design, section 3.3) | the new X |

In every row playback then starts, also from a paused player (`next()` followed by `resume()`). The
snackbar says "Playing next". When the engine refuses the insert because it is not connected to the
playback session, nothing moves, the engine shows its own error, and no snackbar appears.

With no active playback (the queue ended while the prompt was open) there is nothing to keep: Keep
plays the context from the start index as a fresh queue, exactly what Clear plays.

While a restore or a queue load is pending, Keep returns at once (the snackbar shows) and decides
after the wait by the same rules, read from the engine's own state. If no queue is there after the
wait, it plays the context as above.

### Clear

A fresh play of the context from the start index, the same call an ordinary tap makes.

## 3. The Replace prompt

Title "Replace your queue?", message "You've customized your Up Next queue. Playing this will replace
it.", buttons **Replace** and **Cancel**. Replace plays the Play or Shuffle request exactly as decided
at tap time (songs, start song, shuffle flag). Cancel, Back and a tap outside change nothing.

## 4. Pending prompts and duplicate confirmations

`PlaybackViewModel` holds the pending prompts: `queuePrompt` (Keep/Clear) and `queueReplacePrompt`
(Replace), both `StateFlow`s held in `QueuePromptCoordinator`. `MainScaffold` shows a dialog while
one is non-null and routes the buttons to `answerKeep`, `answerClear`, `dismissQueuePrompt`,
`answerReplace` and `dismissQueueReplacePrompt`.

- The view model outlives the activity, so a pending prompt is still there after a recreation
  (dark-mode, locale, font-scale, density, split-screen). It does not survive process death.
- Only one prompt is pending at a time: raising either one takes the place of whatever is pending.
- A fresh play closes both, whatever started it.
- Every answer and every dismissal closes the prompt first. A second Keep, Clear or Replace tap on a
  prompt that is already answered finds nothing pending and does nothing.
- The prompts are shown only in the normal shell. While the restore-maintenance or the
  restart-required surface is up nothing is shown; a prompt still pending when the normal shell
  comes back is shown again.

The song-actions menu's two duplicate confirmations ("Already playing next / This song is already up
next in your queue. Play it next again?" and "Already at the end of the queue / This song is already
the last one queued. Add another copy?") are held the same way, in
`PlaybackViewModel.queueDuplicateConfirmations` (`QueueDuplicateConfirmations`). `SongActionsHost`
asks after a `NeedsConfirmation` result. Confirming takes the pending song and retries with
`force = true`, which inserts the copy; the snackbar shows when that returns `Added`. Cancel and Back
dismiss it. They are not closed by a fresh play: a confirmed copy goes into whatever queue is there.

The dialogs and their buttons carry test tags (`QUEUE_PROMPT_*`, `QUEUE_REPLACE_*`,
`PLAY_NEXT_DUPLICATE_*`, `ADD_TO_QUEUE_DUPLICATE_*` in `ui/components`).

## 5. The waits

Two things can make Play Next, Add to Queue and Keep decide on the wrong queue: the launch restore
that has not installed the saved queue yet, and a fresh play whose load is still starting (it
suspends while it builds the start song's artwork and while it waits for the session connection).

- **Restore** (`QueueRestoreGate`): Pending from the restore request until it returns, at most
  `QUEUE_RESTORE_WAIT_TIMEOUT_MS` (15 s). Only an action with no active playback waits for it.
- **Load** (`QueueLoadTracker`): every fresh play goes through it. A new load cancels the one before
  it, as a second tap does; a generation number makes only the latest load count. A load is in
  flight from its start until it returns (installed, refused, failed) or is cancelled. Every action
  waits for a load in flight, whether or not something is playing.
- **One wait** (`QueueActionWait`): an action held for either reason waits until nothing it waits for
  is pending, under one bound of `QUEUE_ACTION_WAIT_TIMEOUT_MS` (15 s) from the moment it is held.
  Held actions run once each, in the order they were taken, on one coroutine. An action whose
  fallback starts a load does not wait for that load; the next held action does, so two Play Next
  taps during a restore that brings nothing back give a queue of both songs.
- **Song taps and Play / Shuffle** (`QueueActionWait.holdFreshPlayWhileRestoring`): held while the
  restore is Pending and nothing is playing, never for a load, so a newer tap still cancels an older
  tap's load (the second tap wins). Nothing is shown while one is held. Only the latest held tap or
  Play / Shuffle runs: holding one, or deciding one at once, drops every one held before it. They join
  the same order as the other held actions, so everything held runs in the order it was taken, each
  against the queue the one before it left: a tap then a Play Next plays the tapped list and then
  inserts into it; a Play Next then a tap inserts into the restored queue (or plays the song alone
  when nothing came back) and then decides the tap, which over that now hand-edited queue raises
  Keep/Clear. After the wait the tap reads the customised flag and active playback from the engine:
  a restored hand-built queue raises Keep/Clear or Replace exactly as it would after the restore;
  otherwise it plays. At the bound it decides on what is there. The Shuffle start song is still
  chosen at tap time.
- While held, Play Next and Add to Queue answer `Added` and Keep answers true at once. After the wait
  they read the engine's own state: Play Next and Add to Queue skip a song already in their target
  slot (no dialog can ask then), Keep follows section 2, and with no queue the fallback runs.
- **Bound reasoning**: a load can wait out a whole round of connection attempts (`ReconnectPolicy`:
  four attempts with 1, 2 and 4 s of backoff, at least about 7 s), and the artwork step reads local
  data in well under a second. 15 s is about twice that, the same as the restore bound.
- **At the bound** the action decides on what is there: it inserts into the queue that is playing,
  or runs its fallback, whose fresh play cancels the stuck load. A load that ends later installs its
  queue as usual and runs nothing again. Nothing is held when nothing is pending.

## 6. The duplicate checks read a live copy of Up Next

`PlaybackViewModel.upcomingQueue` is shared while subscribed (the player's Up Next list collects it).
The duplicate checks of Play Next and Add to Queue, and Keep's "exactly next", read a private
eagerly collected copy (`upcomingNow`) instead, so they never read a stale list. It follows the
engine's `upcomingQueue` flow, which the engine publishes on every change whether anyone collects it
or not, so the copy adds no work in the engine.

## 7. Decisions

Product decisions (the maintainer's):

- Keep always starts playback.
- Keep inserts a new copy of the tapped song after the current slot, also when the song is the current
  one (it restarts, history keeps the earlier copy) or sits later in Up Next; nothing in between is
  skipped. Only a song that is already exactly next plays from its existing slot.
- Keep with nothing left to keep plays the context, like Clear.
- The pending prompts and duplicate confirmations survive recreation, not process death.
- Play Next, Add to Queue and Keep wait for a queue load that is still starting.
- Song taps and Play / Shuffle wait for a pending launch restore with nothing playing, silently; only
  the latest held one runs (#432).

Implementation choices:

- The prompts live in `QueuePromptCoordinator`, owned by `PlaybackViewModel`; one prompt at a time.
- An answer takes the pending prompt (or duplicate song) before acting, so a double tap acts once.
- Keep answered with nothing pending returns null, so the scaffold shows nothing.
- An action waits for the whole load call, which ends after its install and its follow-up cache
  check, not only until the install.
- One bound for the whole wait, counted per held action; the restore keeps its own bound for its
  Pending state.
- An action against active playback waits for a load but never for the restore.

## 8. Known limits

- A Keep whose song is exactly next does not insert, so it does not ask the engine about the session
  connection: during a reconnect it moves and resumes on a controller that ignores both, and the
  snackbar still says "Playing next". Seek-bar and Up Next taps during a reconnect are refused with the
  session-unavailable message instead (`docs/queue-engine.md`, section 4).
- At the wait bound an insert lands in the queue that is playing; the stuck load, if it ends later,
  replaces that queue (section 5).
- The look of the dialogs after a recreation is checked by hand on a device, not by a test.

## 9. Out of scope

The customised flag staying true until a new queue installs (see `docs/queue-accepted-behaviours.md`);
the rescan remap (`docs/queue-persistence.md`, section 2.2); the `isQueueEnded` latch
(#402); where Add to Queue places a song (#430); external controller shuffle (#389); the Up Next UI
(#410 to #418); the performance batch (#406); shuffle and persistence semantics.

## 10. Traceability

JVM tests are in `app/src/test`, device tests in `app/src/androidTest`, both under
`com.v1per.synthx.playback` unless marked `ui`.

| Decision | Tests |
|---|---|
| Keep starts playback (#422) | `PlaybackViewModelKeepRulesTest.aKeepOnAPausedPlayerMovesToTheSongAndThenPlays`; `PlaybackViewModelRestoreWaitTest.theKeepFallbackWaitsThenKeepsTheRestoredQueue`; device `QueueKeepInstrumentedTest.aKeepOnAPausedHandEditedQueueStartsPlayback`, `ui.QueuePromptRecreationInstrumentedTest.theKeepOrClearQuestionSurvivesAndKeepStillPlaysTheSongNext` |
| Keep rules (#423) | `PlaybackViewModelKeepRulesTest` (deep in Up Next, exactly next, current song, copies elsewhere, last song, refused insert, held after a restore: deep, exactly next, current song); `PlaybackViewModelRestoreWaitTest.aKeepOfASongAlreadyInUpNextInsertsACopyAfterTheCurrentSong`; `PlaybackViewModelQueuePromptTest.keepOfTheCurrentSong…`; device `QueueKeepInstrumentedTest` (deep, current song, exactly next, shuffled extra) |
| Keep with nothing to keep plays the context (#425) | `PlaybackViewModelKeepRulesTest.keepAfterTheQueueEndedPlaysTheWholeTappedListLikeClear`, `aHeldKeepWhoseRestoreBringsNoQueuePlaysTheTappedList`, `aKeepDuringALoadThatInstallsNothingPlaysTheTappedList`, `playNextAndAddToQueueFallbacksStayOneSong`; `PlaybackViewModelRestoreWaitTest.aRestoreThatProducesNoQueueRunsTheKeepFallbackAfterIt`; `PlaybackViewModelPendingPromptTest.keepAfterTheQueueEndedWhileThePromptWasOpenPlaysTheTappedList` |
| Pending prompts and duplicate confirmations (#426) | `PlaybackViewModelPendingPromptTest`; `PlaybackViewModelQueuePromptTest`; device `ui.QueuePromptRecreationInstrumentedTest` (all four dialogs), `ui.QueuePromptsInstrumentedTest`, `QueuePersistenceEndToEndInstrumentedTest.aSongTapAfterAColdStartAsksKeepOrClearForACustomisedQueue` |
| The load wait and the single bound (#427) | `PlaybackViewModelLoadWaitTest`; `PlaybackViewModelKeepRulesTest.aKeepDuringALoadAppliesToTheNewQueue`; `PlaybackViewModelRestoreWaitTest`, `PlaybackViewModelRestoreTimeoutTest`; device `QueueLoadWaitInstrumentedTest`, `PlaybackViewModelRestoreWaitInstrumentedTest` |
| Live duplicate checks (#428) | `PlaybackViewModelUpcomingChecksTest` |
| Song taps and Play / Shuffle wait for the restore (#432) | `PlaybackViewModelRestoreTapTest` (prompt after the wait, plays when not customised or nothing saved, bound, only the latest, arrival order with Play Next both ways, no wait for a load, Shuffle start at tap time, saved modes kept); device `PlaybackViewModelRestoreTapInstrumentedTest` |
