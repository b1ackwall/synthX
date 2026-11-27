# Queue: accepted and deferred behaviours

Check here before filing an audit finding about the play queue, shuffle, queue persistence or the Up
Next screen. Each
entry below is either a behaviour the maintainer accepted on purpose (with what pins it and when to
look again) or a known behaviour that is deferred to later work. Paths are relative to
`app/src/main/java/com/v1per/synthx/`. The rules themselves are in `docs/queue-shuffle-design.md`
and `docs/queue-persistence.md`; the Up Next screen's are in `docs/queue-up-next.md`.

## Accepted

### A1. The cold-start restore reads the queue and the baseline separately (#403)

- **What.** `restoreSavedQueueInternal` reads the live rows and the cursor in one transaction
  (`QueuePersistence.loadRestore`) and the baseline in a second read (`loadBaselineQueue`). A failed
  baseline read is treated as an empty baseline.
- **Why accepted.** `repairRestoredQueue` runs on every restored queue and keeps every song:
  - with shuffle off the baseline read is not used (baseline := live);
  - with shuffle on, an empty baseline is rebuilt from the live order without its tagged extras.

  The worst case is a shuffled queue whose original order is lost: shuffle OFF after that restart
  keeps the shuffled order. No realistic writer runs between the two reads at cold start.
- **What pins it.** `QueuePersistenceReadTest.failingReadsRestoreNothingInsteadOfThrowing` (a failed
  baseline read returns an empty list) and `QueueShuffleExamplesTest` E16 (shuffle OFF with an empty
  baseline keeps the current order).
- **Revisit when.** A baseline read fails in practice (the log line "Baseline queue load failed"), or
  the restore moves to the combined read for another reason. Moving it to `loadQueueWithBaseline`
  would make a baseline failure restore nothing at all.

### A2. Mirrored moves and the restore rewrite leave the stored baseline alone (#405, closed as fixed)

- **What.** With shuffle off, a move is mirrored into the baseline by slot id
  (`QueueOrderStore.moveUpcoming` / `mirrorMoveIntoBaseline`). Its two early returns (the moved or the
  preceding slot missing from the baseline) are silent. The restore's dropped-songs rewrite writes the
  live rows and the cursor, never the baseline, so the stored baseline can keep rows of songs that no
  longer resolve.
- **Why accepted.** The early returns cannot be reached while shuffle is off, because the baseline
  then equals the live order: every edit keeps them equal, and the restore repair sets baseline :=
  live. The restore repair ignores baseline rows that are not in the live order, so a song that comes
  back (a remounted card) is never resurrected into the queue.
- **What pins it.** `QueueShuffleRulesTest.aMirroredMoveFollowsTheSlotNotThePositionWhenTheOrdersDiffer`,
  `QueueShufflePropertyTest` (I4: baseline equals live while shuffle is off),
  `QueueRestoreRepairTest.baselineSlotsThatAreNotInTheLiveOrderAreNeverResurrected`,
  `QueueColdStartInstrumentedTest.aDroppedSongRewritesTheSavedRowsOnceFromTheFullOrder` ("restore never
  writes the baseline").
- **Revisit when.** Anything lets the baseline differ from the live order while shuffle is off.

### A3. Edits that change both orders save them as two commits, live first (#404)

- **What.** These edits save the live order (with the cursor) and then the baseline, as two
  transactions through the one queue writer:
  - Play Next, Add to Queue and a move with shuffle off;
  - a remove of a slot that is in the baseline;
  - a library deletion;
  - a rescan remap to a new song id, applied by a live engine.

  `docs/queue-persistence.md` section 2.2 lists what a process death between them leaves.
- **Why accepted.** With the live commit first, the restore repair turns the state between the two
  commits into the queue after the edit, in both shuffle modes, for every such edit but the one in A4.
- **What pins it.** `DeferredBaselineWriterTest`, `QueueEditWriteOrderInstrumentedTest`,
  `QueueEditKillReplayInstrumentedTest`.
- **Revisit when.** An edit path needs both orders in one commit, or a new edit path changes the
  baseline.

### A4. A process death inside a shuffled rescan remap can cost one song its album place (#404)

- **What.** A rescan that finds a file's content changed flags the old song row missing and inserts
  the song under a new id. In the same transaction it moves every saved queue row of the old id,
  live and baseline, to the new id (#433, `docs/queue-persistence.md` section 2.2), so a scan never
  loses a queue slot and no longer depends on a running engine. A live engine then applies the remap
  it publishes (`applyRemap` → `remapSong`): first the live commit, then the baseline commit, both
  rewriting the new id the rows already hold. What is left of this exception is the one case where
  the rows hold the old id again: a full-row queue write from an engine that still held the old id
  landed after the scan's commit (an edit, or a write already queued). If the process then dies
  between the remap's two commits while shuffle is on:
  - the live order holds the new id and the baseline still holds the old, missing one;
  - the restore keeps every song and the live order after the edit;
  - **if the remapped song is not the current one**, it comes back as an Add to Queue extra, so
    shuffle OFF places it after the album instead of in its album place;
  - **if it is the current song**, the baseline is rebuilt from the shuffled live order, so shuffle
    OFF keeps the shuffled order.
- **Why accepted.** Every song is kept and nothing removed comes back. The window is an engine write
  between the scan's commit and the remap, followed by a death between the remap's two commits.
  Without live first, the same death drops the song from the queue.
- **What pins it.** `QueueRemapKillOutcomeTest`, and on a device
  `QueueEditKillReplayInstrumentedTest.aShuffledRemapOfASongThatIsNotPlayingComesBackAsAnExtra` and
  `aShuffledRemapOfThePlayingSongRebuildsTheBaselineFromLive` (both drive the engine's remap on its
  own, as that remaining case does). The scan's repoint: `ScanRepointsSavedQueueInstrumentedTest`.
- **Revisit when.** An engine queue write can carry a song id the database no longer holds for that
  slot, or the remap is applied in one commit.

### A5. A cursor that cannot be read leaves the saved modes unknown (#400)

- **What.** When the saved queue cannot be restored but the cursor row can be read, its repeat mode
  and shuffle flag still come back (`docs/queue-persistence.md` 4.4). When the read itself fails, the
  engine keeps the session's modes, which are Repeat Off and shuffle off on a fresh service. The next
  fresh play then saves those.
- **Why accepted.** Nothing reliable is known about the saved modes in that case.
- **What pins it.** `QueueSavedModesReadFailureInstrumentedTest`,
  `QueuePersistenceRestoreReadTest.nothingSavedAndAFailedReadBothReadAsEmpty`.
- **Revisit when.** Cursor reads are seen failing in practice.

### A6. The first restart after updating to database version 18 (#401, #424)

- **What.** Saved rows from version 17 carry no customised-queue flag and no saved queue position. So,
  once:
  - a hand-built queue reads as untouched, and the next song tap replaces it without asking;
  - a restore whose saved song is gone starts at the first surviving slot.

  Both values are stored by the next save.
- **Why accepted.** The migration is additive and does not guess either value.
- **What pins it.** `Migration17To18Test`, `QueueRestoreStartRoomInstrumentedTest.aCursorSavedBeforeThePositionColumnStartsTheFirstSurvivor`,
  `QueuePersistenceRestoreReadTest.aLegacyCursorReadsAsUntouched`.
- **Revisit when.** Never on its own; it settles after the first save.

### A7. A missing song stays in the queue and is skipped when it comes up (#397)

- **What.** A song whose file cannot be read keeps its slot in the live order, the baseline and the
  saved queue. When it comes up and fails with a file-source error, playback skips to the next slot
  (`docs/queue-engine.md` section 3). A song flagged missing mid-session stays queued the same way;
  only a restart drops it.
- **Why accepted.** Nothing is removed on a guess: a remounted card or a restored file brings the
  song back, and the skip keeps playback going meanwhile.
- **What pins it.** `PlaybackAutoSkipInstrumentedTest.aCorruptFirstSongIsSkippedAndTheNextSongPlays`
  (the bad song keeps its slot), `PlaybackRecoveryInstrumentedTest.aMissingFileSkipsToTheNextSongAndSendsOneErrorMessage`.
- **Revisit when.** Users ask for missing songs to leave the queue, or the queue gains a missing-song style.

### A8. Repeat All skips a bad song again on every lap (#397)

- **What.** Under Repeat All a queue with one bad song skips it on every lap. A good song playing resets
  the failure counter, so each lap's failure starts a new burst and shows the playback-failed message
  again.
- **Why accepted.** The song may come back between laps; the cap only exists to stop a queue of bad
  files from spinning, which a good song in between already rules out.
- **What pins it.** `PlaybackAutoSkipInstrumentedTest.aGoodSongPlayingBetweenTwoBurstsResetsTheCounter`,
  `PlaybackFailurePolicyTest`.
- **Revisit when.** The repeated message is reported as noise.

### A9. A system Play after a burst stopped retries the same file (#397)

- **What.** When a burst stays on a bad song (no next slot, the cap, or a non-file error), a Play from
  the notification, Bluetooth or the lock screen prepares the same song again. A file error fails
  again within the same burst: no skip past the cap and no new message.
- **Why accepted.** Play means "play this"; the user moves on with Next, which starts a new burst.
- **What pins it.** `PlaybackAutoSkipInstrumentedTest.aUserNextAfterABurstStoppedStartsANewBurst`.
- **Revisit when.** A system Play should count as a user action for the burst.

### A10. Move, remove and shuffle during a reconnect rely on the window repair (#407)

- **What.** While the engine reconnects to its session, moving or removing an Up Next row and toggling
  shuffle still edit the order store and save it; their timeline edits go to the dropped controller. The
  seek bar, Up Next taps and repeat are refused instead (`docs/queue-engine.md` section 4).
- **Why accepted.** The reconnect rebuilds the window from the order store (`maintainWindow(force =
  true)`), so nothing is lost and the edit shows up once connected.
- **What pins it.** `ConnectionGateInstrumentedTest.moveRemoveAndShuffleStillEditTheQueueAndTheReconnectRepairsTheWindow`.
- **Revisit when.** A reconnect stops rebuilding the window, or these edits must give feedback.

### A11. Car, Bluetooth and assistant shuffle buttons are checked by hand only (#389)

- **What.** The tests send the shuffle command through a media3 controller to the same wrapper the
  session uses. How a car head unit, a Bluetooth (AVRCP) device, Android Auto or an assistant maps its
  own shuffle button or voice command onto that command is not tested.
- **Why accepted.** Those mappings live in other devices' software and need the real device.
- **What pins it.** `ExternalShuffleInstrumentedTest`, `SystemNavigationPlayerTest`.
- **Revisit when.** A real device shows a shuffle state that disagrees with the app.

### A12. The reorder destination is a position resolved when the move runs (#414)

- **What.** A drag-reorder names the moved row by its slot id and the drop point by a position in Up
  Next (`QueueOrderStore.moveUpcoming`: `currentIndex + 1 + toDisplayIndex`). When the track changes
  between the drop and the move running (a window of about one frame), the position is resolved
  against the new cursor, and the row lands one slot lower than where it was released. The TalkBack
  Move up and Move down actions resolve the same way.
- **Why accepted.** The window is about one frame, the row is one slot off and nothing is lost: the
  moved row is still found by its id, and a move of a row that has gone is refused.
- **What pins it.** `QueueOrderStoreTest.aMoveRunAfterTheCursorAdvancedLandsOneSlotLowerThanReleased`
  (live [c, a, b, d, e], the cursor advances to a, `moveUpcoming(e, 1)` puts e after b).
- **Revisit when.** A drop is reported landing in the wrong place, or the move gains a neighbour-based
  destination ("insert after slot Y") for another reason.

### A13. Swipe-to-remove racing a track change, and a swipe whose row is disposed (#413)

- **What.** Two paths:
  - the first Up Next row's slot can become the current slot between the swipe settling and the
    removal running; the engine refuses to remove the current slot, the row leaves Up Next anyway
    (it is now playing), so the dismissed song plays;
  - a swipe whose row leaves composition while it settles (the queue changed under it) is dropped;
    the slot stays queued.
- **Why accepted.** Nothing is lost and nothing else is removed: the removal is by slot id, and the
  engine never removes the playing slot. Next skips a song that plays after a dismissal.
- **What pins it.** `QueueSwipeRemoveTest` (a swipe removes only its own slot, also when the queue
  advances or changes mid-swipe); the engine's refusal to remove the current slot
  (`removeQueueItemInternal`).
- **Revisit when.** Swipe-to-remove gains an Undo (see Deferred), which needs the same slot record.

### A14. The controls' touch shield over the bottom of Up Next (#417)

- **What.** While the controls show in queue mode, their background shield covers the bottom 300dp of
  the list: touches there reach neither the list nor the sheet, so a drag that starts beside the
  buttons does not scroll, does not hide the controls and does not move the sheet.
- **Why accepted.** Touches leaking through the controls to the list under them were seen before the
  shield. The list's visible height (`docs/queue-up-next.md` section 1) lets the last rows scroll up
  above the controls, which shrinks the practical effect: no row has to be reached under the shield.
- **What pins it.** `PlayerSheetGestureInstrumentedTest.draggingDownOnTheQueueControlsLeavesTheSheetOpen`.
- **Revisit when.** The dead band is reported, or the shield's touch handling is reworked.

### A15. Hidden controls stay hidden across a collapse; Back collapses the sheet in queue mode (#418)

- **What.** Controls hidden by an upward drag in queue mode stay hidden after the sheet is collapsed
  and expanded again; a downward drag on the list shows them. System Back with the sheet expanded
  collapses the sheet, in queue mode too; queue mode stays on.
- **Why accepted.** Intentional: the sheet comes back as the user left it, and Back has one meaning
  for the sheet in both modes.
- **What pins it.** `UpNextShellInstrumentedTest.controlsHiddenByAnUpwardDragStayHiddenAcrossACollapseAndAReExpand`,
  `backWithTheSheetOpenInQueueModeCollapsesTheSheetAndKeepsQueueMode`.
- **Revisit when.** Users get stuck in queue mode without controls, or Back should leave queue mode first.

### A16. A song tap or page Play / Shuffle during the launch restore waits for it, silently (#432)

- **What.** While the saved-queue restore is pending and nothing is playing, `requestPlayQueue` and
  `requestPlayAll` hold their decision until the restore returns or its 15 s bound passes, then read
  the customised flag and active playback from the engine: a restored hand-built queue raises
  Keep/Clear or Replace as it would after the restore; otherwise the tap plays, with the saved
  shuffle and repeat already in place. Nothing is shown while a tap is held. Only the latest held tap
  or Play / Shuffle runs. They wait for the restore only, never for a queue load, and run in the
  order taken together with held Play Next, Add to Queue and Keep
  (`docs/queue-entry-points.md` section 5, `docs/queue-persistence.md` section 5.1).
- **Why accepted.** A hand-built saved queue is never replaced without the question, and the wait is
  bounded. The prompts are pending view-model state, so they appear after the wait with no UI change.
- **What pins it.** `PlaybackViewModelRestoreTapTest`; device: `PlaybackViewModelRestoreTapInstrumentedTest`.
- **Revisit when.** The hold is long enough on a device to read as an ignored tap, or a held tap
  should show something.

### A17. Up Next on the last song under Repeat All or Repeat One says what plays next (#399)

- **What.** Up Next lists only the slots after the current one in the live order, under every repeat
  mode; it never gains the wrapped songs. Where it is empty, its text follows the repeat mode:
  Repeat Off "Nothing queued after this", Repeat All "Repeat All is on. Playback continues from the
  start.", Repeat One "Repeating this song." (`emptyUpNextText`, `ui/player/PlayerSkeletonQueueContent.kt`).
  The text changes as soon as the mode does; the list itself does not depend on the mode.
- **Why accepted.** Moves, the duplicate checks, Add to Queue's "already last", Keep's "exactly next"
  and the controls-visible rule all read Up Next as "the slots after the current one", so appending
  the wrapped songs would change what they act on.
- **What pins it.** `EmptyUpNextTextTest`; device: `ui.player.QueueEmptyUpNextMarkerTest`,
  `UpNextRepeatMarkerInstrumentedTest`.
- **Revisit when.** Up Next shows history or a "from the start" section (#431).

### A18. Repeat All with shuffle replays the same shuffled order on every loop (#393)

- **What.** The shuffled live order is made once, by a shuffled fresh play or shuffle ON, and the
  wrap at the end goes to the first slot of that same order. Nothing reshuffles at the wrap.
- **Why intended.** The live order is the queue: it is stored and restored verbatim, Up Next shows
  it, and the restore's start rule (#401) places a missing current song by its stored position. A
  reshuffle at the wrap would discard a hand reorder, which the shuffle OFF confirmation exists to
  protect, and would have to happen inside a window that media3 wraps by itself for a queue of 51
  slots or fewer, after it has already moved on. It would also be a reorder outside the toggles, and
  would need the guarantees invariants I1 and I2 give a toggle (the playing item never removed, no
  slot dropped or duplicated) at that moment (`docs/queue-shuffle-design.md` section 9).
- **What pins it.** `QueueShuffleRepeatLoopTest`; device: `QueueShuffleRepeatLoopInstrumentedTest`.
- **Revisit when.** A reshuffle-per-loop option is wanted.

## Deferred

Known behaviours that are not fixed yet. The first group is the "out of scope" list of
`docs/queue-shuffle-design.md` section 9, without the items covered elsewhere: the order of the
queue restore and the daily backup (`docs/queue-persistence.md` section 5), the two commits of
ordinary edits (A3), the same shuffled order on every Repeat All loop (A18), and shuffle from other
controllers (`docs/queue-engine.md` section 2). The second group comes from the queue-persistence work, the last two rows from
the queue entry-points work (`docs/queue-entry-points.md`).

| Behaviour | Issue |
|---|---|
| Add to Queue places a new song at the end of the live order, after the whole remaining context. | #430 |
| Swipe-to-remove is instant and permanent with no Undo, and any release above the fixed 125dp/s fling threshold removes, however short the drag. Parked for the maintainer's own feel testing. An Undo needs: the slot record and its song, its predecessors in the live order and in the baseline, its extra tag, the shuffle state at the time, rules for re-inserting it when either predecessor has gone, persistence of the re-insert through the two commits of A3, and no survival across a process death without a schema change. A snackbar over the player could cover the controls. | #412 |
| External controllers only see the loaded window of about 51 items as the queue. | #409 |
| O(N) work on large queues: every queue write rewrites every row (including on pause and when leaving the app), every track change hydrates the whole Up Next list, and the view model mirrors the whole queue and the Up Next list. Moved to a separate performance batch. | #406 |
| The rest of that performance batch: the Up Next list staying composed (offscreen layer and mask included) while the sheet is collapsed in queue mode; every Up Next row receiving the whole list for its self-heal check; the large-library benchmark scenarios. | #419, #420 (part 2), #293 |
| After Clear or Replace, the customised-queue flag stays true until the new queue installs. A second song tap in that short window raises the Keep/Clear prompt again; Keep then waits for the load and applies to the new queue. | none yet |
| The queue-load wait is proven on the real engine only for a load suspended at its session wait (`QueueLoadWaitInstrumentedTest`). A load suspended while it builds the start song's artwork is covered by the view-model tests alone: there is no seam to hold the artwork step, and none is added for a test. | none yet |
