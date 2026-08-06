package com.v1per.synthx.data.repository

import android.net.Uri
import com.v1per.synthx.data.scanner.ScanResult
import kotlinx.coroutines.flow.Flow

interface SongRepository {
    fun getSongs(): Flow<List<Song>>
    fun getArtists(): Flow<List<Artist>>
    suspend fun getSongsForArtist(artistId: String): List<Song>
    fun getAlbums(): Flow<List<Album>>
    suspend fun getSongsForAlbum(albumId: String): List<Song>
    suspend fun scanLibrary(
        folderUri: Uri,
        onProgress: suspend (current: Int, total: Int) -> Unit = { _, _ -> },
    ): ScanResult

    /** Lightweight existence check over every stored song. Returns the count newly flagged missing. */
    suspend fun verifyLibrary(): Int

    // Issues — songs flagged is_missing
    fun getMissingSongs(): Flow<List<MissingSongInfo>>
    fun getMissingSongCount(): Flow<Int>

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
}
