package com.v1per.synthx.data.repository

import android.content.Context
import android.net.Uri
import com.v1per.synthx.data.scanner.FolderScanner
import com.v1per.synthx.preferences.PreferencesManager

class SongRepositoryImpl(
    private val context: Context,
    private val preferencesManager: PreferencesManager,
) : SongRepository {

    override suspend fun getSongs(): List<Song> {
        val uriString = preferencesManager.musicFolderUri ?: return emptyList()
        val folderUri = Uri.parse(uriString)
        return FolderScanner(context).scanFolder(folderUri)
    }
}
