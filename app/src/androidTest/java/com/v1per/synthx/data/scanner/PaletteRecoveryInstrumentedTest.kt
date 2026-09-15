package com.v1per.synthx.data.scanner

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.data.db.SongEntity
import com.v1per.synthx.data.db.SynthXDatabase
import com.v1per.synthx.data.repository.toPaletteSwatches
import com.v1per.synthx.ui.components.ExtractedSwatchHsl
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PaletteRecoveryInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: SynthXDatabase
    private val swatches = listOf(
        ExtractedSwatchHsl(10f, 0.2f, 0.3f),
        ExtractedSwatchHsl(120f, 0.4f, 0.5f),
        ExtractedSwatchHsl(240f, 0.6f, 0.7f),
    )
    private fun song(id: String = "song") = SongEntity(
        id = id, title = "Title", albumId = null, duration = 1000L,
        filePath = "content://audio/$id", albumArtUri = "compressed/$id", year = 2026,
        dateAdded = 123L, albumArtFullUri = "full/$id", isStarred = false,
    )

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, SynthXDatabase::class.java).build()
    }
    @After fun tearDown() { database.close() }

    @Test fun daoCountAndIdsMatchRecoveryEligibility() = runBlocking<Unit> {
        val rows = listOf(
            song("compressed").copy(albumArtFullUri = null),
            song("full").copy(albumArtUri = null),
            song("no-art").copy(albumArtUri = null, albumArtFullUri = null),
            song("missing").copy(isMissing = true),
            PaletteRecovery.mergePaletteRecoveryResult(song("complete"), swatches),
            PaletteRecovery.mergePaletteRecoveryResult(song("partial-with-first-hue"), swatches)
                .copy(swatch3Lightness = null),
        )
        rows.forEach { database.songDao().upsertSong(it) }
        val expected = listOf("compressed", "full", "partial-with-first-hue")
        assertEquals(expected, PaletteRecovery.findMissingPalette(rows).map { it.id }.sorted())
        assertEquals(expected, database.songDao().getSongIdsMissingPalette())
        assertEquals(expected.size, database.songDao().getMissingPaletteCountOnce())
    }

    @Test fun recoveryPrefersFullArtAndPreservesConcurrentMetadataChanges() = runBlocking<Unit> {
        val dao = database.songDao()
        dao.upsertSong(song())
        val result = PaletteRecovery.recover(context, database, listOf("song", "song", "deleted"), extractSwatches = { uri ->
            assertEquals("full/song", uri)
            dao.upsertSong(song().copy(title = "Retagged", isStarred = true))
            swatches
        })
        val current = requireNotNull(dao.getSongById("song"))
        assertEquals(PaletteRecoveryResult(1, 1, 0), result)
        assertEquals("Retagged", current.title)
        assertTrue(current.isStarred)
        assertEquals(PaletteRecovery.mergePaletteRecoveryResult(song(), swatches).toPaletteSwatches(), current.toPaletteSwatches())
        assertEquals(0, dao.getMissingPaletteCountOnce())
        // A stale cached ID does not decode or overwrite an already populated row.
        PaletteRecovery.recover(context, database, listOf("song"), extractSwatches = { error("Already fixed") })
    }

    @Test fun observingCurrentSongPublishesPaletteAfterBackfillWithoutChangingItsId() = runBlocking<Unit> {
        database.songDao().upsertSong(song())
        val observed = Channel<List<com.v1per.synthx.data.repository.SongPaletteSwatch>?>(Channel.UNLIMITED)
        val collection = async {
            database.songDao().observeSongById("song").map { it?.toPaletteSwatches() }
                .take(2).collect { observed.send(it) }
        }
        withTimeout(5_000) {
            assertNull(observed.receive())
            PaletteRecovery.recover(context, database, listOf("song"), extractSwatches = { swatches })
            assertEquals(PaletteRecovery.mergePaletteRecoveryResult(song(), swatches).toPaletteSwatches(), observed.receive())
            collection.await()
        }
    }

    @Test fun recoveryDoesNotResurrectDeletedSongOrWriteColorsForReplacedArt() = runBlocking<Unit> {
        val dao = database.songDao()
        dao.upsertSong(song("deleted"))
        dao.upsertSong(song("changed"))
        val result = PaletteRecovery.recover(context, database, listOf("deleted", "changed"), extractSwatches = { uri ->
            if (uri == "full/deleted") dao.deleteSongById("deleted")
            else dao.upsertSong(song("changed").copy(albumArtFullUri = "replacement"))
            swatches
        })
        assertEquals(0, result.recovered)
        assertNull(dao.getSongById("deleted"))
        assertEquals(song("changed").copy(albumArtFullUri = "replacement"), dao.getSongById("changed"))
    }

    @Test fun failedDecodePreservesPartialDataAndCancellationPropagates() = runBlocking<Unit> {
        val partial = song().copy(swatch2Lightness = 0.8f)
        database.songDao().upsertSong(partial)
        val result = PaletteRecovery.recover(context, database, listOf("song"), extractSwatches = { emptyList() })
        assertEquals(0, result.recovered)
        assertEquals(partial, database.songDao().getSongById("song"))
        try {
            PaletteRecovery.recover(context, database, listOf("song"), extractSwatches = { throw CancellationException("cancel recovery") })
            fail("Cancellation must propagate")
        } catch (expected: CancellationException) {
            assertEquals("cancel recovery", expected.message)
        }
        assertEquals(partial, database.songDao().getSongById("song"))
    }

    @Test fun largeBatchUsesBoundedExtractionAndHandlesMoreThan999Ids() = runBlocking<Unit> {
        val ids = (0..1000).map { "song-$it" }
        database.withTransaction { ids.forEach { database.songDao().upsertSong(song(it)) } }
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val result = PaletteRecovery.recover(context, database, ids, extractSwatches = {
            val count = active.incrementAndGet()
            peak.updateAndGet { maxOf(it, count) }
            try { delay(1); swatches } finally { active.decrementAndGet() }
        })
        assertEquals(ids.size, result.recovered)
        assertTrue("Peak extraction concurrency: ${peak.get()}", peak.get() in 2..4)
        assertEquals(0, database.songDao().getMissingPaletteCountOnce())
    }

    @Test fun scanInsertWritesAllRawValuesAndRetagMoveAndReappearancePreserveThem() = runBlocking<Unit> {
        val ingestor = SongIngestor(context, database.songDao(), database.artistDao(), database.albumDao(), database.songArtistDao())
        val file = DocumentFile.fromFile(File(context.cacheDir, "palette-scan.mp3"))
        val metadata = ExtractedMetadata(
            readOk = true, title = "Scanned", artistNames = listOf("Artist"),
            primaryArtistNormalized = "artist", albumTitle = null, duration = 1000L,
            year = 2026, trackNumber = 1, albumArtUri = "compressed", albumArtFullUri = "full",
            swatch1Hue = 10f, swatch1Saturation = 0.2f, swatch1Lightness = 0.3f,
            swatch2Hue = 120f, swatch2Saturation = 0.4f, swatch2Lightness = 0.5f,
            swatch3Hue = 240f, swatch3Saturation = 0.6f, swatch3Lightness = 0.7f,
        )
        database.withTransaction {
            val inserted = ingestor.insertNewSong(file, "song", file.uri.toString(), false, metadata)
            val stored = requireNotNull(database.songDao().getSongById("song"))
            assertEquals(PaletteRecovery.mergePaletteRecoveryResult(song(), swatches).toPaletteSwatches(), stored.toPaletteSwatches())
            assertEquals(stored.toPaletteSwatches(), inserted.paletteSwatches)
            assertTrue(ingestor.retagIfChanged(file, stored, metadata.copy(title = "Retagged",
                swatch1Hue = null, swatch2Hue = null, swatch3Hue = null)))
            val retagged = requireNotNull(database.songDao().getSongById("song"))
            assertEquals(stored.toPaletteSwatches(), retagged.toPaletteSwatches())
            assertEquals(stored.albumArtUri, retagged.albumArtUri)
            assertEquals(stored.albumArtFullUri, retagged.albumArtFullUri)
            database.songDao().upsertSong(retagged.copy(filePath = "moved", isMissing = true, missingSince = 1L))
            database.songDao().markFound("song")
            assertEquals(stored.toPaletteSwatches(), database.songDao().getSongById("song")?.toPaletteSwatches())
        }
    }
}
