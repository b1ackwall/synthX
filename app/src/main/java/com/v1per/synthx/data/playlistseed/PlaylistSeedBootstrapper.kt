package com.v1per.synthx.data.playlistseed

import android.content.Context
import androidx.room.withTransaction
import com.v1per.synthx.data.db.PlaylistEntity
import com.v1per.synthx.data.db.PlaylistSongEntity
import com.v1per.synthx.data.db.SynthXDatabase
import java.util.UUID
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
) {

    suspend fun seed(): PlaylistSeedBootstrapResult = withContext(Dispatchers.IO) {
        val manifest = readAndValidateManifest()
            ?: return@withContext PlaylistSeedBootstrapResult.InvalidManifest

        try {
            database.inTransaction {
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
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            PlaylistSeedBootstrapResult.DatabaseFailure
        }
    }

    private fun readAndValidateManifest(): PlaylistSeedManifest? {
        val manifest = runCatching {
            Json.decodeFromString(
                PlaylistSeedManifest.serializer(),
                readManifestBytes().decodeToString(),
            )
        }.getOrNull() ?: return null

        if (manifest.schemaVersion != PlaylistSeedManifestV1.SCHEMA_VERSION) return null
        if (manifest.hashStrategy != PlaylistSeedManifestV1.HASH_STRATEGY) return null

        val playlistIds = mutableSetOf<String>()
        for (playlist in manifest.playlists) {
            if (playlist.playlistId.isBlank()) return null
            if (!playlistIds.add(playlist.playlistId)) return null
            if (runCatching { UUID.fromString(playlist.playlistId) }.isFailure) return null
            if (playlist.name.isBlank()) return null
            if (playlist.songHashes.any { !SONG_HASH_REGEX.matches(it) }) return null
            if (playlist.songHashes.size != playlist.songHashes.toSet().size) return null
        }

        return manifest
    }

    companion object {
        private const val ASSET_FILE_NAME = "playlist_seed_manifest_v1.json"
        private val SONG_HASH_REGEX = Regex("^[0-9a-f]{64}$")

        fun create(context: Context): PlaylistSeedBootstrapper {
            val appContext = context.applicationContext
            val roomDatabase = SynthXDatabase.getInstance(appContext)
            return PlaylistSeedBootstrapper(
                readManifestBytes = {
                    appContext.assets.open(ASSET_FILE_NAME).use { input -> input.readBytes() }
                },
                database = RoomPlaylistSeedDatabase(roomDatabase),
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
