# Queue engine: external controllers, playback errors, connection and adoption

This document describes how the playback engine behaves at its edges: commands from other
controllers (car, Bluetooth, assistants, the notification), songs that cannot be played, a dropped
session connection, an engine adopting a session that is already running, and the in-app shuffle and
repeat buttons. Paths are relative to `app/src/main/java/com/v1per/synthx/`. The shuffle rules
themselves are in `docs/queue-shuffle-design.md`, persistence in `docs/queue-persistence.md`, and the
song-tap and queue-action rules in `docs/queue-entry-points.md`. Known behaviours that are accepted or
deferred are in `docs/queue-accepted-behaviours.md`.

## 1. Purpose

The engine (`playback/ExoPlayerEngine*.kt`) owns the full queue in its order store; the playback
service's ExoPlayer only holds a window of about 51 slots. Every rule here keeps the order store the
single authority: no command from outside, no error and no reconnect lets ExoPlayer's own order,
shuffle or end-of-window decide what plays.

## 2. External controllers

### 2.1 What reaches the player

`SynthXPlaybackService` builds its session around `SystemNavigationPlayer`, a wrapper in front of the
raw ExoPlayer. The session accepts every controller with the default player commands, so a controller
can send every command the wrapper reports as available.

| Command | Handled by | Behaviour |
|---|---|---|
| Next, Previous (`SEEK_TO_NEXT`, `SEEK_TO_NEXT_MEDIA_ITEM`, `SEEK_TO_PREVIOUS`, `SEEK_TO_PREVIOUS_MEDIA_ITEM`) | the wrapper, then the engine | The app's own Next and Previous rules over the full queue; availability follows the engine (`navigationCommandEdit`). Native window behaviour when no engine is ready. |
| Shuffle (`SET_SHUFFLE_MODE`) | the wrapper, then the engine | Routed to the app's shuffle (2.2). Never reaches ExoPlayer. Offered only while an engine is ready. |
| Repeat (`SET_REPEAT_MODE`) | raw ExoPlayer | The engine follows the player's repeat mode (`onRepeatModeChanged`), so a controller's repeat change is the app's repeat change. |
| Seek to a queue item | raw ExoPlayer | The engine adopts the slot ExoPlayer moves to (`onMediaItemTransition`). |
| Play, pause, stop, prepare, seeks within the song, speed, volume, media items, tracks, metadata | raw ExoPlayer | Unchanged. A controller's stop leaves the engine's cursor where it was. |

### 2.2 Shuffle routing

- **ON** behaves exactly like the in-app shuffle ON (`docs/queue-shuffle-design.md` section 4.1,
  including the Repeat All variant).
- **OFF** behaves exactly like the in-app shuffle OFF (section 4.2), except for the refuse rule below.
- **Refuse rule**: an OFF while the queue was reordered while shuffled (the reorder flag) is refused.
  Shuffle stays on and nothing is discarded. No dialog can ask on a car screen or a headset.
- A request for the state that is already in place is a no-op.
- After every shuffle command, applied, refused or a no-op, the wrapper publishes the app's flag to
  every controller. A controller that showed its own request in advance therefore ends up showing the
  app's state.

The decision is `externalShuffleAction` (`playback/SystemNavigationRules.kt`); the engine applies it
in `setShuffleFromSystemInternal` (`playback/ExoPlayerEngineNavigation.kt`). An applied command runs
the in-app toggle to completion before the wrapper publishes.

### 2.3 What controllers see

- `getShuffleModeEnabled` reports the app's shuffle flag, never ExoPlayer's.
- Every change of the app's flag, from the app or from a controller, reaches every controller as
  `onShuffleModeEnabledChanged` through the wrapper's listener dispatch. The session sends its full
  player state to every controller on each such event, so an unchanged value still corrects a
  controller's display.
- `SET_SHUFFLE_MODE` is offered while an engine is ready (the same readiness the skip commands use:
  a live controller and a current slot in the order store) and withdrawn otherwise, so a car can grey
  its shuffle button.

### 2.4 No engine

With no engine attached or ready (the app's view model is gone, or the queue is still loading), the
shuffle command is withdrawn. A command that still arrives is ignored, and the reported flag is off.

### 2.5 Safety net

ExoPlayer's own shuffle stays off. The wrapper switches it back off whenever it sees it on: when the
wrapper is created (with the service), in every listener it holds on the raw player (the session's
listeners go through it), and whenever the bridge reports a change (an engine attaching, any
navigation refresh). It only acts while the flag is on, so it cannot loop. ExoPlayer's random order
never drives playback.

### 2.6 Checked by hand only

How a car head unit, a Bluetooth (AVRCP) device, Android Auto and an assistant map their shuffle
buttons or voice commands to this command can only be confirmed on the real devices. The tests drive
the session through a media3 controller, which reaches the same wrapper methods.

## 3. Playback errors

### 3.1 Which errors skip

A failure whose cause is the song's own file skips to the next slot (`isSourceFailure`,
`playback/ExoPlayerEngineErrors.kt`, codes from media3 1.11.1):

| Code | Name | Why it skips |
|---|---|---|
| 2000 | `ERROR_CODE_IO_UNSPECIFIED` | Any other read failure of the file. |
| 2005 | `ERROR_CODE_IO_FILE_NOT_FOUND` | The file is gone (deleted, moved, card removed). |
| 2006 | `ERROR_CODE_IO_NO_PERMISSION` | The file cannot be read. |
| 2008 | `ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE` | The file is shorter than its own data says. |
| 3001 | `ERROR_CODE_PARSING_CONTAINER_MALFORMED` | The file is damaged. |
| 3003 | `ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED` | No extractor understands the file. |

Everything else stays on the song: unspecified (1000), remote (1001), behind live window (1002),
timeout (1003), failed runtime check (1004), the network and HTTP codes (2001 to 2004, 2007), the
manifest codes (3002, 3004), decoder (4xxx), audio output (5xxx) and DRM (6xxx). They are not about a
local file, or they can pass.

### 3.2 Rules

| Repeat | A next slot exists | Otherwise |
|---|---|---|
| Off | skip to it | stay on the bad song (idle) |
| One | skip forward in queue order, wrapping from the last slot to the first, as Next does | stay |
| All | skip in queue order, with the wrap | stay |

"Next slot" is the engine's own `nextLiveIndex()` over the order store, not the loaded window. The
skip goes through `seekToLiveIndex`, which revives the idle player (`applyIdleRecovery`) and keeps
its play-when-ready: a user who was playing hears the next song, a paused user is moved to it and
stays paused. The skip is the engine's own move and is never refused for lack of a connection.

A skip happens only while the player is still idle on the failure. A failure reported after the user
already moved playback on (a Next tap that landed first) does not skip past the song the user chose.

### 3.3 Bursts and the cap

- A burst is a run of consecutive failures. `PLAYBACK_FAILURE_CAP` is 5.
- Under Repeat Off a burst stays after 5 failures. Under Repeat All and Repeat One, where skips wrap,
  it also stays once every slot was tried: after min(5, queue length) failures. A queue of bad files is
  tried once and never spins.
- The counter resets when a song reaches READY or starts playing, and on every user action: Next,
  Previous (in-app or from a controller), a tap on an Up Next row, a fresh play.
- The playback-failed message (the existing one) shows once per burst, for the first failed song; the
  songs a burst skips add no message. A new burst starts after a song plays.

### 3.4 What stays

- A bad song keeps its slot: no removal, no reconciliation change, no new styling. A missing song is
  skipped when it comes up and fails; a remounted card brings it back.
- Repeat All with one bad song skips it quietly on every lap: the counter resets each time a good song
  plays, and each lap's failure is the first of a new burst, so it shows the message again.
- When a burst stays on a bad song, a system Play (notification, Bluetooth, lock screen) retries the
  same file. It fails again within the same burst, so no new message shows.
- The message reaches only a screen that is collecting it; a failure in the background shows nothing.

## 4. Connection policy

During a mid-session reconnect the engine still holds the dropped controller, which ignores every
command. `connection.isConnected` is what tells the two apart.

| Entry point | Policy | Reason |
|---|---|---|
| `playQueue`, `restoreSavedQueue`, the saved-modes adoption | waits for the connection (`awaitController`), re-arming a round that gave up | A load must not be dropped while a retry is seconds away. |
| `playNext`, `addToQueue` | refused, `ServiceUnavailable` | The store would change while the timeline edit is lost. |
| `seekTo` (seek bar), `seekToQueueItem` (a tap on an Up Next row), `setRepeatMode` | refused, `ServiceUnavailable` | The change would be lost without a word. The seek bar calls `seekTo` once per gesture (on release, or once per accessibility adjustment), so one gesture shows one message. |
| `moveQueueItem`, `removeQueueItem`, `setShuffleEnabled` | not gated: self-repairing | They edit the order store; the reconnect's `maintainWindow(force = true)` rebuilds the window to match. |
| `next`, `previous`, `pause`, `resume`, `persistPlaybackState`, external shuffle | not gated | On a dropped controller they do nothing; out of scope here. |
| Position and duration ticks, cursor checkpoints, restore and adoption, window rebuilds, `seekToLiveIndex`, the auto-skip, deletion, remap and reconciliation | internal, never gated | The engine's own work, which must not fail on a message path. |

A controller's repeat command goes to the raw player and is not subject to this gate.

## 5. Adoption and stop

### 5.1 Adopting a running session

When an engine connects to a session that already holds a timeline (an earlier engine is gone, the
service and its timeline remain), it adopts the saved order (`adoptRunningTimeline`). The slot the
session is playing stays the current slot:

- **The playing slot survives**: as before, nothing about playback changes.
- **Its song is flagged missing** (its queue row and song row both remain): the saved read leaves the
  slot out of the surviving order and lists it in the snapshot's `missingSlots`.
  `withPlayingSlotAdopted` (`playback/QueueAdoption.kt`) puts it back among the survivors at its
  stored position, from its saved row, or first when no position is stored. Playback is never paused,
  restarted or reloaded; the mini player, Next and Up Next work, and the next save names the slot.
  Other missing slots stay dropped, as a cold restore drops them.
  - Shuffle off: the slot is an ordinary slot of the live order and the baseline.
  - Shuffle on: the saved baseline lacks the slot, so the restore repair rebuilds the baseline from the
    live order (`docs/queue-shuffle-design.md` section 8, a baseline lacking an untagged current slot).
    Shuffle OFF afterwards keeps the shuffled order, and the adopted slot keeps its place.
- **Its song row is gone** (its queue rows went with it): nothing can rebuild the slot. The timeline
  item carries no song id, and a queue row needs an existing song row. The file is gone too, so the item
  cannot play. The cursor moves to the slot a cold restore starts on (`resolveRestoreStart` from the
  saved `current_queue_position`), the window is rebuilt there at 0 ms, and it plays only if the session
  was playing; a paused session stays paused. This is the one case where the playing item is replaced:
  its song and file no longer exist. Every playable item is never removed or re-added.
- **No slot survives**: nothing is adopted and the app has no current song.

In the common case the session is not playing at adoption: when the last owning controller
disconnects, the service stops the player and keeps its timeline.

### 5.2 Stop

The engine's `stop()` is internal; the view model and controller have no stop. Its callers:

| Caller | Outcome |
|---|---|
| Library deletion with nothing left to play (`removeDeletedSongInternal`) | Clears the timeline right after; unchanged. |
| The restore's release of every engine (`releaseAllForRestore`) | Clears the timeline after `stop()`, so no loaded timeline remains behind a null cursor for a system Play to resume. |

## 6. Shuffle and repeat buttons

- The Up Next shuffle and repeat buttons call `PlaybackViewModel.toggleShuffle` and
  `PlaybackViewModel.cycleRepeat`. The screen only says "toggle" or "cycle"; it captures no value.
- `toggleShuffle` reads the engine's shuffle flag when it runs (the engine updates it synchronously in
  the toggle) and asks for the opposite. OFF goes through the guarded path: with a reordered shuffled
  queue it raises the shuffle-off question; while that question is open another tap does nothing and
  does not ask again. ON never asks.
- `cycleRepeat` steps Off, All, One, Off from `PlaybackEngine.currentRepeatMode`, which the engine
  answers from the controller's own repeat mode. A set updates that at once; the engine's repeat flow
  follows only when media3 calls back, which comes later when the set happens inside another callback.
- Two taps that land before the screen redraws are therefore two toggles or two steps.
- The toggle runs to completion on `Main.immediate` with no suspension point; `launchShuffleToggle` is
  shared by `setShuffleEnabled` and `confirmShuffleOff`.

## 7. The queue-ended flag

A diagnostic test (`QueueEndedFlagInstrumentedTest`) ran on unchanged production: after a natural end,
Repeat All, and Repeat All then Shuffle ON, give the ended queue a next slot, and media3 keeps the player
in `STATE_ENDED`. The flag stayed set, so Play Next and Add to Queue would have replaced a queue that
has a next song.

The engine re-reads the flag (`refreshQueueEnded`) after a repeat change, a shuffle toggle and every
queue edit, only while the player is still ended: it clears the flag while a next slot exists and sets
it again when none does. A plain ended queue still reads as ended (Play Next and Add to Queue start a
fresh one-song queue). The player is not moved out of the ended state, and nothing else counts Repeat
All at the end as active playback.

## 8. Product decisions and implementation choices

Decided by the maintainer:

- External shuffle goes to the app's shuffle; OFF is refused while the shuffled queue was reordered;
  controllers see the app's flag; the command is offered only with a ready engine; ExoPlayer's own
  shuffle is kept off.
- Unplayable files are skipped (source errors only), with a cap of 5, the repeat rules of 3.2, one
  message per burst, and bad songs keeping their slots.
- The seek bar, Up Next taps and repeat are refused while reconnecting, with the existing message;
  move, remove and shuffle are not.
- Adoption keeps the playing slot (missing-flagged) or moves to the cold-restore start (row gone).
- The public stop is removed; the restore's release clears the timeline.
- Shuffle and repeat buttons toggle from the engine's current value.
- The ended flag: fixed only because the diagnostic failed, with the narrow re-read of section 7.

Implementation choices:

- The skip codes listed in 3.1.
- Under Repeat Off the burst limit is the cap alone; under Repeat All and One it is min(cap, queue length).
- A failure is skipped only while the player is still idle on it.
- The app's shuffle flag is published by calling the session listeners' `onShuffleModeEnabledChanged`
  from the wrapper; a change caused by a controller's command is published once, at its end.
- The native-shuffle guard lives in the wrapper (creation, its raw-player listeners, bridge changes).
- The ended flag is also set again while the player stays ended and the next slot goes away, so a plain
  ended queue keeps its rule.
- On a song-row-gone adoption, a stopped session is paused before the rebuild so it does not start.

## 9. Out of scope

The Up Next scroll-to-top flag and the buttons' accessibility state; Up Next gestures and styling; the
customised flag after Clear or Replace; song taps during the launch restore; a rescan remap with no
engine running; reshuffling per Repeat All loop; where Add to Queue places a song; the window size;
performance; shuffle, persistence and entry-point semantics; the message being dropped when nothing
collects errors; Next, Previous, pause and resume during a reconnect.

## 10. Traceability

JVM tests are in `app/src/test`, device tests in `app/src/androidTest`, both under
`com.v1per.synthx.playback` unless marked `ui`.

| Decision | Tests |
|---|---|
| External shuffle routing, reported flag, publishing, refuse, no-op (#389) | `SystemNavigationPlayerTest` (shuffle cases), `ExternalShuffleRuleTest`; device `ExternalShuffleInstrumentedTest` |
| Shuffle offered only with a ready engine; ignored without one (#389) | device `SystemNavigationPlayerInstrumentedTest.shuffleIsOfferedOnlyWhileAnEngineIsReady`, `ExternalShuffleInstrumentedTest.withNoEngineTheShuffleCommandIsNotOfferedAndDoesNothing` |
| Native shuffle safety net (#389) | `SystemNavigationPlayerTest` (native cases); device `NativeShuffleGuardInstrumentedTest` |
| Skip decision, codes, cap, idle guard (#397) | `PlaybackFailurePolicyTest` |
| Auto-skip on the real engine: missing, corrupt, unreadable; paused; Repeat Off last; Repeat One wrap; Repeat All once round; cap; counter resets; one message per burst (#397) | device `PlaybackAutoSkipInstrumentedTest`, `PlaybackRecoveryInstrumentedTest` |
| Connection gate scope (#407) | device `ConnectionGateInstrumentedTest` |
| Adoption: missing-flagged slot, shuffle off and on; row gone, playing and paused; nothing survives (#408) | `QueueAdoptionTest`; device `QueueAdoptMissingSongInstrumentedTest`, `QueueAdoptInstrumentedTest` |
| No public stop; the restore's release clears the timeline (#408) | `PlaybackViewModelToggleTest.theViewModelHasNoPublicStop`; device `QueueAdoptInstrumentedTest.releasingEveryEngineForARestoreClearsTheSessionTimeline` |
| Toggle and cycle from the engine's value; the guarded OFF (#394, #396) | `PlaybackViewModelToggleTest`; device `QueueToggleRealEngineInstrumentedTest`, `ui.QueueModeTogglesShellInstrumentedTest` |
| The ended flag (#402) | device `QueueEndedFlagInstrumentedTest` |
