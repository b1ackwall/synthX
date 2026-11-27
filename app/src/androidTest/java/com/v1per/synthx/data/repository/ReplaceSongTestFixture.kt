package com.v1per.synthx.data.repository

import android.content.Context
import androidx.core.content.FileProvider
import androidx.room.Room
import com.v1per.synthx.data.AudioFileDeletion
import com.v1per.synthx.data.FileOperationCoordinator
import com.v1per.synthx.data.backup.BackupManager
import com.v1per.synthx.data.backup.BackupOutcomeStore
import com.v1per.synthx.data.backup.PlaylistExportManager
import com.v1per.synthx.data.backup.RestoreCoordinator
import com.v1per.synthx.data.backup.RestoreStartup
import com.v1per.synthx.data.db.PlaylistEntity
import com.v1per.synthx.data.db.PlaylistSongEntity
import com.v1per.synthx.data.db.SongEntity
import com.v1per.synthx.data.db.SynthXDatabase
import com.v1per.synthx.data.deleteSongAudioFile
import com.v1per.synthx.data.scanner.DestructiveFilePreflightResult
import com.v1per.synthx.data.scanner.FolderScanner
import com.v1per.synthx.data.scanner.KnownAudioRoot
import com.v1per.synthx.data.scanner.ReplacementFilePreflightResult
import com.v1per.synthx.preferences.DestructiveActionTracker
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * The real [SongRepositoryImpl.replaceSong] over in-memory Room, for the tests of what happens
 * after its transaction commits. Two songs: `old` (in playlist `p` at position 2, not starred)
 * and `new` (starred, in no playlist). Preferences and `filesDir` are isolated, as in
 * [RepositoryTestFixture].
 *
 * [document] gives a real file-backed content URI from the app's FileProvider. Its existence
 * query returns a row, it opens and reads, and its delete call succeeds, so the real
 * deleteSongAudioFile reports Deleted. FileProvider does not implement document deletion, so the
 * file itself stays until [close].
 */
internal class ReplaceSongTestFixture(base: Context) {
    val context = RepositoryTestFixture.IsolatedContext(base)

    init {
        RestoreStartup.await()
        RestoreCoordinator.resetForTest()
        FileOperationCoordinator.resetForTest()
        BackupOutcomeStore.resetForTest()
        DestructiveActionTracker.resetForTest(context)
    }

    val database: SynthXDatabase = Room.inMemoryDatabaseBuilder(context, SynthXDatabase::class.java).build()
    private val exports = PlaylistExportManager(
        context,
        database,
        database.playlistDao(),
        database.songDao(),
        database.artistDao(),
        database.albumDao(),
    )
    private val repositories = mutableListOf<SongRepositoryImpl>()
    private val documents = mutableListOf<File>()

    val songDao get() = database.songDao()
    val playlistDao get() = database.playlistDao()
    val pendingCleanupDao get() = database.pendingCleanupDao()

    /** A repository with every replace seam injectable; the defaults are the real file delete. */
    fun repository(
        audioFileDelete: (String) -> AudioFileDeletion = { path -> deleteSongAudioFile(context, path) },
        replacementReadable: suspend (String) -> Boolean = { true },
        replacementRoot: suspend (String) -> KnownAudioRoot? = { KnownAudioRoot.Music },
        graduateReplacement: suspend (String) -> Boolean = { error("No graduation expected") },
        schedulePlaylistExport: () -> Unit = {},
    ): SongRepositoryImpl = SongRepositoryImpl(
        context, database, database.songDao(), database.artistDao(), database.albumDao(),
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
        schedulePlaylistExport = schedulePlaylistExport,
        audioFileDelete = audioFileDelete,
        replacementReadable = replacementReadable,
        replacementRoot = replacementRoot,
        graduateReplacement = graduateReplacement,
    ).also { repositories += it }

    /** Seeds `old` and `new`; `old` is in playlist `p` unless [oldInPlaylist] is false. */
    suspend fun seed(oldPath: String = "test://old", newPath: String = "test://new", oldInPlaylist: Boolean = true) {
        songDao.upsertSong(song("old", oldPath, dateAdded = 111L, starred = false))
        songDao.upsertSong(song("new", newPath, dateAdded = 999L, starred = true))
        playlistDao.insertPlaylist(PlaylistEntity("p", "Playlist", null, null, 0, 1, 1, null, 120, 42L))
        if (oldInPlaylist) playlistDao.insertPlaylistSong(PlaylistSongEntity("p", "old", 2, 5L))
    }

    /** Previews and confirms replacing `old` with `new`, keeping the old song's place. */
    suspend fun replace(repository: SongRepositoryImpl, onStage: (ReplaceSongStage) -> Unit = {}): ReplaceSongResult =
        withTimeout(10_000) {
            val preview = repository.previewReplaceSong("old", "new") as ReplaceSongPreviewResult.Ready
            repository.replaceSong(
                "old",
                "new",
                PlaylistConflictResolution.KeepOld,
                preview.membershipSnapshot,
                onStage,
            )
        }

    /** A FileProvider content URI for a real file holding [bytes]. */
    fun document(bytes: ByteArray = ByteArray(8_192) { it.toByte() }): String {
        val file = providerFile().also { it.writeBytes(bytes) }
        return uriFor(file)
    }

    /** A FileProvider content URI whose file does not exist, so opening it fails. */
    fun missingDocument(): String = uriFor(providerFile())

    private fun providerFile(): File {
        val dir = File(context.baseContext.filesDir, "restore_incidents").apply { mkdirs() }
        return File(dir, "replace-test-${UUID.randomUUID()}.audio").also { documents += it }
    }

    private fun uriFor(file: File): String {
        val app = context.baseContext
        return FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file).toString()
    }

    fun close() {
        runBlocking { repositories.forEach { it.repositoryScope.coroutineContext[Job]!!.cancelAndJoin() } }
        documents.forEach(File::delete)
        database.close()
        // Before cleanUp(): resetting afterwards would recreate the isolated preference file.
        DestructiveActionTracker.resetForTest(context)
        context.cleanUp()
        RestoreCoordinator.resetForTest()
        FileOperationCoordinator.resetForTest()
        BackupOutcomeStore.resetForTest()
    }

    private fun song(id: String, path: String, dateAdded: Long, starred: Boolean) = SongEntity(
        id = id, title = id, albumId = null, duration = 1, filePath = path, albumArtUri = null,
        year = null, dateAdded = dateAdded, isStarred = starred,
    )
}
