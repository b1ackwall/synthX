package com.v1per.synthx.data.scanner

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.Color
import androidx.core.graphics.get
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.data.compressedArtFileExists
import com.v1per.synthx.data.deleteSongArtFiles
import com.v1per.synthx.data.fullResArtFileExists
import com.v1per.synthx.data.scanner.ScanTestFixture.Companion.write
import com.v1per.synthx.ui.testing.TestTimeouts
import com.v1per.synthx.ui.testing.isNear
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A tagged Ogg Opus file ([OpusAudioFixture]) through a real scan: the .opus and .oga extensions
 * are scanned, and the platform reader yields its tags, a duration and its front-cover art.
 */
@RunWith(AndroidJUnit4::class)
class OpusTagIngestInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.device()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var fx: ScanTestFixture
    private lateinit var music: File

    // Art lands in the app's own filesDir/art, outside the fixture's cacheDir root.
    private val scannedSongIds = mutableSetOf<String>()

    @Before fun setUp() {
        fx = ScanTestFixture(context)
        music = fx.root("music")
    }

    @After fun tearDown() {
        deleteSongArtFiles(context, scannedSongIds)
        fx.close()
    }

    @Test fun opusTagsDurationAndFrontCoverAreRead() = runBlocking {
        val file = write(music, "file-name.opus", OpusAudioFixture.bytes())

        assertEquals(1, fx.scan(music).added)

        val song = fx.requireSongAt(file).also { scannedSongIds += it.id }
        assertEquals(OpusAudioFixture.TITLE, song.title)
        assertEquals(listOf(OpusAudioFixture.ARTIST), artistNames(song.id))
        assertEquals(
            OpusAudioFixture.ALBUM,
            fx.database.albumDao().getAlbumById(requireNotNull(song.albumId))?.title,
        )
        assertEquals(OpusAudioFixture.TRACK_NUMBER, song.trackNumber)
        assertTrue("duration ${song.duration} ms", song.duration in 1L..2_000L)

        assertTrue("compressed art file", compressedArtFileExists(context, song.id))
        assertTrue("full-resolution art file", fullResArtFileExists(context, song.id))
        val artUri = requireNotNull(song.albumArtUri) { "albumArtUri" }.toUri()
        assertTrue("albumArtFullUri", song.albumArtFullUri != null)
        val art = requireNotNull(
            context.contentResolver.openInputStream(artUri).use { BitmapFactory.decodeStream(it) },
        ) { "art at $artUri did not decode" }
        val centre = Color(art[art.width / 2, art.height / 2])
        assertTrue("art centre $centre", centre.isNear(Color(OpusAudioFixture.COVER_ARGB)))
    }

    @Test fun ogaExtensionIsScanned() = runBlocking {
        val file = write(music, "file-name.oga", OpusAudioFixture.bytes())

        assertEquals(1, fx.scan(music).added)

        val song = fx.requireSongAt(file).also { scannedSongIds += it.id }
        assertEquals(OpusAudioFixture.TITLE, song.title)
        assertTrue("duration ${song.duration} ms", song.duration in 1L..2_000L)
    }

    private suspend fun artistNames(songId: String): List<String> {
        val byId = fx.database.artistDao().getAllArtistsOnce().associate { it.id to it.name }
        return fx.songDao.getSongArtistRefs(songId).sortedBy { it.position }.map { byId.getValue(it.artistId) }
    }
}
