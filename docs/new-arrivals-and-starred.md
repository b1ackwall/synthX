# New Arrivals and Starred

How a song gets onto the Starred screen, how it leaves, and what "graduation" does to its file. Paths are
relative to `app/src/main/java/com/v1per/synthx/`. Code is cited by file and symbol.

## 1. Two different things

| | What it is | Where it lives |
|---|---|---|
| **New Arrivals folder** | An optional second audio folder (a SAF tree), separate from the Music folder. Picked in onboarding (`FolderSetupStep`) or in Library Management; both show `NewArrivalsFolderSection`. It may not be the Music folder or nest with it (`error_folders_nested`). The grant is read and write, because graduation deletes from it. | `PreferencesManager.newArrivalsFolderUri` |
| **Starred** | A per-song flag. The Starred screen lists every song with the flag set. | `SongEntity.isStarred`, stored in the column `in_inbox` |

The column keeps its older name `in_inbox`; the code and the UI say Starred. The Starred screen is route
`Screen.Starred` (`ui/screens/starred/StarredScreen.kt`, `StarredViewModel`), reached from the Library tab's
Starred row (`LibraryScreen`, item `category_starred`). The row is always there, with a count badge only when
the count is above zero.

`SongDao.getStarredSongs` lists starred songs newest first and includes missing ones.
`SongDao.getStarredSongCount`, behind the Library badge, counts only the ones that are not missing.

## 2. How scans set the flag

Every scan passes `isNewArrivalsRoot` to `FolderScanner.scan`. `NewArrivalsScanWorker` passes `true`;
`LibraryScanWorker` (the Music folder) passes `false`. In the write phase (`FolderScannerScan.kt`):

- **A new song** is inserted with `isStarred = isNewArrivalsRoot` (`SongIngestor.insertNewSong`). A song first
  found under New Arrivals is starred; one first found under the Music folder is not.
- **A song whose stored file is gone** (missing, or no longer present) and that turns up at a new path is
  re-pointed there, and its flag is re-derived from the root being scanned. Moving a file by hand from New
  Arrivals into the Music folder and rescanning therefore un-stars it, and the reverse stars it.
- **A song with both copies present** keeps its flag (`ScanDuplicateCopies.compare`). A copy under the Music
  folder outranks one under New Arrivals (`DuplicateCopyVerdict.StoredCopyOutranks` leaves the row as it
  is), so a New Arrivals copy of a song already in the library is not starred.
- **Same path, different content**: the old row is flagged missing and its path cleared, and the new content
  is inserted as a new song under the same rule as any new song.

When the New Arrivals tree is scanned:

- On every launch: `MainActivity.runLaunchScans` chains `VerifyLibraryWorker` then `NewArrivalsScanWorker`
  under `LibraryScanWorker.UNIQUE_WORK_NAME`. With no folder set, the worker ends at once
  ("No folder selected").
- On the Library tab's pull-to-refresh (`LibraryViewModel.onPullToRefresh`) when a New Arrivals folder is set.
  Without one, pull-to-refresh runs the Music-folder scan instead.
- When the folder is picked in Library Management (`LibraryManagementViewModelFolders.kt`,
  `ExistingWorkPolicy.APPEND_OR_REPLACE`), and after onboarding's first scan when the wizard picked one
  (`LibraryManagementViewModelFirstScan.kt`, `completeFirstScanInternal`).

The New Arrivals scan runs with `reconcileMissing = false`, so it never flags Music-folder songs missing.

### Older libraries

Before commit `dc6aedb`, every newly scanned song was starred, wherever it was found (the entity default
`true`). `MIGRATION_7_8` (`SynthXLegacyMigrations.kt`) added the column with default 1, then cleared it for
every song already in a playlist. A library from before that commit can therefore hold starred songs whose
files live in the Music folder.

## 3. Adding to a playlist does not graduate

`SongActionsViewModel.addToPlaylist` goes through `SongRepositoryImpl.addSongToPlaylistInternal` to
`PlaylistDao.addSongToPlaylist`, which writes `playlist_songs` only. The flag is untouched. Every
add-to-playlist path is one song at a time through the shared song actions sheet (any song row, or the player).
There is no multi-select, bulk add or drag-to-playlist.

What a playlist does do is unlock graduation. The sheet's "Remove from Starred (Graduate)" row
(`actions_remove_from_starred`, `SongActionsBottomSheet`):

- shows only when the sheet was opened from a Starred screen row (`SongActionSource.Starred`, set by
  `StarredScreen`);
- is enabled only when the song belongs to at least one playlist (`SongActionsViewModel.resolveRemoveFromStarredGate`
  asking `SongDao.isInAnyPlaylist`) and no graduation is running (`graduationInProgress`);
- otherwise stays visible but disabled.

A song that is never graduated stays starred. There is no expiry and no action that only dismisses it.

## 4. What graduation does

Tapping the row calls `SongActionsViewModel.removeFromStarred`, which runs `graduateSong` (`data/scanner/SongGraduation.kt`)
through `SongRepository.graduateSong`.

1. **Music folder required.** With no Music folder set, the dialog fails at once with
   `GRADUATION_MUSIC_FOLDER_REQUIRED_REASON`. A New Arrivals folder is not required; it is passed when set.
2. **Coordination.** The whole move holds the song's `FileOperationCoordinator.withSongLock`, one
   `RestoreCoordinator` permit (refused while a restore runs: `GRADUATION_RESTORE_IN_PROGRESS_REASON`) and root
   usage on both trees (refused while folders change: `GRADUATION_FOLDER_CHANGING_REASON`).
3. **Preflight.** The source file must exist. `GraduationSourcePreflight` requires it to sit under the Music or
   the New Arrivals root, that root to hold a write grant, and its provider to support delete. The Music root
   must be writable (`isRootWritable`). The preflight messages name the folder at fault.
4. **Copy** (`copyToGraduationTemp`, `SongGraduationPublish.kt`) into the **top level** of the Music folder
   under a temporary name (`GRADUATION_TEMP_PREFIX`, `.graduating_`, which scans skip). On a name clash the
   final name gets " (1)", " (2)" and so on (`resolveCollisionFreeName`). Subfolders are not kept: the
   destination is flat.
5. **Verify and publish** (`verifyAndPublishGraduationTemp`): the copy's song id (`HashUtils.computeSongId`)
   must equal the song's, and a full re-read must match the SHA-256 and size captured while copying. Then the
   temp file is renamed to its final name.
6. **Stray-row guard.** If another row already points at the published path, graduation stops.
7. **Delete the original**, inside `NonCancellable`. First a `pending_cleanup` record (`PendingCleanupEntity`,
   type `GRADUATE`, phase `SourceDeleted`, with the published path) is written, then the source is deleted.
8. **One transaction** (`commitGraduatedPath`) sets `file_path` to the published path and `isStarred = false`.
   The song id, its playlists and its queue slots stay as they were: the content, and so the id, has not
   changed. The record is then cleared.
9. **Player remap.** `reportGraduationCommit` sends `SongRemapEvents.notifyRemap` so the live player stops
   holding the deleted path, then reports `GraduationStage.Done`.

UI: `GraduationProgressDialog` ("Moving to Library") shows each `GraduationStage`. On success the snackbar says
"Moved to library" (`actions_removed_from_starred`) and the dialog closes after 3 s. A failure stays until the
user closes it. Failure reasons are written in the data layer, not as string resources.

### Failures

- **Before the original is deleted**: the original file and the row are untouched, and the temporary or
  published copy is deleted on a best-effort basis (the message says so if that cleanup also fails).
- **The original cannot be deleted**: the record is cleared, the verified copy is removed, the row is left
  alone, and the message says to try again ("Copy verified, but the original file could not be removed…").
- **The row vanished during the move** (`GraduationCommit.RowGone`): a failure. The moved file is kept and the
  message says to run a library scan.
- **The database write fails after the delete**: the record is kept on purpose, and the message says to run a
  scan or try again. On the next launch `PendingCleanupReconciliation.reconcile` (run by
  `DatabaseStartupGate` before the rest of the app can reach the database) re-points the row at the published
  file and clears the flag, or flags the song missing if that file is gone too.

## 5. Other ways the flag clears

Without graduation's file move:

- **Replace Song, Music folder** (`SongRepositoryImplReplace.kt`, `transplantIdentity`): a replacement whose file
  is under the Music folder and that ends up in at least one playlist is un-starred (`SongDao.dismissFromStarred`)
  in the replace's own transaction. Which folder owns the file is `DestructiveFilePreflight.owningRoot`, the same
  ownership decision destructive preflight and graduation make. A replacement whose file is under neither folder
  keeps its flag. Nothing in the UI calls `SongRepository.dismissFromStarred`, so Replace Song is the only path
  that reaches it.
- **Interrupted-graduation reconciliation**: see above.
- **Restore**: a restored backup replaces every row's flag with the backup's.
- **A hand move into the Music folder** plus a rescan (section 2).

With graduation's file move:

- **Replace Song, New Arrivals folder** (`SongRepositoryImplReplaceCommit.kt`, `completeCommittedReplace`): a
  replacement whose file is under the New Arrivals folder and that ends up in at least one playlist keeps its
  flag in the replace's transaction. Once the replace has committed, the old file's cleanup has run and the old
  song's lock is released, it is graduated by `graduateSong` itself (section 4, under the replacement's own song
  lock, with the configured folders read as "Remove from Starred" reads them: `graduateReplacementIntoMusic`).
  Graduation clears the flag. The review step says so beforehand (`replace_song_review_will_move`, from
  `ReplaceSongPreview.replacementMovesToMusic`, worked out on every preview), and the working overlay shows
  `replace_song_stage_moving` while it runs. The replace never depends on graduation: when no Music folder is
  set, a preflight fails or anything else goes wrong, the replacement stays starred in New Arrivals and the
  wizard reports `replace_song_done_move_incomplete` (OK only). It can then be graduated from the Starred screen
  as usual. Graduation's flat destination applies: the file lands at the top level of the Music folder, with
  " (1)" and so on on a name clash.

Removing a song from a playlist later never un-graduates it. `removeSongFromPlaylistInternal` touches
`playlist_songs` only, the file stays in the Music folder, and nothing sets the flag back. Removing it from its
last playlist before graduating just disables the row again.

## 6. A user with no New Arrivals folder

Onboarding calls New Arrivals optional (`onboarding_folder_setup_subtitle`). Without the folder, no scan stars
anything, so the Library tab's Starred row has no badge and the Starred screen shows its empty state
(`empty_no_starred`: "Nothing starred right now. Songs found in your New Arrivals folder show up here."). The
exception is an older library (section 2), whose legacy starred songs still show. Graduating one moves its
file from wherever it is in the Music folder to the folder's top level.

## 7. Where the info dialog lives

- The info action and its state are in the Starred route in `ui/BrowseRoutes.kt`: an `IconButton` in
  `DetailTopBar`'s actions (`Icons.Outlined.Info`, content description `cd_starred_info`), and
  `showStarredInfo` held in `rememberSaveable` so the open dialog survives a recreation.
- The dialog is `ui/screens/starred/StarredInfoDialog.kt`: `StarredInfoDialog` on `SynthXAlertDialog`, with
  `StarredInfoBody` (three paragraphs in a scrolling column) and `StarredInfoCloseButton` (Dialog-width Primary,
  `action_close`).
- Its strings are `starred_info_dialog_title` and `starred_info_dialog_message_arrivals`, `_graduate` and
  `_audience`. The middle paragraph quotes the menu row through `%1$s`, filled from
  `actions_remove_from_starred`.
- Tests: `StarredInfoInstrumentedTest` (androidTest) and `StarredInfoStringsTest` (JVM, the quote stays in sync).
