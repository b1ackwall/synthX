package com.v1per.synthx.ui.components

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.data.scanner.DestructiveFilePreflightResult

/** Where the sheet was opened from — lets the sheet tailor itself per origin. */
enum class SongActionSource { Default, Player, Starred }

/** Opens the song actions sheet from anywhere inside [SongActionsHost]. */
fun interface SongActionsController {
    fun show(song: Song, currentPlaylistId: String?, source: SongActionSource)
}

/** Outcomes worth telling the user about, surfaced as a Snackbar by [SongActionsHost]. */
sealed class SongActionEvent {
    data class AlreadyInPlaylist(val playlistName: String) : SongActionEvent()
    data class AddedToPlaylist(val playlistName: String) : SongActionEvent()
    data class RemovedFromPlaylist(val playlistName: String) : SongActionEvent()
    data object RemovedFromStarred : SongActionEvent()

    /** A mutation threw (Room/SAF failure) — see [SongActionsViewModel.launchCatching] use. */
    data object ActionFailed : SongActionEvent()

    /**
     * The delete committed — row, references and art are all gone — but the audio file itself
     * could not be removed from disk. Distinct from both success (nothing to say) and
     * [ActionFailed] (nothing happened): the library change is real and irreversible, only the
     * on-disk cleanup is outstanding. See
     * [com.v1per.synthx.data.repository.SongDeletionOutcome].
     */
    data class DeletedButFileRemained(val filePath: String) : SongActionEvent()

    /** The library delete committed, but one or more physical cleanup steps could not be verified. */
    data class DeletedButCleanupUncertain(val filePath: String) : SongActionEvent()
    data object AudioCleanupRetrySucceeded : SongActionEvent()
    data class DeleteBlocked(val reason: DestructiveFilePreflightResult.Blocking) : SongActionEvent()
}

/**
 * SongRow appears on five screens, and the actions sheet needs navigation, a repository
 * and its own sheet state. Threading all of that through five call sites (and their
 * ViewModels) would mean the same five callbacks drilled through every screen that
 * happens to render a list of songs. Instead the sheet is hosted once, above the
 * NavHost, and rows reach it through composition.
 *
 * Defaults to a no-op so previews and tests can render SongRow without a host.
 */
val LocalSongActions: ProvidableCompositionLocal<SongActionsController> =
    compositionLocalOf { SongActionsController { _, _, _ -> } }
