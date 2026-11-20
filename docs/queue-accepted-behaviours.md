# Queue: accepted and deferred behaviours

Check here before filing an audit finding about the play queue, shuffle or queue persistence. Each
entry below is either a behaviour the maintainer accepted on purpose (with what pins it and when to
look again) or a known behaviour that is deferred to later work. Paths are relative to
`app/src/main/java/com/v1per/synthx/`. The rules themselves are in `docs/queue-shuffle-design.md`
and `docs/queue-persistence.md`.

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
  - a rescan remap to a new song id.

  `docs/queue-persistence.md` section 2.2 lists what a process death between them leaves.
- **Why accepted.** With the live commit first, the restore repair turns the state between the two
  commits into the queue after the edit, in both shuffle modes, for every such edit but the one in A4.
- **What pins it.** `DeferredBaselineWriterTest`, `QueueEditWriteOrderInstrumentedTest`,
  `QueueEditKillReplayInstrumentedTest`.
- **Revisit when.** An edit path needs both orders in one commit, or a new edit path changes the
  baseline.

### A4. A process death inside a shuffled rescan remap can cost one song its album place (#404)

- **What.** A rescan that finds a file's content changed flags the old song row missing and inserts
  the song under a new id. The scanner does not touch the saved queue, so only a live engine moves
  the queue to the new id (`applyRemap` → `remapSong`): first the live commit, then the baseline
  commit. If the process dies between the two while shuffle is on:
  - the live order holds the new id and the baseline still holds the old, missing one;
  - the restore keeps every song and the live order after the edit;
  - **if the remapped song is not the current one**, it comes back as an Add to Queue extra, so
    shuffle OFF places it after the album instead of in its album place;
  - **if it is the current song**, the baseline is rebuilt from the shuffled live order, so shuffle
    OFF keeps the shuffled order.
- **Why accepted.** Every song is kept and nothing removed comes back. The window is one pair of
  commits during a rescan. Without live first, the same death drops the song from the queue.
- **What pins it.** `QueueRemapKillOutcomeTest`, and on a device
  `QueueEditKillReplayInstrumentedTest.aShuffledRemapOfASongThatIsNotPlayingComesBackAsAnExtra` and
  `aShuffledRemapOfThePlayingSongRebuildsTheBaselineFromLive`.
- **Revisit when.** The scanner repoints saved queue rows (live and baseline) to the new id in its
  own transaction. That would also cover a remap that happens while no engine is running, which
  leaves the slot on the missing old id until the next restore drops it.

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

## Deferred

Known behaviours that are not fixed yet. The first group is the "out of scope" list of
`docs/queue-shuffle-design.md` section 9, without the three items covered elsewhere: the order of the
queue restore and the daily backup (`docs/queue-persistence.md` section 5), the two commits of
ordinary edits (A3), and shuffle from other controllers (`docs/queue-engine.md` section 2). The second group comes from the queue-persistence work, the last two rows from
the queue entry-points work (`docs/queue-entry-points.md`).

| Behaviour | Issue |
|---|---|
| Add to Queue places a new song at the end of the live order, after the whole remaining context. | #430 |
| Repeat All replays the same shuffled order on every loop. | #393 |
| A shuffle tap that changes nothing leaves a scroll-to-top pending; the Shuffle and Repeat buttons expose no on/off state to accessibility. | #395, #421 |
| Up Next gestures (reorder auto-scroll, padding, swipe-to-remove, the overlay show/hide gesture and related races). | #410 to #418 |
| External controllers only see the loaded window of about 51 items as the queue. | #409 |
| O(N) work on large queues: every queue write rewrites every row (including on pause and when leaving the app), every track change hydrates the whole Up Next list, and the view model mirrors the whole queue and the Up Next list. Moved to a separate performance batch. | #406 |
| A song tap or a page Play / Shuffle tap during the launch restore does not wait for it. Its Keep/Clear or Replace decision runs before the restore finishes, without the stored customised-queue flag, so it plays at once and the restore then finds a timeline and stands down. Making those taps wait would mean holding `requestPlayQueue` and `requestPlayAll` in the same restore gate and then re-checking the flag from the engine; the prompts are pending view model state, so they could be raised after the wait without a UI change. | none yet |
| After Clear or Replace, the customised-queue flag stays true until the new queue installs. A second song tap in that short window raises the Keep/Clear prompt again; Keep then waits for the load and applies to the new queue. | none yet |
| The queue-load wait is proven on the real engine only for a load suspended at its session wait (`QueueLoadWaitInstrumentedTest`). A load suspended while it builds the start song's artwork is covered by the view-model tests alone: there is no seam to hold the artwork step, and none is added for a test. | none yet |
