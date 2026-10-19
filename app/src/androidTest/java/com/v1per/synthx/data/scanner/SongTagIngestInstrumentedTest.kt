package com.v1per.synthx.data.scanner

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.data.scanner.ScanAudioFixtures.mp3
import com.v1per.synthx.data.scanner.ScanTestFixture.Companion.write
import com.v1per.synthx.ui.testing.TestTimeouts
import java.io.File
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tags read from real (generated) MP3 and FLAC files through a real scan: title, album, year,
 * track, and the comma-only artist split. Also the "same file, edited tags" retag path.
 */
@RunWith(AndroidJUnit4::class)
class SongTagIngestInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.device()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var fx: ScanTestFixture
    private lateinit var music: File

    @Before fun setUp() {
        fx = ScanTestFixture(context)
        music = fx.root("music")
    }

    @After fun tearDown() = fx.close()

    @Test fun mp3TagsBecomeTitleAlbumYearAndTrack() = runBlocking {
        val file = write(
            music,
            "file-name.mp3",
            mp3(
                mapOf(
                    "TIT2" to "Real Title",
                    "TPE1" to "Solo",
                    "TALB" to "The Album",
                    "TYER" to "1999",
                    "TRCK" to "5/12",
                ),
            ),
        )

        assertEquals(1, fx.scan(music).added)

        val song = fx.requireSongAt(file)
        assertEquals("Real Title", song.title)
        assertEquals(5, song.trackNumber)
        assertEquals(1999, song.year)
        assertEquals("The Album", fx.database.albumDao().getAlbumById(requireNotNull(song.albumId))?.title)
        assertEquals(listOf("Solo"), artistNames(song.id))
    }

    @Test fun flacVorbisCommentsAreRead() = runBlocking {
        val file = write(
            music,
            "file-name.flac",
            ScanAudioFixtures.flac(mapOf("TITLE" to "Flac Title", "ARTIST" to "Flac Artist", "ALBUM" to "Flac Album")),
        )

        fx.scan(music)

        val song = fx.requireSongAt(file)
        assertEquals("Flac Title", song.title)
        assertEquals(listOf("Flac Artist"), artistNames(song.id))
    }

    @Test fun commaSplitsArtistsTrimmedAndInTagOrder() = runBlocking {
        val file = write(music, "a.mp3", mp3(mapOf("TIT2" to "T", "TPE1" to "Alpha ,  Beta,Gamma")))

        fx.scan(music)

        assertEquals(listOf("Alpha", "Beta", "Gamma"), artistNames(fx.requireSongAt(file).id))
    }

    @Test fun ampersandKeepsOneArtist() = runBlocking {
        val file = write(music, "a.mp3", mp3(mapOf("TIT2" to "T", "TPE1" to "Matisse & Sadko")))

        fx.scan(music)

        assertEquals(listOf("Matisse & Sadko"), artistNames(fx.requireSongAt(file).id))
    }

    @Test fun repeatedArtistIgnoringCaseIsKeptOnce() = runBlocking {
        val file = write(music, "a.mp3", mp3(mapOf("TIT2" to "T", "TPE1" to "Alpha, alpha, ALPHA, Beta")))

        fx.scan(music)

        assertEquals(listOf("Alpha", "Beta"), artistNames(fx.requireSongAt(file).id))
    }

    @Test fun repeatedArtistLaterInTheListKeepsTheFirstOneFirst() = runBlocking {
        val file = write(music, "a.mp3", mp3(mapOf("TIT2" to "T", "TPE1" to "Beta, Alpha, beta")))

        fx.scan(music)

        assertEquals(listOf("Beta", "Alpha"), artistNames(fx.requireSongAt(file).id))
    }

    @Test fun blankPartsBetweenCommasAreDropped() = runBlocking {
        val file = write(music, "a.mp3", mp3(mapOf("TIT2" to "T", "TPE1" to "Alpha, , Beta,")))

        fx.scan(music)

        assertEquals(listOf("Alpha", "Beta"), artistNames(fx.requireSongAt(file).id))
    }

    @Test fun missingArtistTagFallsBackToUnknownArtist() = runBlocking {
        val file = write(music, "a.mp3", mp3(mapOf("TIT2" to "No Artist")))

        fx.scan(music)

        assertEquals(listOf("Unknown Artist"), artistNames(fx.requireSongAt(file).id))
    }

    @Test fun twoSongsShareOneArtistRowIgnoringCase() = runBlocking {
        write(music, "a.mp3", mp3(mapOf("TIT2" to "One", "TPE1" to "Shared Name")))
        write(music, "b.mp3", mp3(mapOf("TIT2" to "Two", "TPE1" to "shared name"), audioFrames = 41))

        fx.scan(music)

        assertEquals(1, fx.database.artistDao().getAllArtistsOnce().size)
        assertEquals(2, fx.songDao.getAllSongsOnce().size)
    }

    /**
     * Text frames sit after a 70 KB filler frame, so editing them changes only bytes between the
     * hashed head and tail: same id, and the rescan must re-read the tags into the same row.
     */
    @Test fun tagEditOutsideHashedBytesRetagsTheSameRow() = runBlocking {
        val before =
            mp3(mapOf("TIT2" to "Old Title", "TPE1" to "Old Artist"), fillerBeforeFrames = 70_000, audioFrames = 200)
        val after =
            mp3(mapOf("TIT2" to "New Title", "TPE1" to "New, Pair!"), fillerBeforeFrames = 70_000, audioFrames = 200)
        assertEquals("fixture must keep the file size", before.size, after.size)
        val file = write(music, "song.mp3", before)
        fx.scan(music)
        val original = fx.requireSongAt(file)
        fx.addPlaylist("p1", original.id)

        file.writeBytes(after)
        val remaps = fx.recordRemaps(this)
        val result = fx.scan(music)
        coroutineContext.cancelChildren()

        val retagged = fx.requireSongAt(file)
        assertEquals("same content id", original.id, retagged.id)
        assertEquals(1, result.updated)
        assertEquals(0, result.added)
        assertEquals("New Title", retagged.title)
        assertEquals(listOf("New", "Pair!"), artistNames(retagged.id))
        assertEquals(listOf(original.id), fx.playlistDao.getSongIdsForPlaylist("p1"))
        assertTrue(
            "remap for the retag: $remaps",
            remaps.any {
                it.oldId == original.id &&
                    it.newSong.title == "New Title"
            },
        )
    }

    @Test fun unchangedTagsOnRescanAreCountedUnchanged() = runBlocking {
        write(music, "a.mp3", mp3(mapOf("TIT2" to "T", "TPE1" to "A")))
        fx.scan(music)

        val second = fx.scan(music)

        assertEquals(1, second.unchanged)
        assertEquals(0, second.updated)
    }

    private suspend fun artistNames(songId: String): List<String> {
        val byId = fx.database.artistDao().getAllArtistsOnce().associate { it.id to it.name }
        return fx.songDao.getSongArtistRefs(songId).sortedBy { it.position }.map { byId.getValue(it.artistId) }
    }
}
