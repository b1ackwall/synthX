package com.v1per.synthx.data.repository

import com.v1per.synthx.data.backup.BackupReason
import com.v1per.synthx.data.backup.BackupTier
import com.v1per.synthx.data.db.PlaylistEntity
import com.v1per.synthx.data.db.PlaylistGroupEntity
import androidx.room.withTransaction
import com.v1per.synthx.data.playlistart.selectDistancedHue
import com.v1per.synthx.preferences.DestructiveActionTracker
import java.util.UUID
import kotlin.random.Random
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// SongRepositoryImpl's playlist and playlist-group operations.

internal fun SongRepositoryImpl.getAllGroupsInternal(): Flow<List<PlaylistGroup>> =
    RepositoryDbFlow.observe { playlistDao.getAllGroups() }.map { entities ->
        entities.map { PlaylistGroup(id = it.id, name = it.name) }
    }

internal suspend fun SongRepositoryImpl.createGroupInternal(name: String) = dbOperation {
    playlistDao.insertGroup(
        PlaylistGroupEntity(
            id = UUID.randomUUID().toString(),
            name = name,
            position = 0,
            createdAt = System.currentTimeMillis(),
        )
    )
    playlistExportManager.scheduleExport()
}

internal suspend fun SongRepositoryImpl.renameGroupInternal(id: String, name: String) = dbOperation {
    playlistDao.renameGroup(id, name)
    playlistExportManager.scheduleExport()
}

internal suspend fun SongRepositoryImpl.deleteGroupInternal(id: String) = dbOperation {
    playlistDao.deleteGroup(id)
    playlistExportManager.scheduleExport()
}

internal fun SongRepositoryImpl.getUngroupedPlaylistsInternal(): Flow<List<Playlist>> =
    RepositoryDbFlow.observe { playlistDao.getUngroupedPlaylists() }.map { entities ->
        entities.map { it.toDomain() }
    }

internal fun SongRepositoryImpl.getPlaylistsForGroupInternal(groupId: String): Flow<List<Playlist>> =
    RepositoryDbFlow.observe { playlistDao.getPlaylistsForGroup(groupId) }.map { entities ->
        entities.map { it.toDomain() }
    }

internal suspend fun SongRepositoryImpl.createPlaylistInternal(name: String, groupId: String?): String = dbOperation {
    val now = System.currentTimeMillis()
    val id = UUID.randomUUID().toString()
    val structureSeed = Random.nextLong()
    // P8-T1/R1-F07: reading the existing hues and inserting the new row must be one
    // transaction — otherwise two concurrent creators (or a creator overlapping a
    // PlaylistArtBackfill pass) can both read the same used-hue snapshot and pick the same
    // "unused" hue before either write lands. Room/SQLite serializes writers at the
    // transaction level, so this alone is enough; it needs no visibility into a concurrent
    // backfill pass's in-flight choices (see PlaylistArtBackfill's own mutex for that half).
    database.withTransaction {
        // The playlist's generated cover art, decided once here and never again (see
        // PlaylistEntity.artHue). Done on the creation path rather than left to
        // PlaylistArtBackfill so a user-created playlist already has its art the first time
        // the list redraws, with no placeholder flash.
        val hue = selectDistancedHue(playlistDao.getExistingArtHues())
        playlistDao.insertPlaylist(
            PlaylistEntity(
                id = id,
                name = name,
                description = null,
                groupId = groupId,
                position = 0,
                createdAt = now,
                updatedAt = now,
                coverArtUri = null,
                artHue = hue,
                artStructureSeed = structureSeed,
            )
        )
    }
    playlistExportManager.scheduleExport()
    id
}

internal suspend fun SongRepositoryImpl.renamePlaylistInternal(id: String, name: String) = dbOperation {
    playlistDao.renamePlaylist(id, name, System.currentTimeMillis())
    playlistExportManager.scheduleExport()
}

internal suspend fun SongRepositoryImpl.movePlaylistToGroupInternal(id: String, groupId: String?) = dbOperation {
    playlistDao.movePlaylistToGroup(id, groupId, System.currentTimeMillis())
    playlistExportManager.scheduleExport()
}

// Hooked here rather than in the ViewModels because both PlaylistListViewModel and
// GroupDetailViewModel delete playlists through this one call.
internal suspend fun SongRepositoryImpl.deletePlaylistInternal(id: String) {
    // Backup waits for finite operations to drain, so it must precede our own permit.
    backupManager.createBackup(BackupTier.Auto, BackupReason.DELETE_PLAYLIST)
    // Recheck admission: restore may have begun while the backup was running.
    dbOperation {
        playlistDao.deletePlaylist(id)
        playlistExportManager.scheduleExport()
        DestructiveActionTracker.recordDestructiveAction(context)
    }
}

internal suspend fun SongRepositoryImpl.getPlaylistIdsForSongInternal(songId: String): List<String> = dbOperation {
    playlistDao.getPlaylistIdsForSong(songId)
}

internal suspend fun SongRepositoryImpl.addSongToPlaylistInternal(playlistId: String, songId: String): Boolean = dbOperation {
    // P7.6/N9/6.6: contains-check, max-position read, and insert live in one
    // @Transaction DAO method so concurrent adds to the same playlist can't race on
    // the next position — see PlaylistDao.addSongToPlaylist.
    val added = playlistDao.addSongToPlaylist(playlistId, songId, System.currentTimeMillis())
    if (added) {
        playlistExportManager.scheduleExport()
    }
    added
}

internal suspend fun SongRepositoryImpl.removeSongFromPlaylistInternal(playlistId: String, songId: String) = dbOperation {
    playlistDao.removePlaylistSong(playlistId, songId)
    playlistExportManager.scheduleExport()
}
