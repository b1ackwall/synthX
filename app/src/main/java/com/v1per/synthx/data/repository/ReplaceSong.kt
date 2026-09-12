package com.v1per.synthx.data.repository

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
) {
    val hasConflicts: Boolean get() = conflictingPlaylists.isNotEmpty()

    /** Every playlist the new song ends up in as a result of this operation. */
    val playlistsAffected: Int get() = transferringPlaylists.size + conflictingPlaylists.size
}

/** Terminal outcome of [SongRepository.replaceSong]. */
sealed class ReplaceSongResult {
    /**
     * The replacement landed. [oldAudioCleanup] preserves the complete post-commit cleanup result
     * so callers can distinguish confirmed file retention from an uncertain cleanup state.
     */
    data class Success(
        val playlistsAffected: Int,
        val oldAudioCleanup: SongDeletionOutcome,
    ) : ReplaceSongResult()

    /** Guardrail: a song cannot replace itself. Rejected before anything is written. */
    data object SameSong : ReplaceSongResult()

    /** The old song's row disappeared between selection and confirmation. Nothing was written. */
    data object OldSongMissing : ReplaceSongResult()

    /** The new song's row disappeared between selection and confirmation. Nothing was written. */
    data object NewSongMissing : ReplaceSongResult()
}
