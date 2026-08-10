package com.v1per.synthx.data.repository

import android.net.Uri
import com.v1per.synthx.data.scanner.ScanResult
import kotlinx.coroutines.flow.Flow

interface SongRepository {
    fun getSongs(): Flow<List<Song>>
    fun getArtists(): Flow<List<Artist>>
    fun getSongsForArtist(artistId: String): Flow<List<Song>>
    fun getAlbums(): Flow<List<Album>>
    fun getSongsForAlbum(albumId: String): Flow<List<Song>>
    suspend fun scanLibrary(
        folderUri: Uri,
        onProgress: suspend (current: Int, total: Int) -> Unit = { _, _ -> },
    ): ScanResult

    /** Lightweight existence check over every stored song. Returns the count newly flagged missing. */
    suspend fun verifyLibrary(): Int

    // Issues — songs flagged is_missing
    fun getMissingSongs(): Flow<List<MissingSongInfo>>
    fun getMissingSongCount(): Flow<Int>

    /** Permanently deletes a song, its art file, and (via CASCADE) all rows referencing it. */
    suspend fun deleteSong(id: String)

    // Groups
    fun getAllGroups(): Flow<List<PlaylistGroup>>
    suspend fun createGroup(name: String)
    suspend fun renameGroup(id: String, name: String)
    suspend fun deleteGroup(id: String)

    // Playlists
    fun getAllPlaylists(): Flow<List<Playlist>>
    fun getUngroupedPlaylists(): Flow<List<Playlist>>
    fun getPlaylistsForGroup(groupId: String): Flow<List<Playlist>>
    suspend fun createPlaylist(name: String, groupId: String?)
    suspend fun renamePlaylist(id: String, name: String)
    suspend fun movePlaylistToGroup(id: String, groupId: String?)
    suspend fun deletePlaylist(id: String)

    // Playlist songs
    fun getSongsForPlaylist(playlistId: String): Flow<List<Song>>
    suspend fun getSongIdsForPlaylist(playlistId: String): List<String>
    suspend fun setPlaylistSongs(playlistId: String, songIds: List<String>)

    /**
     * Appends to the end of the playlist.
     * @return false when the song was already a member, in which case nothing changed.
     */
    suspend fun addSongToPlaylist(playlistId: String, songId: String): Boolean
    suspend fun removeSongFromPlaylist(playlistId: String, songId: String)

    // Playback queue — persisted so it survives a restart

    /** The saved queue, resolved to songs. Shorter than [getQueueSongIds] when songs went away. */
    suspend fun getQueueSongs(): List<Song>

    /** The saved queue as raw ids, including entries that no longer resolve to a playable song. */
    suspend fun getQueueSongIds(): List<String>

    /** Overwrites the saved queue with [songIds], in order. */
    suspend fun setQueueSongs(songIds: List<String>)

    // Baseline order — the app-managed source-of-truth sequence, persisted separately
    // from the live/active queue above so it can survive shuffle toggles.

    /** The saved baseline, resolved to songs. Shorter than [getBaselineQueueSongIds] when songs went away. */
    suspend fun getBaselineQueueSongs(): List<Song>

    /** The saved baseline as raw ids, including entries that no longer resolve to a playable song. */
    suspend fun getBaselineQueueSongIds(): List<String>

    /** Overwrites the saved baseline with [songIds], in order. */
    suspend fun setBaselineQueueSongs(songIds: List<String>)
}
