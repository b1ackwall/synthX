# Queue persistence

How the play queue is saved and brought back: the three tables, every write and its order, the
restore (repair, start slot, saved modes, customised-queue flag) and the startup sequence around
it. Paths are relative to `app/src/main/java/com/v1per/synthx/`. The shuffle rules themselves
(live order, baseline, extras, reorder flag) are in `docs/queue-shuffle-design.md`; behaviours
that are known and accepted, or deferred, are listed in `docs/queue-accepted-behaviours.md`.

## 1. The tables

| Table | Column | Meaning |
|---|---|---|
| `queue_items` | `slot_id` | The stable identity of one queue occurrence (the same song can fill several slots). |
| | `position` | The slot's place in the live (playing) order. Every queue write renumbers 0..n-1; a song deletion's cascade removes rows and leaves gaps. |
| | `song_id` | The song; `ON DELETE CASCADE` on `songs.id`. |
| | `extra_kind`, `extra_sequence` | Set only on a shuffle session extra (1 = Play Next, 2 = Add to Queue) and its sequence number; NULL on every ordinary slot. |
| `queue_baseline_items` | `slot_id`, `position`, `song_id` | The unshuffled reference order shuffle OFF returns toward. Same cascade. |
| `playback_cursor` (one row, `id` = 0) | `current_slot_id` | The playing slot. No foreign key: it can name a slot whose row a cascade took. |
| | `position_ms` | Where in that song playback is. |
| | `shuffle_enabled`, `repeat_mode` | The playback modes (`repeat_mode` is media3's `REPEAT_MODE_*`). |
| | `shuffle_reordered` | True while a shuffled queue has been reordered by hand; shuffle OFF then asks first. |
| | `queue_customized` | True once the queue has been edited by hand (Play Next, Add to Queue, a move, a remove) since its last fresh play; a song tap then asks Keep or Clear, and Play or Shuffle asks Replace. |
| | `current_queue_position` | The `queue_items.position` of the current slot's row, read from the rows inside every cursor write. Kept while the slot is unchanged and its row is gone, so a restore can still place a missing song. NULL with no current slot. |

All three tables live in the one Room database, so a backup and a restore carry them together.

## 2. Writes

Every write goes through `QueuePersistence`'s single FIFO writer, so writes land in the order they
are enqueued. Each DAO method below is one Room transaction.

### 2.1 State table

What each write does to each piece. *Set* = written from the call, *derived* = read from the rows
in the same transaction, *copied* = carried over from the stored row, *kept* = column untouched,
*null keeps* = the call may pass null to copy the stored value.

| DAO write | Callers | Live rows | Baseline rows | Slot, `position_ms` | `current_queue_position` | `queue_customized` | `shuffle_reordered` | Modes |
|---|---|---|---|---|---|---|---|---|
| `replaceQueueBaselineAndCursor` (unified) | fresh play (`playQueueInternal`: song tap, Play, Shuffle, Clear, Replace, the Play Next / Add to Queue / Keep fallbacks); shuffle toggle (`setShuffleEnabledInternal`) | set | set | set | derived | set: false on a fresh play, the engine's value on a toggle | set | set |
| `replaceQueueAndCursor` | `persistQueue`: Play Next, Add to Queue, move, remove, library deletion, rescan remap, cache reconcile, pause, leaving the app (`persistPlaybackState`) | set | kept | set | derived | set from the engine's flag (true after any of the four edits, unchanged otherwise) | set from the order store | copied |
| `replaceQueueAndCursor` | the restore's dropped-songs rewrite | set | kept | set (the chosen start slot, see 4.3) | derived | null keeps | set (the repaired flag) | copied |
| `replaceBaselineQueue` | `DeferredBaselineWriter`, flushed by `persistQueue` right after the live write | kept | set | kept | kept | kept | kept | kept |
| `updateCursor` | `checkpointCursor` (track change, playback stopping, the periodic checkpoint), a same-track seek, the end of the queue, the service's `onTaskRemoved` and `onDestroy` | kept | kept | set | derived, or kept while the slot is unchanged and its row is gone | copied | copied | copied |
| `updateRepeatMode` | `onRepeatModeChanged` (every repeat change, including saved modes applied to the session, see 4.4) | kept | kept | kept | kept | kept | kept | repeat set |
| `updateShuffleEnabled` | none in production | kept | kept | kept | kept | kept | kept | shuffle set |
| either mode write, no cursor row yet | the first mode write ever | — | — | NULL, 0 | NULL | false | false | the one mode set |
| foreign-key cascade | a song row deleted | the song's rows gone (gaps) | the song's rows gone | kept | kept | kept | kept | kept |
| backup restore | the whole database file is replaced | | | | | | | |

No cursor-only or mode-only write changes `queue_customized` or `shuffle_reordered`. A queue write
that does not know the authoritative value passes null and keeps the stored one.

### 2.2 Edits that change both orders: live first

Some edits change the live order and the baseline:

- with shuffle off, Play Next, Add to Queue and a move (mirrored into the baseline);
- in either mode, a remove of a slot that is in the baseline;
- a library deletion (`removeAll`);
- a rescan remap that changes a song id (`remapSong`).

`QueueOrderStore` hands the changed baseline to the engine's `DeferredBaselineWriter`, which holds it.
`persistQueue` then enqueues the live write and right after it the baseline write. These are two
commits, live first.

Shuffled Play Next, Add to Queue and moves leave the baseline alone: one commit. A fresh play and a
shuffle toggle save everything in one commit; any held baseline is dropped there.

A process death between the two commits leaves the new live order with the old baseline. The restore
repair then gives:

| Edit | Restored queue |
|---|---|
| any of the edits above with shuffle off | the queue after the edit (the repair takes baseline := live) |
| shuffled remove | the queue after the edit; the removed song does not come back (the repair drops baseline slots that are not in live) |
| library deletion, either mode | the queue after the edit (the cascade already took the rows from both tables) |
| shuffled rescan remap to a new id | every song kept and the live order after the edit, but the remapped slot loses its album place; see `docs/queue-accepted-behaviours.md` |

A death before the live commit restores the queue before the edit.

## 3. The cold-start read

`QueuePersistence.loadRestore` reads the live rows and the cursor in one transaction and returns:

- `Queue`: at least one saved slot still resolves to a song (not deleted, not flagged missing);
- `ModesOnly`: a cursor row exists but no slot survives (no rows, or every saved song gone);
- `Empty`: nothing is saved, or the read failed (the saved modes are then unknown).

The baseline is a second, separate read; a failed baseline read counts as empty (accepted, see
`docs/queue-accepted-behaviours.md`). The adoption of a running session uses
`loadQueueWithBaseline`, one transaction for both, and goes through the same repair.

## 4. Restore

### 4.1 Repair

Every restored queue goes through `repairRestoredQueue` before the engine adopts it; the live order
is never changed. The rules are in `docs/queue-shuffle-design.md` section 8.

### 4.2 Where it starts

`resolveRestoreStart` (`playback/QueueRestoreStart.kt`), with P = the stored `current_queue_position`:

| Saved state | Start |
|---|---|
| the saved slot survives | that slot, at the saved `position_ms` |
| it does not survive, P is stored | the first surviving slot whose stored position is >= P, else the last surviving slot before P; at 0 ms |
| it does not survive, no P (a cursor saved before version 18, or no current slot) | the first surviving slot, at 0 ms |
| no slot survives | nothing is restored (see 4.4) |

"Does not survive" covers a song flagged missing (its row is kept, so its position is still stored)
and a song whose row a cascade took (P is still on the cursor; positions have a gap). The surviving
slots are read in their stored live order, so a shuffled queue continues in its playing order.

### 4.3 The dropped-songs rewrite

When saved rows do not resolve, the restore rewrites the live rows from the restored order with
the chosen start slot as the cursor; `current_queue_position` is derived from those rows,
`queue_customized` is kept and the reorder flag is the repaired one. The baseline is not rewritten.

### 4.4 Saved modes without a queue

For `ModesOnly`, the engine waits for the session connection and then, if neither the order store
nor the session holds a queue, applies the saved shuffle flag (the flag only, since nothing is
pending) and the repeat mode, on its own state and on the session's player. The next fresh play
saves those modes: a song tap keeps the shuffle flag, Play turns shuffle off, Shuffle turns it on.
A session that already holds a queue keeps its own modes. For `Empty`, nothing is applied.

### 4.5 The customised-queue flag

The restore and the adoption of a running session set the engine's `isQueueCustomized` from the
saved row; a restore that yields no queue leaves it false. `onQueueStructureChanged` sets the flag
before it saves, so an edit's own write already carries true.

## 5. Startup sequence

`MainActivity` runs `runLaunchSequence` once the database gate has opened normally:

1. If launch maintenance may run (onboarding complete, no first scan pending): request the saved-queue
   restore. The UI takes input from the next step on, so the restore is already pending by then.
2. Prune finished WorkManager work.
3. If maintenance may run, start it on an IO coroutine: the daily backup with its verification, the
   daily Issues scan, the post-restore art sweep, the playlist art backfill, then the launch scans.

The restore runs on the view model's own coroutine and waits for neither the backup nor the Issues
scan. The backup can run beside it: it first waits for already-admitted database writes, then takes
its snapshot with `VACUUM INTO` on the primary connection, which only sees committed transactions.
The restore's rewrite is one transaction. The verification reads backup files only. A snapshot
taken between an edit's two commits is repaired like a process death at that point (2.2).

### 5.1 Waiting for the restore

`PlaybackViewModel.queueRestoreState` is `NotRequested` until a restore is requested, `Pending` while
it runs, and `Done` once it returns (installed, skipped because a timeline exists, nothing saved,
failed) or the wait bound passes.

While it is `Pending` and nothing is playing, Play Next, Add to Queue and the Keep fallback:

- answer at once (`Added`, or `true` for Keep);
- hold their action until the restore settles. Held actions then run in the order they are taken.

Each held action then checks the engine's own state:

- **The restore produced a queue:** Play Next inserts after the current song and Add to Queue
  appends. Neither is applied when the song already sits in that spot, because the confirmation
  that would ask has no dialog to appear in. Keep plays the song next and jumps to it, unless it is
  already the current song or in Up Next.
- **No queue came back:** the fallback runs, playing the song alone.

Actions taken while something is playing, or when no restore is pending, never wait.

The bound is `QUEUE_RESTORE_WAIT_TIMEOUT_MS` = 15 s. The restore can wait out a whole round of
session connection attempts (`ReconnectPolicy`: four attempts with 1, 2 and 4 s of backoff, at least
about 7 s), so the bound is about twice that backoff. A slow connection that still succeeds is not
cut off, and a session that never answers releases the actions in bounded time. The bound runs on
the view model scope's clock, so tests drive it with virtual time; the constructor takes the bound.

After the bound, the held actions run the fallback. A restore that returns later runs no held action
again, and the engine does not install a saved queue over a timeline that exists by then. So a
queue started after the bound is never replaced by the late restore.

A configuration change keeps the view model and the engine. The recreated Activity requests the
restore again, and the engine returns at once because a timeline exists. If nothing is playing
then, for example after the queue ended, the three actions wait for that short restore.

Song taps and the page Play and Shuffle buttons do not wait (see `docs/queue-accepted-behaviours.md`).

## 6. After updating to version 18

`MIGRATION_17_18` adds `queue_customized` (default 0) and `current_queue_position` (NULL) and writes
nothing else. So, once, until the next save:

- a queue built before the update reads as untouched, so the next song tap does not ask Keep or Clear;
- a restore whose saved song is gone starts at the first surviving slot.

The first queue write after the update stores both values.

## 7. Invariants

| Id | Invariant |
|---|---|
| P1 | Whenever the current slot has a live row, `current_queue_position` equals that row's `position`. While the slot is unchanged and its row is gone it keeps the last position; with no current slot it is NULL. |
| P2 | Only a queue write given a value changes `queue_customized`. The four edits save true, a fresh play saves false, every other queue write saves the engine's unchanged value or keeps the stored one, and no cursor-only or mode-only write touches it. The same holds for `shuffle_reordered` with the shuffle rules. |
| P3 | For an edit that saves both orders, the live commit is enqueued before the baseline commit. |
| P4 | Every restored or adopted queue goes through `repairRestoredQueue`. |
| P5 | Nothing waits for a restore without the bound: `Pending` always ends at the restore's return or the bound, whichever is first. |

## 8. Traceability

JVM tests are under `app/src/test/`, device tests under `app/src/androidTest/` (run one class per
process on the device).

| Decision | Tests |
|---|---|
| #398 restore requested first, before pruning | `LaunchSequenceTest` |
| #398 Play Next / Add to Queue / Keep wait, apply or fall back, duplicate skip, timeout | `PlaybackViewModelRestoreWaitTest`, `PlaybackViewModelRestoreTimeoutTest`; device: `PlaybackViewModelRestoreWaitInstrumentedTest`, `PlaybackViewModelRestoreTimeoutInstrumentedTest` (late restore after the bound) |
| #398 a running session is not replaced | device: `QueueColdStartInstrumentedTest.aRunningSessionIsNotReplacedByTheSavedQueue` |
| #400 saved modes without a queue | `QueuePersistenceRestoreReadTest`; device: `QueueSavedModesInstrumentedTest`, `QueueSavedModesReadFailureInstrumentedTest` |
| #401 restore start | `QueueRestoreStartTest`, `QueuePersistenceRestoreReadTest`; device: `QueueRestoreStartRoomInstrumentedTest`, `QueueRestoreStartInstrumentedTest`, `QueuePersistenceEndToEndInstrumentedTest.aGoneSavedSongSavesTheNewStartAndItsQueuePosition` |
| #401 the position follows every write (P1) | device: `RepositoryQueueCursorColumnsRoomInstrumentedTest`, `QueuePersistenceEndToEndInstrumentedTest.aTrackChangeAndASeekKeepTheFlagAndFollowTheQueuePosition` |
| #404 live first | `DeferredBaselineWriterTest`; device: `QueueEditWriteOrderInstrumentedTest`, `QueueEditKillReplayInstrumentedTest` |
| #404 the accepted remap exception | `QueueRemapKillOutcomeTest`; device: `QueueEditKillReplayInstrumentedTest.aShuffledRemap…` (two) |
| #424 the durable flag (P2) | `QueuePersistenceRestoreReadTest`; device: `QueueDurableFlagInstrumentedTest`, `RepositoryQueueCursorColumnsRoomInstrumentedTest`, `QueuePersistenceEndToEndInstrumentedTest`, `QueueColdStartInstrumentedTest.restoresTheSavedQueuePausedAtTheSavedSlotAndPosition` |
| #424 cursor-only writers emit cursor writes | device: `PlaybackCheckpointInstrumentedTest` (track change, seek, pause from outside), `PlaybackPeriodicCheckpointInstrumentedTest`, `NaturalQueueEndInstrumentedTest`, `PlaybackServiceExitInstrumentedTest` (`onTaskRemoved`, `onDestroy`) |
| Version 18 | device: `Migration17To18Test`, `MigrationFullChainTest`, `MigrationFullChainUserDataTest`, `PhaseBRestoreJournalUpgradeInstrumentedTest`, `BackupSnapshotInstrumentedTest`; JVM: `MigrationCompletenessTest`, `BackupPrimitivesTest`, `BackupSchemaContractExportTest`, `RestoreJournalCompatibilityTest` |
| #403 and #405 (accepted) | `QueuePersistenceReadTest.failingReadsRestoreNothingInsteadOfThrowing`, `QueueShuffleExamplesTest` (E16), `QueueRestoreRepairTest`; device: `QueueColdStartInstrumentedTest.aDroppedSongRewritesTheSavedRowsOnceFromTheFullOrder` |
