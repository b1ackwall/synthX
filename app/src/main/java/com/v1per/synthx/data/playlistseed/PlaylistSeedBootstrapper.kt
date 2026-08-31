package com.v1per.synthx.data.playlistseed

import android.content.Context
import androidx.room.withTransaction
import com.v1per.synthx.data.db.PlaylistEntity
import com.v1per.synthx.data.db.PlaylistSongEntity
import com.v1per.synthx.data.db.SynthXDatabase
import com.v1per.synthx.data.backup.PlaylistExportManager
import com.v1per.synthx.data.backup.RestoreCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

sealed interface PlaylistSeedBootstrapResult {
    data class Complete(
        val playlistsCreated: Int,
        val membershipsRestored: Int,
        val totalMemberships: Int,
        val uniqueMissingSongs: Int,
    ) : PlaylistSeedBootstrapResult

    data object PlaylistsAlreadyExist : PlaylistSeedBootstrapResult
    data object InvalidManifest : PlaylistSeedBootstrapResult
    data object DatabaseFailure : PlaylistSeedBootstrapResult
}

/**
 * Temporary, developer-triggered bootstrap for a database whose playlist table is empty.
 * This deliberately has no merge, replacement, or synchronization behavior.
 */
class PlaylistSeedBootstrapper internal constructor(
    private val readManifestBytes: () -> ByteArray,
    private val database: PlaylistSeedDatabase,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
    // N14: seeding writes through its own DAO seam rather than SongRepositoryImpl, so nothing
    // else schedules an export of this data — without this, a fresh install's entire seeded
    // playlist structure has zero export coverage until some unrelated later mutation happens
    // to trigger one. Defaulted to a no-op so existing direct-constructor tests are unaffected.
    private val scheduleExport: () -> Unit = {},
) {

    suspend fun seed(): PlaylistSeedBootstrapResult = withContext(Dispatchers.IO) {
        val manifest = readAndValidateManifest()
            ?: return@withContext PlaylistSeedBootstrapResult.InvalidManifest

        val result = try {
            RestoreCoordinator.withDbOperation { database.inTransaction {
                if (getPlaylistCount() != 0) {
                    return@inTransaction PlaylistSeedBootstrapResult.PlaylistsAlreadyExist
                }

                val availableSongIds = getAllSongIds().toHashSet()
                val now = currentTimeMillis()
                val playlists = manifest.playlists.map { playlist ->
                    PlaylistEntity(
                        id = playlist.playlistId,
                        name = playlist.name,
                        description = null,
                        groupId = null,
                        position = 0,
                        createdAt = now,
                        updatedAt = now,
                        coverArtUri = null,
                    )
                }
                val memberships = buildList {
                    manifest.playlists.forEach { playlist ->
                        var restoredPosition = 0
                        playlist.songHashes.forEach { songHash ->
                            if (songHash in availableSongIds) {
                                add(
                                    PlaylistSongEntity(
                                        playlistId = playlist.playlistId,
                                        songId = songHash,
                                        position = restoredPosition++,
                                        addedAt = now,
                                    )
                                )
                            }
                        }
                    }
                }

                playlists.forEach { insertPlaylist(it) }
                memberships.forEach { insertPlaylistSong(it) }

                val allManifestSongHashes = manifest.playlists.flatMap { it.songHashes }
                PlaylistSeedBootstrapResult.Complete(
                    playlistsCreated = playlists.size,
                    membershipsRestored = memberships.size,
                    totalMemberships = allManifestSongHashes.size,
                    uniqueMissingSongs = allManifestSongHashes
                        .asSequence()
                        .filterNot { it in availableSongIds }
                        .toSet()
                        .size,
                )
            } }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            PlaylistSeedBootstrapResult.DatabaseFailure
        }

        if (result is PlaylistSeedBootstrapResult.Complete) {
            scheduleExport()
        }
        result
    }

    private fun readAndValidateManifest(): PlaylistSeedManifest? {
        val manifest = runCatching {
            Json.decodeFromString(
                PlaylistSeedManifest.serializer(),
                readManifestBytes().decodeToString(),
            )
        }.getOrNull() ?: return null

        return manifest.takeIf { PlaylistSeedManifestValidator.isValid(it) }
    }

    companion object {
        private const val ASSET_FILE_NAME = "playlist_seed_manifest_v1.json"

        fun create(context: Context): PlaylistSeedBootstrapper {
            val appContext = context.applicationContext
            val roomDatabase = SynthXDatabase.getInstance(appContext)
            // PlaylistExportManager.create() is a singleton (matching SongRepositoryImpl's own
            // instance), so this reuses the same debounced writer SongRepositoryImpl uses
            // rather than standing up a second, competing one.
            val playlistExportManager = PlaylistExportManager.create(appContext)
            return PlaylistSeedBootstrapper(
                readManifestBytes = {
                    appContext.assets.open(ASSET_FILE_NAME).use { input -> input.readBytes() }
                },
                database = RoomPlaylistSeedDatabase(roomDatabase),
                scheduleExport = playlistExportManager::scheduleExport,
            )
        }
    }
}

/** Small seam that keeps the transaction behavior testable without an Android test runtime. */
internal interface PlaylistSeedDatabase {
    suspend fun <T> inTransaction(block: suspend PlaylistSeedDatabase.() -> T): T
    suspend fun getPlaylistCount(): Int
    suspend fun getAllSongIds(): List<String>
    suspend fun insertPlaylist(playlist: PlaylistEntity)
    suspend fun insertPlaylistSong(playlistSong: PlaylistSongEntity)
}

private class RoomPlaylistSeedDatabase(
    private val database: SynthXDatabase,
) : PlaylistSeedDatabase {
    private val playlistDao = database.playlistDao()
    private val songDao = database.songDao()

    override suspend fun <T> inTransaction(
        block: suspend PlaylistSeedDatabase.() -> T,
    ): T = database.withTransaction {
        block(this@RoomPlaylistSeedDatabase)
    }

    override suspend fun getPlaylistCount(): Int = playlistDao.getPlaylistCount()

    override suspend fun getAllSongIds(): List<String> =
        songDao.getAllSongsOnce().map { it.id }

    override suspend fun insertPlaylist(playlist: PlaylistEntity) {
        playlistDao.insertPlaylist(playlist)
    }

    override suspend fun insertPlaylistSong(playlistSong: PlaylistSongEntity) {
        playlistDao.insertPlaylistSong(playlistSong)
    }
}
