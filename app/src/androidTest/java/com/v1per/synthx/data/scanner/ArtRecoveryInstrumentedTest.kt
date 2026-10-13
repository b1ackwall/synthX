package com.v1per.synthx.data.scanner

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.data.db.SongEntity
import com.v1per.synthx.data.db.SynthXDatabase
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Race coverage for [ArtRecovery.recoverOneSong]'s transactional re-read-merge-write (R1-F01):
 * a concurrent write is injected via the extraction seam ([ArtRecovery.recover]'s `extractArt`
 * parameter) so it lands exactly between ArtRecovery's pre-transaction read (for extraction)
 * and its DB write.
 */
@RunWith(AndroidJUnit4::class)
class ArtRecoveryInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: SynthXDatabase
    private lateinit var artDir: File

    private fun song(id: String = "song") = SongEntity(
        id = id, title = "Title", albumId = null, duration = 1000L,
        filePath = "content://audio/$id", albumArtUri = "compressed/$id", year = 2026,
        dateAdded = 123L, albumArtFullUri = "full/$id", isStarred = false,
    )

    /** Writes real `art_<id>.jpg` / `art_full_<id>.jpg` files so disk-based existence checks
     *  (both the candidate-selection pass and the post-write "still missing" check) see this
     *  song's art as actually present, exactly as a real [AlbumArtExtractor.extract] would leave
     *  behind. */
    private fun writeArtFiles(id: String, compressed: Boolean = true, fullRes: Boolean = true) {
        if (compressed) File(artDir, "art_$id.jpg").writeBytes(byteArrayOf(1))
        if (fullRes) File(artDir, "art_full_$id.jpg").writeBytes(byteArrayOf(1))
    }

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, SynthXDatabase::class.java).build()
        artDir = File(context.filesDir, "art").apply { mkdirs() }
        artDir.listFiles()?.forEach { it.delete() }
    }

    @After fun tearDown() {
        database.close()
        artDir.listFiles()?.forEach { it.delete() }
    }

    @Test fun recoveryPreservesConcurrentMetadataChanges() = runBlocking<Unit> {
        val dao = database.songDao()
        dao.upsertSong(song())
        val result = ArtRecovery.recover(context, database, listOf("song"), extractArt = { _, requested ->
            assertEquals("song", requested.id)
            // Lands between the read used to drive extraction and ArtRecovery's DB write.
            dao.upsertSong(song().copy(title = "Retagged", isStarred = true))
            writeArtFiles("song")
            "new-compressed" to "new-full"
        })
        val current = requireNotNull(dao.getSongById("song"))
        assertEquals(ArtRecoveryResult(checked = 1, recovered = 1, skipped = 0), result)
        assertEquals("Retagged", current.title)
        assertTrue(current.isStarred)
        assertEquals("new-compressed", current.albumArtUri)
        assertEquals("new-full", current.albumArtFullUri)
    }

    @Test fun recoveryDoesNotResurrectADeletedSong() = runBlocking<Unit> {
        val dao = database.songDao()
        dao.upsertSong(song("deleted"))
        val result = ArtRecovery.recover(context, database, listOf("deleted"), extractArt = { _, _ ->
            dao.deleteSongById("deleted")
            writeArtFiles("deleted")
            "new-compressed" to "new-full"
        })
        assertEquals(0, result.recovered)
        assertNull(dao.getSongById("deleted"))
    }

    @Test fun recoveryDropsWriteWhenSourceFilePathChangedConcurrently() = runBlocking<Unit> {
        val dao = database.songDao()
        dao.upsertSong(song("moved"))
        val result = ArtRecovery.recover(context, database, listOf("moved"), extractArt = { _, _ ->
            // Simulates a concurrent graduation/rename repointing the row at a different physical
            // file between this extraction (keyed to the OLD path) and ArtRecovery's write — the
            // extracted art no longer corresponds to what the row now points at.
            dao.upsertSong(song("moved").copy(filePath = "content://audio/moved-elsewhere"))
            "new-compressed" to "new-full"
        })
        assertEquals(0, result.recovered)
        val current = requireNotNull(dao.getSongById("moved"))
        assertEquals("content://audio/moved-elsewhere", current.filePath)
        // The stale extraction result was never written onto the row.
        assertEquals("compressed/moved", current.albumArtUri)
        assertEquals("full/moved", current.albumArtFullUri)
    }

    @Test fun successfulRecoveryClearsFromMissingArtSet() = runBlocking<Unit> {
        val dao = database.songDao()
        dao.upsertSong(song())
        assertTrue(ArtRecovery.findMissingArt(context, listOf(requireNotNull(dao.getSongById("song")))).isNotEmpty())
        ArtRecovery.recover(context, database, listOf("song"), extractArt = { _, _ ->
            writeArtFiles("song")
            "new-compressed" to "new-full"
        })
        assertFalse(ArtRecovery.findMissingArt(context, listOf(requireNotNull(dao.getSongById("song")))).isNotEmpty())
    }
}
