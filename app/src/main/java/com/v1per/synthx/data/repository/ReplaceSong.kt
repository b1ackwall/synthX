package com.v1per.synthx.data.repository

import com.v1per.synthx.data.scanner.DestructiveFilePreflightResult
import com.v1per.synthx.data.scanner.ReplacementFilePreflightResult

/**
 * What to do about a playlist **both** songs already belong to, when one song is replacing the
 * other. A single global choice applied uniformly to every conflicting playlist, matching the
 * one "choose one" step the Replace Song screen offers — the repository never receives a
 * per-playlist map.
 *
 * Playlists only the old song belonged to are not conflicts and transfer unconditionally
 * regardless of which option is chosen; playlists only the *new* song belonged to are outside
 * the operation entirely and are never touched.
 */
enum class PlaylistConflictResolution {
    /**
     * The old song's slot wins: its position is kept and the new song's own row in that playlist
     * is replaced by one at that position.
     */
    KeepOld,

    /**
     * The new song's slot wins: the old song's row is simply dropped and the new song stays
     * exactly where it already was.
     */
    KeepNew,

    /**
     * Behaviourally identical to [KeepOld]. A `playlist_songs` row carries nothing but
     * `(playlist_id, song_id, position, added_at)`, and the composite primary key means the two
     * memberships must collapse into exactly one row — so once "which song" is settled (it is
     * always the new one) the only thing left to decide is which position survives, and there is
     * no third answer between "old's" and "new's" to merge towards. Kept as a distinct option
     * because it is a distinct *intent* the user expresses ("fold the two memberships together"
     * rather than "the old one wins"), and because a future schema that gives the row real
     * per-membership data would need it to diverge.
     */
    Merge,
}

/**
 * Read-only analysis of what [SongRepository.replaceSong] would do, computed fresh from the
 * database before the destructive confirm — the Replace Song screen's counterpart to restore's
 * dry-run preview.
 *
 * Playlist names, not ids: this exists to be read by a human on the review step.
 */
data class ReplaceSongPreview(
    val oldSong: Song,
    val newSong: Song,
    /** Playlists only the old song is in — these transfer unconditionally, position preserved. */
    val transferringPlaylists: List<String>,
    /** Playlists both songs are already in — the only ones a [PlaylistConflictResolution] affects. */
    val conflictingPlaylists: List<String>,
    /** Playlists only the new song is in. Listed for context; the replace never touches them. */
    val newSongOnlyPlaylists: List<String>,
    /**
     * The replacement's file is under the New Arrivals folder and it will be in at least one
     * playlist afterwards, so the replace ends by graduating it into the Music folder. Worked out
     * from the replacement's path against the configured folders each time a preview is computed.
     */
    val replacementMovesToMusic: Boolean = false,
) {
    val hasConflicts: Boolean get() = conflictingPlaylists.isNotEmpty()
}

/**
 * The database state a replacement review was computed from. Both sides are retained because a
 * change to either can create/remove a conflict. Position and added-at are part of the fingerprint:
 * they are the membership data [SongRepository.replaceSong] preserves when the old slot wins.
 */
data class ReplaceSongMembershipSnapshot(
    val oldSongMemberships: List<ReplaceSongMembershipFingerprint>,
    val newSongMemberships: List<ReplaceSongMembershipFingerprint>,
)

data class ReplaceSongMembershipFingerprint(val playlistId: String, val position: Int, val addedAt: Long)

/** Typed preview result so a vanished selection is not collapsed into a retryable null. */
sealed interface ReplaceSongPreviewResult {
    data class Ready(val preview: ReplaceSongPreview, val membershipSnapshot: ReplaceSongMembershipSnapshot) :
        ReplaceSongPreviewResult

    data object OldSongMissing : ReplaceSongPreviewResult
    data object NewSongMissing : ReplaceSongPreviewResult
}

/** What became of the old song's audio file once a replace committed. */
enum class OldAudioFile {
    /** Deleted, or confirmed absent. */
    Removed,

    /** The provider refused the delete; the file is still on disk. */
    StillPresent,

    /** The delete could not be confirmed either way. */
    Unconfirmed,
}

/** Whether a committed replace moved a New Arrivals replacement into the Music folder. */
enum class ReplacementMove {
    /** The replacement was already under the Music folder, or will not be in any playlist. */
    NotNeeded,

    /** Graduated: the file is in the Music folder and the song is no longer starred. */
    Moved,

    /** Graduation failed or could not run. The song stays starred, its file in New Arrivals. */
    Incomplete,
}

/** Progress of [SongRepository.replaceSong] that the Replace Song screen shows while it waits. */
enum class ReplaceSongStage {
    /** Graduating a New Arrivals replacement, after the replace itself has committed. */
    MovingReplacement,
}

/** Terminal outcome of [SongRepository.replaceSong]. */
sealed class ReplaceSongResult {
    /**
     * The replacement committed. Nothing after the commit can turn it into a failure: each later
     * step reports here instead. [oldAudioFile] is the old audio file's state only;
     * [followUpIncomplete] is true when any other after-commit step failed (the playback
     * notice, art, the destructive-action count, the cleanup record, the playlist export
     * request); [replacementMove] covers the New Arrivals graduation.
     */
    data class Success(
        val oldAudioFile: OldAudioFile,
        val followUpIncomplete: Boolean = false,
        val replacementMove: ReplacementMove = ReplacementMove.NotNeeded,
    ) : ReplaceSongResult()

    /** Membership state changed after review. No database writes were made. */
    data object PreviewStale : ReplaceSongResult()

    /** Guardrail: a song cannot replace itself. Rejected before anything is written. */
    data object SameSong : ReplaceSongResult()

    /** The old song's row disappeared between selection and confirmation. Nothing was written. */
    data object OldSongMissing : ReplaceSongResult()

    /** The new song's row disappeared between selection and confirmation. Nothing was written. */
    data object NewSongMissing : ReplaceSongResult()

    /** The old song's live audio document failed a definite destructive-safety check. */
    data class OldSongBlocked(val reason: DestructiveFilePreflightResult.Blocking) : ReplaceSongResult()

    /** The replacement row exists, but its live audio is definitely gone or has different content. */
    data class NewSongUnusable(val reason: ReplacementFilePreflightResult.Blocking) : ReplaceSongResult()

    /** The replacement's document could not be opened, or yielded no bytes. Nothing was written. */
    data object NewSongUnreadable : ReplaceSongResult()
}
