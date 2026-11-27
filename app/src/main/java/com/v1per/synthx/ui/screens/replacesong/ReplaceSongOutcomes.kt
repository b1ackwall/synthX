package com.v1per.synthx.ui.screens.replacesong

import com.v1per.synthx.R
import com.v1per.synthx.data.repository.AudioFileRetryOutcome
import com.v1per.synthx.data.repository.OldAudioFile
import com.v1per.synthx.data.repository.ReplaceSongResult
import com.v1per.synthx.data.repository.ReplacementMove
import com.v1per.synthx.data.repository.Song

/** Terminal state of the replace itself, once confirmed. */
sealed class ReplaceSongOutcome {
    data object InProgress : ReplaceSongOutcome()

    /**
     * The replacement committed; nothing after the commit is reported as a failure.
     *
     * With no [warning], the screen leaves the wizard at once and the shell shows [exitMessage]
     * (the "Replacement complete." confirmation when null) as a snackbar on the screen the user
     * lands on. A [warning] is something the user has no other way to find out: [fileWarning]
     * for the old audio file, [otherWarnings] for the move into the Music folder or a follow-up
     * step. It stays in a dialog until acknowledged, and [retryTarget] adds Retry while the old
     * audio file may still be on the device.
     */
    data class Complete(
        val fileWarning: String? = null,
        val otherWarnings: List<String> = emptyList(),
        val retryTarget: AudioCleanupRetryTarget? = null,
        val exitMessage: String? = null,
    ) : ReplaceSongOutcome() {
        val warning: String? get() = (listOfNotNull(fileWarning) + otherWarnings).takeIf { it.isNotEmpty() }
            ?.joinToString(separator = "\n\n")
    }

    data class Failed(val message: String) : ReplaceSongOutcome()
}

/** The old song whose audio file Retry deletes, and where that file was. */
data class AudioCleanupRetryTarget(val songId: String, val filePath: String)

/**
 * Turns a committed replace into its [ReplaceSongOutcome.Complete]. The old file's state comes
 * first; the generic follow-up warning is shown only when nothing more specific is.
 */
internal fun completedReplaceOutcome(
    result: ReplaceSongResult.Success,
    oldSong: Song,
    stringResource: (Int) -> String,
): ReplaceSongOutcome.Complete {
    val fileWarning = when (result.oldAudioFile) {
        OldAudioFile.Removed -> null
        OldAudioFile.StillPresent -> stringResource(R.string.replace_song_done_cleanup_pending)
        OldAudioFile.Unconfirmed -> stringResource(R.string.replace_song_done_cleanup_uncertain)
    }
    val moveWarning = when (result.replacementMove) {
        ReplacementMove.NotNeeded, ReplacementMove.Moved -> null
        ReplacementMove.Incomplete -> stringResource(R.string.replace_song_done_move_incomplete)
    }
    val followUpWarning = stringResource(R.string.replace_song_done_housekeeping)
        .takeIf { result.followUpIncomplete && fileWarning == null && moveWarning == null }
    return ReplaceSongOutcome.Complete(
        fileWarning = fileWarning,
        otherWarnings = listOfNotNull(moveWarning, followUpWarning),
        retryTarget = AudioCleanupRetryTarget(oldSong.id, oldSong.filePath).takeIf { fileWarning != null },
    )
}

/**
 * The outcome after Retry: only the old file's part changes, and any other warning from the
 * replace is kept. A file now owned by a library song is confirmed with its own message.
 */
internal fun retriedCleanupOutcome(
    retry: AudioFileRetryOutcome,
    previous: ReplaceSongOutcome.Complete,
    target: AudioCleanupRetryTarget,
    stringResource: (Int) -> String,
): ReplaceSongOutcome.Complete = when (retry) {
    AudioFileRetryOutcome.Deleted -> previous.copy(fileWarning = null, retryTarget = null)

    AudioFileRetryOutcome.FileRemained -> previous.copy(
        fileWarning = stringResource(R.string.replace_song_done_cleanup_pending),
        retryTarget = target,
    )

    AudioFileRetryOutcome.CleanupUncertain -> previous.copy(
        fileWarning = stringResource(R.string.replace_song_done_cleanup_uncertain),
        retryTarget = target,
    )

    AudioFileRetryOutcome.FileInLibrary -> previous.copy(
        fileWarning = null,
        retryTarget = null,
        exitMessage = stringResource(R.string.audio_cleanup_retry_file_in_library),
    )
}
