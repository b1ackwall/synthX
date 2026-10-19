package com.v1per.synthx.playback

import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.ui.testing.TestTimeouts
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerBackgroundArtLoaderTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.jvm()

    @Test fun compressedCopyIsTriedBeforeTheFullResolutionOriginal() {
        val request = PlayerBackgroundArtRequest("song", compressedUri = "content://art", fullUri = "content://full")
        assertEquals(listOf("content://art", "content://full"), request.decodeSources())
    }

    @Test fun missingCompressedCopyFallsBackToTheFullResolutionOriginal() {
        val request = PlayerBackgroundArtRequest("song", compressedUri = null, fullUri = "content://full")
        assertEquals(listOf("content://full"), request.decodeSources())
    }

    @Test fun songWithoutArtHasNoSources() {
        assertEquals(emptyList(), PlayerBackgroundArtRequest("song", null, null).decodeSources())
    }

    @Test fun identicalUrisAreDecodedOnce() {
        assertEquals(
            listOf("content://art"),
            PlayerBackgroundArtRequest("song", "content://art", "content://art").decodeSources(),
        )
    }

    @Test fun requestCarriesTheSongsIdentityAndBothUris() {
        val song = Song(
            id = "id-1",
            title = "T",
            artist = "A",
            album = "B",
            duration = 1L,
            filePath = "f",
            albumArtUri = "content://art",
            albumArtFullUri = "content://full",
        )
        assertEquals(
            PlayerBackgroundArtRequest("id-1", "content://art", "content://full"),
            PlayerBackgroundArtRequest.from(song),
        )
    }

    @Test fun sampleSizeIsTheLargestPowerOfTwoKeepingTheShortSideAtLeast128() {
        assertEquals(1, backgroundArtSampleSize(100, 100, 128))
        assertEquals(1, backgroundArtSampleSize(255, 255, 128))
        assertEquals(2, backgroundArtSampleSize(256, 256, 128))
        assertEquals(4, backgroundArtSampleSize(1000, 1000, 128))
        // Short side 2000 decides: /8 = 250 >= 128, /16 = 125 < 128.
        assertEquals(8, backgroundArtSampleSize(3000, 2000, 128))
        assertEquals(8, backgroundArtSampleSize(2000, 3000, 128))
        for (size in listOf(128, 300, 640, 1000, 1400, 3000, 5000)) {
            val sample = backgroundArtSampleSize(size, size, 128)
            assertEquals(true, size / sample >= 128, "short side below 128 at $size")
            assertEquals(true, size / (sample * 2) < 128, "sample not the largest at $size")
        }
    }

    @Test fun centerCropIsTheLargestCenteredSquare() {
        assertEquals(SquareCrop(0, 0, 250), centerSquareCrop(250, 250))
        assertEquals(SquareCrop(50, 0, 300), centerSquareCrop(400, 300))
        assertEquals(SquareCrop(0, 50, 300), centerSquareCrop(300, 400))
        assertEquals(SquareCrop(50, 0, 300), centerSquareCrop(401, 300))
    }
}
