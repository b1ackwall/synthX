package com.v1per.synthx.data.repository

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.room.Room
import com.v1per.synthx.data.FileOperationCoordinator
import com.v1per.synthx.data.backup.BackupManager
import com.v1per.synthx.data.backup.BackupOutcomeStore
import com.v1per.synthx.data.backup.PlaylistExportManager
import com.v1per.synthx.data.backup.RestoreCoordinator
import com.v1per.synthx.data.backup.RestoreStartup
import com.v1per.synthx.data.db.AlbumEntity
import com.v1per.synthx.data.db.ArtistEntity
import com.v1per.synthx.data.db.PlaylistEntity
import com.v1per.synthx.data.db.PlaylistGroupEntity
import com.v1per.synthx.data.db.SongArtistEntity
import com.v1per.synthx.data.db.SongDao
import com.v1per.synthx.data.db.SongEntity
import com.v1per.synthx.data.db.SynthXDatabase
import com.v1per.synthx.data.scanner.DestructiveFilePreflightResult
import com.v1per.synthx.data.scanner.FolderScanner
import com.v1per.synthx.data.scanner.ReplacementFilePreflightResult
import com.v1per.synthx.preferences.DestructiveActionTracker
import com.v1per.synthx.preferences.PreferencesManager
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.fail

/**
 * The real [SongRepositoryImpl] over in-memory Room, built the way `create` builds it.
 *
 * Preferences and `filesDir` go to a per-fixture copy, so backups, export outcomes and the
 * destructive-action counter never touch the installed debug app's own data.
 */
internal class RepositoryTestFixture(base: Context) {
    val context = IsolatedContext(base)

    init {
        RestoreStartup.await()
        RestoreCoordinator.resetForTest()
        FileOperationCoordinator.resetForTest()
        BackupOutcomeStore.resetForTest()
        DestructiveActionTracker.resetForTest(context)
    }

    val database: SynthXDatabase =
        Room.inMemoryDatabaseBuilder(context, SynthXDatabase::class.java).build()
    val exports = PlaylistExportManager(
        context,
        database,
        database.playlistDao(),
        database.songDao(),
        database.artistDao(),
        database.albumDao(),
    )
    private val repositories = mutableListOf<SongRepositoryImpl>()
    val repository = repositoryWith()

    /** Another real repository on the same database; [songDao] can wrap the real one. */
    fun repositoryWith(songDao: SongDao = database.songDao()) = SongRepositoryImpl(
        context, database, songDao, database.artistDao(), database.albumDao(),
        database.playlistDao(), database.queueDao(), database.pendingCleanupDao(),
        FolderScanner(
            context,
            database,
            database.songDao(),
            database.artistDao(),
            database.albumDao(),
            database.songArtistDao(),
            exports,
        ),
        BackupManager(context), exports,
        destructiveFileCheck = { _, _ -> DestructiveFilePreflightResult.Ambiguous },
        replacementFileCheck = { _, _ -> ReplacementFilePreflightResult.Ambiguous },
    ).also { repositories += it }

    val songDao get() = database.songDao()
    val playlistDao get() = database.playlistDao()
    val queueDao get() = database.queueDao()

    /** Makes every export request run for real; the folder is unreachable, so it records a failure. */
    fun pointExportsAtUnreachableFolder() {
        PreferencesManager(context).backupFolderUri = UNREACHABLE_FOLDER
    }

    suspend fun album(id: String, title: String = id) {
        database.albumDao().upsertAlbum(AlbumEntity(id, title, year = null, albumArtUri = null))
    }

    suspend fun artist(id: String, name: String = id) {
        database.artistDao().upsertArtist(ArtistEntity(id, name, name.trim().lowercase()))
    }

    /** One song; [artistIds] are linked in the given order (position 0 is the main artist). */
    suspend fun song(
        id: String,
        title: String = id,
        albumId: String? = null,
        dateAdded: Long = 1,
        trackNumber: Int? = null,
        missing: Boolean = false,
        starred: Boolean = true,
        artistIds: List<String> = emptyList(),
    ) {
        songDao.upsertSong(
            SongEntity(
                id = id, title = title, albumId = albumId, duration = 1_000,
                filePath = "test://$id", albumArtUri = null, year = null,
                trackNumber = trackNumber, dateAdded = dateAdded, isMissing = missing,
                missingSince = if (missing) 1 else null, isStarred = starred,
            ),
        )
        artistIds.forEachIndexed { position, artistId ->
            database.songArtistDao().insertSongArtist(SongArtistEntity(id, artistId, position))
        }
    }

    /** A playlist written straight to Room; songs are appended in the given order. */
    suspend fun playlist(id: String, name: String = id, groupId: String? = null, vararg songIds: String) {
        playlistDao.insertPlaylist(
            PlaylistEntity(id, name, null, groupId, 0, 1, 1, null, artHue = 0, artStructureSeed = 0L),
        )
        songIds.forEachIndexed { index, songId ->
            playlistDao.addSongToPlaylist(id, songId, addedAt = index.toLong())
        }
    }

    suspend fun group(id: String, name: String = id) {
        playlistDao.insertGroup(PlaylistGroupEntity(id, name, position = 0, createdAt = 1))
    }

    fun close() {
        // The cached song, album, artist and playlist flows hold a reactive permit until stopped.
        runBlocking { repositories.forEach { it.repositoryScope.coroutineContext[Job]!!.cancelAndJoin() } }
        database.close()
        // Before cleanUp(): resetting afterwards would recreate the isolated preference file.
        DestructiveActionTracker.resetForTest(context)
        context.cleanUp()
        RestoreCoordinator.resetForTest()
        FileOperationCoordinator.resetForTest()
        BackupOutcomeStore.resetForTest()
    }

    /** Keeps preference and file writes separate from the installed app's library. */
    class IsolatedContext(base: Context) : ContextWrapper(base) {
        private val prefix = "repository-test-${UUID.randomUUID()}"
        private val preferenceNames = mutableSetOf<String>()
        private val testFiles = File(base.cacheDir, prefix).also { it.mkdirs() }

        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = testFiles

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolatedName = "$prefix-$name"
            preferenceNames += isolatedName
            return super.getSharedPreferences(isolatedName, mode)
        }

        fun cleanUp() {
            preferenceNames.forEach { baseContext.deleteSharedPreferences(it) }
            check(testFiles.parentFile == baseContext.cacheDir)
            testFiles.deleteRecursively()
        }
    }

    companion object {
        /** Not a tree URI, so an export attempt fails fast without touching any storage. */
        const val UNREACHABLE_FOLDER = "content://com.v1per.synthx.test.nowhere/export"
    }
}

/** Waits for [flow] to emit a value matching [done]; fails with the last value seen. */
internal suspend fun <T> awaitEmission(flow: Flow<T>, what: String, timeoutMs: Long = 5_000, done: (T) -> Boolean): T {
    var last: Any? = "nothing emitted"
    val match = withTimeoutOrNull(timeoutMs) {
        flow.first { value ->
            last = value
            done(value)
        }
    }
    if (match == null) fail("$what: no matching emission within $timeoutMs ms; last value: $last")
    return match!!
}
