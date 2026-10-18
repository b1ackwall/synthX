package com.v1per.synthx.data.scanner

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.data.FileOperationCoordinator
import com.v1per.synthx.data.backup.PlaylistExportManager
import com.v1per.synthx.data.backup.RestoreCoordinator
import com.v1per.synthx.data.db.SongEntity
import com.v1per.synthx.data.db.SynthXDatabase
import com.v1per.synthx.ui.testing.TestTimeouts
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScannerDegradationInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER) val testTimeout = TestTimeouts.device()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: SynthXDatabase
    private lateinit var root: File

    @Before fun setUp() {
        RestoreCoordinator.resetForTest()
        FileOperationCoordinator.resetForTest()
        database = Room.inMemoryDatabaseBuilder(context, SynthXDatabase::class.java).build()
        root = File(context.cacheDir, "scanner-degradation-${System.nanoTime()}").also { it.mkdirs() }
    }

    @After fun tearDown() {
        database.close()
        root.deleteRecursively()
        RestoreCoordinator.resetForTest()
        FileOperationCoordinator.resetForTest()
    }

    @Test fun incompleteNestedWalkDoesNotMarkMissingAndStillIngestsFoundFile() = runBlocking {
        val found = File(root, "Found.mp3").also { it.writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val broken = File(root, "Broken").also { it.mkdirs() }
        val missingPath = DocumentFile.fromFile(File(root, "StillMaybeThere.mp3")).uri.toString()
        database.songDao().upsertSong(song("existing", missingPath))

        val brokenUri = DocumentFile.fromFile(broken).uri
        val scanner = scanner(
            directoryQuery = DirectoryChildrenQuery { directory ->
                if (directory.uri == brokenUri) {
                    DirectoryListing(emptyList(), complete = false)
                } else {
                    DirectoryListing(
                        directory.listFiles().map { child ->
                            DirectoryChild(child, child.name, child.isDirectory)
                        },
                        complete = true,
                    )
                }
            },
        )

        val result = scanner.scan(DocumentFile.fromFile(root).uri)

        assertFalse(result.missingReconciled)
        assertEquals(
            setOf(ScanDegradation(KnownAudioRoot.Music, ScanDegradationReason.WalkIncomplete)),
            result.degradations,
        )
        assertEquals(1, result.added)
        assertFalse(database.songDao().getSongById("existing")!!.isMissing)
        assertNotNull(database.songDao().getSongByPath(DocumentFile.fromFile(found).uri.toString()))
    }

    @Test fun verificationMarksOnlyConfirmedAbsenceAndReportsUncertainty() = runBlocking {
        val rootUri = "content://scanner.test/tree/music".toUri()
        val absentUri = "content://scanner.test/tree/music/document/absent".toUri()
        val uncertainUri = "content://scanner.test/tree/music/document/uncertain".toUri()
        database.songDao().upsertSong(song("absent", absentUri.toString()))
        database.songDao().upsertSong(song("uncertain", uncertainUri.toString()))

        val scanner = scanner(
            existenceProbe = DocumentExistenceProbe { uri ->
                if (uri == absentUri) DocumentExistence.ConfirmedAbsent else DocumentExistence.Unknown
            },
            rootMembership = { _, _ -> true },
        )
        val result = scanner.verify(
            listOf(ConfiguredAudioRoot(rootUri, KnownAudioRoot.Music))
        )

        assertEquals(1, result.newlyMissing)
        assertTrue(database.songDao().getSongById("absent")!!.isMissing)
        assertFalse(database.songDao().getSongById("uncertain")!!.isMissing)
        assertEquals(
            setOf(
                ScanDegradation(KnownAudioRoot.Music, ScanDegradationReason.VerificationIncomplete)
            ),
            result.degradations,
        )
    }

    private fun scanner(
        directoryQuery: DirectoryChildrenQuery = DirectoryChildrenQuery {
            DirectoryListing(emptyList(), complete = true)
        },
        existenceProbe: DocumentExistenceProbe = DocumentExistenceProbe {
            DocumentExistence.Present
        },
        rootMembership: suspend (Uri, Uri) -> Boolean = { _, _ -> true },
    ): FolderScanner {
        val exports = PlaylistExportManager(
            context,
            database,
            database.playlistDao(),
            database.songDao(),
            database.artistDao(),
            database.albumDao(),
        )
        return FolderScanner(
            context = context,
            database = database,
            songDao = database.songDao(),
            artistDao = database.artistDao(),
            albumDao = database.albumDao(),
            songArtistDao = database.songArtistDao(),
            playlistExportManager = exports,
            rootResolver = { DocumentFile.fromFile(root) },
            directoryQuery = directoryQuery,
            documentExistenceProbe = existenceProbe,
            rootMembership = rootMembership,
        )
    }

    private fun song(id: String, path: String) = SongEntity(
        id = id,
        title = id,
        albumId = null,
        duration = 1,
        filePath = path,
        albumArtUri = null,
        year = null,
        dateAdded = 1,
    )
}
