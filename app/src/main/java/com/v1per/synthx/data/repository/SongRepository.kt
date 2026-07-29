package com.v1per.synthx.data.repository

import android.net.Uri
import com.v1per.synthx.data.scanner.ScanResult
import kotlinx.coroutines.flow.Flow

interface SongRepository {
    fun getSongs(): Flow<List<Song>>
    fun getArtists(): Flow<List<Artist>>
    suspend fun getSongsForArtist(artistId: String): List<Song>
    suspend fun scanLibrary(folderUri: Uri): ScanResult
}
