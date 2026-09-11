package com.v1per.synthx.data.repository

import androidx.room.ColumnInfo

/**
 * One `playlist_songs` row, minus the song id the query was scoped by — the full membership,
 * not just the playlist it names.
 *
 * Exists alongside [com.v1per.synthx.data.db.PlaylistDao.getPlaylistIdsForSong]'s bare-id list
 * rather than replacing it: the Add-to-Playlist indicator only asks "is it a member?", while
 * Replace Song has to move a song into another song's *exact* slot, which needs the position it
 * held. [addedAt] rides along for the same reason — the replacement steps into the old song's
 * membership wholesale, so it inherits when that membership was created rather than being
 * stamped as a brand new add.
 */
data class PlaylistMembership(
    @ColumnInfo(name = "playlist_id") val playlistId: String,
    val position: Int,
    @ColumnInfo(name = "added_at") val addedAt: Long,
)
