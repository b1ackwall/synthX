# Queue shuffle design

How shuffle ON and shuffle OFF change the play queue, and what the queue keeps through edits,
restarts and repeat-mode changes. The code lives in `playback/QueueShuffleOrder.kt` (the pure
order rules), `playback/QueueOrderStore.kt` (the two orders and the session state) and
`playback/ExoPlayerEngineQueueOps.kt` (the toggle on the player). Paths are relative to
`app/src/main/java/com/v1per/synthx/`.

## 1. Purpose

The queue is the user's own work: an album played in order, a playlist, songs added by hand. A
shuffle toggle reorders what is still to come. It never loses a song, never brings a removed song
back, never interrupts the song that is playing, and turning shuffle off returns the songs to the
order the user knows.

## 2. Vocabulary

| Term | Meaning |
|---|---|
| live | The order that plays: a list of slots, each slot a slot id plus a song id. While shuffle is on it is the shuffled order. |
| cursor, current | The slot that is playing. |
| history | The slots before the cursor in live. Position decides, nothing else: a song above a tapped song is history even if it was never heard. |
| pending | The slots after the cursor in live. |
| baseline | The full unshuffled reference order (history and current included) that shuffle OFF restores toward. |
| extra | A slot added by Play Next or Add to Queue while shuffle is on. It is in live, not in baseline, and carries a kind (Play Next or Add to Queue) and a sequence number that grows with every extra. Slots added while shuffle is off are not extras. |
| baseline-origin slot | A slot in baseline that is not an extra. |
| anchor | Where the album continues after shuffle OFF: the current slot's baseline position when it is baseline-origin, otherwise the position of the nearest baseline-origin slot before it in live, otherwise -1. |
| reorder flag | True when the user moved a slot while shuffle was on, since the last toggle or fresh play. |

Slot identity is what counts everywhere: the same song can sit in several slots, and each slot is
kept, moved or removed on its own.

## 3. Data model

```
                 QueueOrderStore (main thread)
   live      : [slot] the order that plays            -> queue_items (position order)
   baseline  : [slot] the unshuffled reference order  -> queue_baseline_items
   extras    : slot id -> (kind, sequence)            -> queue_items.extra_kind, extra_sequence
   reordered : Boolean                                -> playback_cursor.shuffle_reordered
                 Engine
   cursor    : slot id, position                      -> playback_cursor.current_slot_id, position_ms
   shuffle   : Boolean                                -> playback_cursor.shuffle_enabled
   repeat    : Off / All / One                        -> playback_cursor.repeat_mode
```

```
          shuffle ON (3.2)                          Play Next / Add to Queue:
   +-----------------------------+                    extras grow (live only)
   |                             v                  drag: reorder flag set
 [ OFF ]                       [ ON ]  <----------+
   ^  baseline == live           |  baseline != live (ordered differently)
   |  no extras, no flag         |
   +-----------------------------+
          shuffle OFF (3.3): live rebuilt, baseline := live, extras and flag cleared
```

Every fresh play (Play button, Shuffle button, song-row tap) replaces both orders and clears the
extras and the flag. The ExoPlayer timeline holds only a window of about 51 slots around the
cursor; a toggle reorders the records and then reconciles that window without removing the
playing item.

## 4. Rules

These are the maintainer's decisions; the implementation follows them as written unless section 11
says otherwise.

### 4.1 Shuffle ON (shuffle was off)

| Case | New live | Baseline | Extras, flag |
|---|---|---|---|
| Repeat Off or One | history and current unchanged, then the pending slots shuffled uniformly | live from before the tap | none, false |
| Repeat All | current first, then every other slot (history included) shuffled uniformly | live from before the tap | none, false |
| Nothing pending, Repeat Off or One | unchanged (only the flag changes) | live | none, false |
| Shuffle already on | no change | no change | no change |

Pending slots include songs added with Play Next or Add to Queue while shuffle was off: they are
ordinary slots and are shuffled with the rest. Removed slots stay removed. Slots reordered while
shuffle was off are part of baseline, so shuffle OFF brings that order back.

### 4.2 Shuffle OFF (shuffle was on)

At the tap: H = history, c = current, P = pending baseline-origin slots, PN = pending Play Next
extras, AQ = pending Add to Queue extras, HE = extras in H. RA = the slots of P in baseline order
starting just after the anchor, then wrapping to the ones at or before it.

| Case | New live |
|---|---|
| Repeat Off or One | H (unchanged) + c + PN (newest first) + RA + AQ (oldest first) |
| Repeat All | HE (in their order) + c + PN (newest first) + RA over every baseline-origin slot except c, heard ones included + AQ (oldest first) |

Then baseline := the new live, every extra becomes an ordinary slot, the flag is cleared and
shuffle is off. The shuffled order of the album songs is never read, so reorders made while
shuffled are discarded; removals made while shuffled stay. A live slot missing from baseline is
treated as an extra (an untagged one as Add to Queue, after every tagged one, in live order) and
is never dropped; a baseline slot missing from live is never brought back.

The current slot can itself be an extra (a Play Next song that is playing): history stays, the
current slot stays, and the anchor decides where the album continues.

### 4.3 Edits

| Edit | Shuffle off | Shuffle on |
|---|---|---|
| Play Next | right after the cursor in live and in baseline | right after the cursor in live only, as a Play Next extra; repeated Play Next is newest first |
| Add to Queue | end of live and of baseline | end of live only, as an Add to Queue extra |
| Remove (swipe) | gone from both orders | gone from both orders, its tag with it |
| Library deletion | every slot of the song gone from both orders | the same, tags included |
| Reorder (drag) | live and baseline together, by slot identity: the slot lands after the slot that precedes it in live | live only; sets the reorder flag when a slot actually moves |
| Next, previous, Up Next tap | the cursor moves; history and pending follow by position | the same |
| Repeat mode change | no reordering | no reordering |

### 4.4 Fresh plays

| Entry | Queue | Shuffle |
|---|---|---|
| Play button | the page in page order, from the first song | off |
| Shuffle button | a random start song, the rest shuffled; baseline = page order, history empty | on |
| Song-row tap, shuffle off | the list in order, cursor on the tapped song; the songs above it are history | stays off |
| Song-row tap, shuffle on | the tapped song first, the rest shuffled; baseline = list order | stays on |

Every fresh play clears the extras and the reorder flag.

### 4.5 Repeat One

Repeat One follows the Repeat Off rules for every toggle: history is never shuffled in and never
replayed. Only Repeat All uses the Repeat All variants.

## 5. Invariants

| Id | Invariant |
|---|---|
| I1 | A toggle never interrupts playback and never changes the current slot, its position or the play/pause state. The playing media item is never removed and re-added. |
| I2 | A toggle never drops or duplicates a slot: live holds the same multiset of slots before and after. |
| I3 | With Repeat Off or One, a toggle never changes the history. |
| I4 | While shuffle is off, baseline equals live. |
| I5 | A slot the user removed, or a library deletion took out, is absent from live and baseline after any later operations. |
| I6 | Changing the repeat mode never changes the order of live or baseline. |
| I7 | Fail closed: a toggle whose computed order would not keep the cursor and the same slots changes nothing, keeps the old state and logs one line. |
| I8 | Baseline is never trusted blindly: shuffle ON takes it from live at the tap; shuffle OFF treats a live slot missing from baseline as an extra and ignores baseline slots that are not in live. |

## 6. Worked examples

t1..tN are album songs, X and Y songs added by Play Next or Add to Queue.

| # | Steps | Result |
|---|---|---|
| E1 | [t1..t14] unshuffled, cursor t4; ON then OFF | [t1..t14], cursor t4, history t1-t3 unchanged |
| E2 | Shuffle button on 12 songs starts at t7; OFF | [t7, t8, t9, t10, t11, t12, t1, t2, t3, t4, t5, t6]; baseline the same |
| E3 | [t1..t8], ON, Play Next X, advance to X; OFF | [t1, X, t2, t3, t4, t5, t6, t7, t8] |
| E4 | ON at t1, Play Next X, OFF before X plays | [t1, X, t2, ..., t8] |
| E5 | ON at t1, Play Next X, advance to X, then to t5; OFF | [t1, X, t5, t6, t7, t8, t2, t3, t4]; X is not queued again |
| E6 | Repeat All, [t1..t8], cursor t4; ON | [t4] + t1, t2, t3, t5..t8 shuffled |
| E6 | then OFF, Repeat All still on | [t4, t5, t6, t7, t8, t1, t2, t3] |
| E7 | unshuffled at t1, Play Next X → [t1, X, t2..t6]; ON (X shuffled with the rest); OFF | [t1, X, t2, ..., t6] |
| E8 | [t1..t6] at t1, ON, Add to Queue Y; OFF | [t1, t2, t3, t4, t5, t6, Y]: Y last, after every pending album song |
| E9 | ON at t1, Play Next X, Play Next Y | [t1, Y, X, ...]; OFF → [t1, Y, X, t2, ...] |
| E10 | [t1..t6] at t1: remove t3 while shuffled, OFF; or remove t3 unshuffled, ON, OFF | [t1, t2, t4, t5, t6]; t3 absent from live and baseline |
| E11 | unshuffled [t1..t6], move t5 after t1 → [t1, t5, t2, t3, t4, t6]; ON; OFF | [t1, t5, t2, t3, t4, t6] |
| E12 | ON, drag t6 to the top of Up Next, tap shuffle | the question appears; Turn off shuffle → t6 back in its original place among the pending songs; Keep shuffle on → still on, order unchanged, flag still set, also after a restart |
| E13 | one song left, Repeat Off: ON | order unchanged, shuffle on; Play Next X → X next; OFF → X still next |
| E14 | song-row tap on t7 of 10, shuffle on | [t7] + the rest shuffled; baseline [t1..t10] |
| E14 | the same tap, shuffle off | [t1..t10], cursor t7, t1-t6 history; ON shuffles only t8-t10 |
| E15 | ON at t1, Play Next X, advance to X, then t1's song is deleted from the library; OFF | [X, t2, t3, ..., t8]: anchor -1, album in baseline order |
| E16 | shuffled live [t3, t1, t4, t2] at t1 with an empty baseline; OFF | [t3, t1, t4, t2]: the current order, nothing lost |
| E16 | shuffled live [t1, t5, t3, t2, t4] at t1 with baseline [t1, t2, t3]; OFF | [t1, t2, t3, t5, t4]: t2, t3 in baseline order, t5, t4 (outside baseline) last in live order |
| E17 | shuffle on, Repeat All toggled on and off repeatedly | order identical before and after each change |

The five scenarios of the queue audit (`docs/queue-audit/appendix-shuffle-simulation.md`) under
these rules:

| Scenario | Result |
|---|---|
| S1: ON, Play Next X, skip to X, OFF | [t1, X, t2, ..., t8] |
| S2: ON, Play Next X, OFF before X plays | [t1, X, t2, ..., t8] |
| S3: ON, Play Next X, X plays, next song t8, OFF | [t1, X, t8, t2, t3, t4, t5, t6, t7] |
| S4: Shuffle button starts at t7 of 12, OFF at once | [t7, ..., t12, t1, ..., t6] |
| S5: in order at t6, ON, two songs (t12, t11), OFF, ON again | OFF → [t1..t6, t12, t11, t7, t8, t9, t10]; ON again keeps all 12 |

## 7. Confirm dialog

- The engine publishes the reorder flag as `shuffleReorderedByUser`.
- When the user taps shuffle to turn it off and the flag is set, the app asks instead of toggling:
  - title "Restore the original order?";
  - message "Turning shuffle off puts the songs you moved back in their original order.";
  - buttons "Turn off shuffle" and "Keep shuffle on".
- Turn off shuffle runs the toggle. Keep shuffle on, and Back, keep shuffle on and leave the flag and
  the reordered queue exactly as they are.
- `PlaybackViewModel` holds the pending question as a state flow. It survives a configuration
  change; it does not survive process death, but the flag does, so the next tap asks again.
- There is no question when shuffle is turned on, or when the flag is not set.
- Every in-app shuffle toggle goes through `PlaybackViewModel.setShuffleEnabled`. The engine's own
  `setShuffleEnabled` always runs when called.
- Test tags: `shuffle_off_confirm_dialog`, `shuffle_off_confirm_button`,
  `shuffle_off_dismiss_button`.

## 8. Persistence and restore

Schema version 17 adds three columns, additively, through `MIGRATION_16_17`:

| Column | Meaning |
|---|---|
| `queue_items.extra_kind` INTEGER NULL | 1 = Play Next, 2 = Add to Queue; NULL for every ordinary slot |
| `queue_items.extra_sequence` INTEGER NULL | the extra's sequence number; NULL for every ordinary slot |
| `playback_cursor.shuffle_reordered` INTEGER NOT NULL DEFAULT 0 | the reorder flag |

Saved rows from version 16 come out untagged and unflagged.

Writes:
- A toggle or fresh play writes live, baseline, cursor, shuffle flag, repeat mode, the extra tags and
  the reorder flag in one transaction.
- Ordinary edits keep their two commits, in this order (see `docs/queue-persistence.md`):
  - the active order with its tags, plus the cursor with the engine's flag;
  - the baseline write.
- A track change writes the cursor only and copies the stored flag.
- Each mode-only write changes its own column.
- A mode-only write that creates the cursor row starts with the flag cleared.

A restored queue is repaired before the engine adopts it. The live order is never changed.

| State at restore | Repair |
|---|---|
| shuffle off | baseline := live; no extras; flag cleared |
| shuffle on, baseline empty, or sharing no slot with live, or missing an untagged current slot | baseline := live without its tagged extras |
| shuffle on, a tagged extra is the current slot | baseline kept (the extra is outside baseline by definition) |
| shuffle on, baseline slots not in live | dropped from baseline |
| shuffle on, tags of slots not in live | dropped |
| shuffle on, untagged live slot missing from baseline | an Add to Queue extra, after the highest tag, in live order |
| shuffle on | the reorder flag is kept |

A queue these rules produced comes back unchanged. New extras continue the sequence after the
highest restored one. The cold-start restore reads the active order and the baseline in two reads;
the repair makes a failed or stale baseline read lossless.

## 9. Out of scope

- shuffle from external controllers (the media session's own shuffle command);
- where Add to Queue places a new song (the end of live);
- reshuffling at each Repeat All loop;
- the Keep, Clear and Replace prompts;
- the order of the queue restore and the daily backup;
- the scroll-to-top flag in `QueueModeState`, and the accessibility semantics of the shuffle button;
- Up Next gestures, system transport, and the size of the player window;
- the two commits of ordinary edits.

## 10. Traceability

JVM tests are under `app/src/test/.../playback/` unless noted. Instrumented tests run on a device.

| Rule | Tests |
|---|---|
| 4.1 ON, Repeat Off | `QueueShuffleExamplesTest.e1…`, `e13…`, `e14…` (in `QueueShuffleRulesTest`), `QueueOrderStoreTest.shuffleLeavesAlreadyPlayedSlotsInHistory`, `shuffleKeepsCurrentFirstPreservesMembershipAndLeavesBaselineAlone`; device: `QueueLongWindowInstrumentedTest.shuffleOnMidQueueKeepsThePlayingSongFirstAndSavesOnce` |
| 4.1 ON, Repeat All | `QueueShuffleRulesTest.e6RepeatAllShufflesTheHistoryInAndShuffleOffWrapsAfterTheCurrentSong`; device: `QueueShuffleToggleInstrumentedTest.repeatAllShufflesTheHistoryInAndShuffleOffWrapsAfterThePlayingSong` |
| 4.2 OFF, Repeat Off | `QueueShuffleExamplesTest` E2-E5, E7-E11, E15, S1-S5; `QueueOrderStoreTest.unshuffleRestoresBaselineTailAndFoldsInSessionAdditions`; device: `PlayQueueShuffleInstrumentedTest.turningShuffleOffAfterAShuffledPlayRestoresListOrderAfterTheCurrentSong`, `QueueShuffleToggleInstrumentedTest.theShuffleButtonThenShuffleOffKeepsTheSongsBeforeTheStartSong` |
| 4.2 OFF, Repeat All | `QueueShuffleRulesTest.repeatAll…` (three tests); device: `QueueColdStartInstrumentedTest.theRestoredBaselineIsWhatTurningShuffleOffFollows` |
| 4.2 extra as current | `QueueShuffleExamplesTest.e3…`, `e15…`; device: `QueueShuffleToggleInstrumentedTest.keepThenShuffleOffKeepsEverySongOfAHandEditedQueue` |
| 4.3 Play Next / Add to Queue | `QueueShuffleRulesTest.playNextWhileShuffled…`, `addToQueueWhileShuffled…`, `editsWhileUnshuffled…`; device: `QueueShuffleToggleInstrumentedTest.playNextWhileShuffledIsSavedAsAnExtraAndStaysNextThroughShuffleOff`, `shuffleOnAtTheLastSongChangesNothingAndAPlayNextSongStaysNext` |
| 4.3 Remove, deletion | `QueueShuffleExamplesTest.e10…` (two), `QueueShuffleRulesTest.removingAnExtraDropsItsTagAndALibraryDeletionDoesToo` |
| 4.3 Reorder | `QueueShuffleRulesTest.aReorder…` (three), `aMirroredMoveFollowsTheSlotNotThePositionWhenTheOrdersDiffer`, `e12…`, `aReorderedExtraIsPlacedByItsKindAndSequenceOnShuffleOff`; device: `QueueShuffleToggleInstrumentedTest.e12TheMovedSongGoesBackToItsOriginalPlaceOnShuffleOff`, `aReorderWhileShuffledIsFlaggedSavedAndSurvivesATrackChangeAndAPause` |
| 4.4 Fresh plays | `QueueShuffleRulesTest.e14…`, `theFreshPlayShuffleKeepsTheStartFirstAndEverySlotOnce`, `aFreshPlayClearsTheExtrasAndTheReorderFlag`; device: `PlayQueueShuffleInstrumentedTest` |
| 4.5 Repeat One | `QueueShuffleRulesTest.repeatOneFollowsTheRepeatOffRulesForShuffleOnAndOff` |
| I1 | device: `QueueShuffleToggleInstrumentedTest.togglesOnALongQueueNeverReplaceThePlayingItemOrRestartIt`; `QueueWindowTest.aWholesaleShuffleKeepsThePlayingItemLoadedThroughout` |
| I1-I8 together | `QueueShufflePropertyTest.everyInvariantHoldsAcrossThousandsOfRandomSequences` (3,000 seeded sequences of 60 operations) |
| I6 | device: `QueueShuffleToggleInstrumentedTest.repeatModeChangesNeverReorderAShuffledQueue` |
| I7 | `QueueShuffleRulesTest.aShufflerThat…` (three), `theToggleGuardComparesSlotsAsAMultisetAndRequiresTheCursor` |
| I8, E16 | `QueueShuffleExamplesTest.e16…` (three), `QueueShuffleRulesTest.anUntaggedLiveSlotMissingFromBaseline…`, `baselineSlotsThatAreNotInTheLiveOrderAreNeverResurrected` |
| 7 Confirm dialog | `PlaybackViewModelShuffleOffConfirmTest`; device: `ShuffleOffConfirmDialogInstrumentedTest`, `ShuffleOffConfirmShellInstrumentedTest` |
| 8 Persistence | `QueueRestoreRepairTest`; device: `RepositoryQueueExtrasRoomInstrumentedTest`, `Migration16To17Test`, `QueueShuffleToggleInstrumentedTest.aRestart…` (two), `aRestoredQueueSavedWithoutTagsTreatsItsExtrasAsAddToQueue`, `theRestoreRewriteForDroppedSongsKeepsTheExtrasAndTheFlag` |

## 11. Decisions and implementation choices

**Product decisions (the maintainer's):**
- every rule in sections 4 to 7, the invariants I1-I8 and the examples;
- Repeat One behaving like Repeat Off;
- the dialog texts;
- persisting the extras and the flag with a schema change.

**Implementation choices:**
- **Where the logic lives.** Pure functions in `QueueShuffleOrder.kt`, checked on the JVM with an
  injectable shuffler. `QueueOrderStore` holds the extras as a map from slot id to (kind,
  sequence), next to the two orders. The reorder flag is a store field, which the engine republishes.
- **Persistence.** The tags sit on the `queue_items` rows, so a song deletion's cascade takes them
  with their slot; the flag sits on the cursor row. The active-order write takes the engine's flag
  explicitly, where null keeps the stored one. Every other cursor rebuild copies the stored flag.
- **Restore repair: a deviation from rule 3.8.** The rule says that, while shuffled, a baseline
  missing the current slot is rebuilt from live. The repair skips that rebuild when the current
  slot is a tagged extra, because such a slot is outside baseline by definition. Rebuilding there
  would make shuffle OFF after a restart return the shuffled order instead of the album order.
- **Baseline slots not in live at restore.** They are dropped, so I5 holds in memory as well as on
  disk.
- **Untagged slots outside baseline.** They become Add to Queue extras after every tagged extra,
  in live order. This applies both at restore and at shuffle OFF.
- **Mirrored moves.** With shuffle off, a move is mirrored into baseline by slot identity rather than
  by position. When the orders are equal the result is the same; when they differ, the moved slot
  is the one that moves.
- **The toggle guard.** It compares the records (slot id and song id) as a multiset and requires the
  cursor slot. The engine logs a refusal once and changes nothing.
- **Sequence numbers.** They never reset within a process and continue after the highest restored
  one. They only order extras against each other.
- **The confirm flow.** `PlaybackViewModel.setShuffleEnabled` is the guarded path.
  `confirmShuffleOff` and `dismissShuffleOffConfirmation` answer the question, and
  `ShuffleOffConfirmationHost` shows it from `MainScaffold`.
- **The cold-start restore.** It still reads the active order and the baseline separately; the
  repair makes that safe.
