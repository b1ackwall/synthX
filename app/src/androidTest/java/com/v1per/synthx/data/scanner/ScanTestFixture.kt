package com.v1per.synthx.data.scanner

import android.content.Context
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.room.Room
import com.v1per.synthx.data.FileOperationCoordinator
import com.v1per.synthx.data.SongRemap
import com.v1per.synthx.data.SongRemapEvents
import com.v1per.synthx.data.backup.PlaylistExportManager
import com.v1per.synthx.data.backup.RestoreCoordinator
import com.v1per.synthx.data.db.PlaylistEntity
import com.v1per.synthx.data.db.SongEntity
import com.v1per.synthx.data.db.SynthXDatabase
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * A real [FolderScanner] over in-memory Room and plain files in `cacheDir`.
 *
 * Roots are ordinary directories. The scanner reaches them through its existing constructor
 * seams: [FolderScanner.rootResolver] maps a root's `file://` URI back to the directory, and the
 * directory query lists children sorted by name so walk order is fixed.
 */
internal class ScanTestFixture(private val context: Context) {
    val database: SynthXDatabase =
        Room.inMemoryDatabaseBuilder(context, SynthXDatabase::class.java).build()
    private val base = File(context.cacheDir, "scan-fixture-${System.nanoTime()}").also { it.mkdirs() }
    private val roots = mutableMapOf<String, File>()

    val songDao get() = database.songDao()
    val playlistDao get() = database.playlistDao()

    init {
        RestoreCoordinator.resetForTest()
        FileOperationCoordinator.resetForTest()
    }

    fun root(name: String): File = File(base, name).also {
        it.mkdirs()
        roots[uriOf(it)] = it
    }

    fun scanner(): FolderScanner = FolderScanner(
        context = context,
        database = database,
        songDao = database.songDao(),
        artistDao = database.artistDao(),
        albumDao = database.albumDao(),
        songArtistDao = database.songArtistDao(),
        playlistExportManager = PlaylistExportManager(
            context,
            database,
            database.playlistDao(),
            database.songDao(),
            database.artistDao(),
            database.albumDao(),
        ),
        rootResolver = { uri -> roots[uri.toString()]?.let(DocumentFile::fromFile) },
        directoryQuery = DirectoryChildrenQuery { directory ->
            val dir = File(requireNotNull(directory.uri.path))
            DirectoryListing(
                dir.listFiles().orEmpty().sortedBy { it.name }.map { child ->
                    DirectoryChild(DocumentFile.fromFile(child), child.name, child.isDirectory)
                },
                complete = true,
            )
        },
    )

    /** Scans [root] the way the Music worker does, unless told it is New Arrivals. */
    suspend fun scan(
        root: File,
        newArrivals: Boolean = false,
        otherRoots: List<File> = emptyList(),
    ): ScanResult = scanner().scan(
        folderUri = uriOf(root).toUri(),
        reconcileMissing = !newArrivals,
        otherRootUris = otherRoots.map { uriOf(it).toUri() },
        isNewArrivalsRoot = newArrivals,
    )

    suspend fun songAt(file: File): SongEntity? = songDao.getSongByPath(uriOf(file))

    suspend fun requireSongAt(file: File): SongEntity =
        requireNotNull(songAt(file)) { "No song row for ${file.name}; rows: ${songDao.getAllSongsOnce()}" }

    suspend fun addPlaylist(id: String, vararg songIds: String) {
        playlistDao.insertPlaylist(
            PlaylistEntity(
                id = id,
                name = id,
                description = null,
                groupId = null,
                position = 0,
                createdAt = 1,
                updatedAt = 1,
                coverArtUri = null,
            )
        )
        songIds.forEachIndexed { index, songId ->
            playlistDao.addSongToPlaylist(id, songId, addedAt = index.toLong())
        }
    }

    /** Starts collecting remap notices before a scan; read the list after it returns. */
    fun recordRemaps(scope: CoroutineScope): MutableList<SongRemap> {
        val seen = java.util.Collections.synchronizedList(mutableListOf<SongRemap>())
        scope.launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            SongRemapEvents.events.collect { seen += it }
        }
        return seen
    }

    fun close() {
        database.close()
        base.deleteRecursively()
        RestoreCoordinator.resetForTest()
        FileOperationCoordinator.resetForTest()
    }

    companion object {
        fun uriOf(file: File): String = DocumentFile.fromFile(file).uri.toString()

        /** Deterministic bytes: the same [seed] and [size] always give the same content. */
        fun bytes(size: Int, seed: Int): ByteArray = Random(seed).nextBytes(size)

        fun write(dir: File, name: String, content: ByteArray): File =
            File(dir, name).also {
                it.parentFile?.mkdirs()
                it.writeBytes(content)
            }
    }
}
