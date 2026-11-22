package com.v1per.synthx.ui.components

import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.ui.testing.ArtFiles
import com.v1per.synthx.ui.testing.ArtTestColor
import com.v1per.synthx.ui.testing.ArtTestImageLoader
import com.v1per.synthx.ui.testing.ArtTestOtherColor
import com.v1per.synthx.ui.testing.TestTimeouts
import com.v1per.synthx.ui.testing.isNear
import com.v1per.synthx.ui.theme.SynthXTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The player art's two sources: the expanded player draws albumArtFullUri at original resolution,
 * here a flat JPEG wider than 4096 px, and the mini player draws the compressed albumArtUri. Each
 * source has its own colour, so the test sees which one was drawn. Only the colour is asserted:
 * the decoded size is the image library's choice.
 */
@RunWith(AndroidJUnit4::class)
class PlayerFullResolutionArtInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.device()

    @get:Rule val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val loader = ArtTestImageLoader(context)
    private val art = ArtFiles(context)

    @Before
    fun installImageLoader() {
        loader.install()
    }

    @After
    fun cleanUp() {
        loader.reset()
        art.deleteAll()
    }

    private fun song(fullUri: String) = Song(
        id = "full-res",
        title = "Full Resolution",
        artist = "Artist",
        album = "Album",
        duration = 60_000L,
        filePath = "/music/full-res.mp3",
        albumArtUri = art.jpeg(ArtTestOtherColor),
        albumArtFullUri = fullUri,
    )

    private fun show(song: Song, fullResolution: Boolean) {
        composeRule.setContent {
            SynthXTheme {
                Box(modifier = Modifier.testTag(HOST_TAG)) {
                    PlayerAlbumArt(
                        song = song,
                        isPlaying = true,
                        size = DetailHeroSize,
                        fullResolution = fullResolution,
                        scale = 1f,
                    )
                }
            }
        }
    }

    private fun centre(): Color {
        val pixels = composeRule.onNodeWithTag(HOST_TAG).captureToImage().toPixelMap()
        return pixels[pixels.width / 2, pixels.height / 2]
    }

    @Test
    fun theExpandedPlayerDrawsTheWideFullResolutionArt() {
        show(song(art.jpeg(ArtTestColor, WIDE_WIDTH_PX, WIDE_HEIGHT_PX)), fullResolution = true)

        composeRule.waitUntil(WIDE_LOAD_TIMEOUT_MS) { loader.successCount > 0 && centre().isNear(ArtTestColor) }
        Log.i(LOG_TAG, "full-resolution art decoded at ${loader.lastDecodedSize}")
        assertEquals("no request failed", 0, loader.failureCount)
    }

    @Test
    fun theMiniPlayerDrawsTheCompressedArt() {
        show(song(art.jpeg(ArtTestColor)), fullResolution = false)

        composeRule.waitUntil(LOAD_TIMEOUT_MS) { loader.successCount > 0 && centre().isNear(ArtTestOtherColor) }
        assertEquals("no request failed", 0, loader.failureCount)
    }

    private companion object {
        const val HOST_TAG = "player-full-resolution-art-host"
        const val LOG_TAG = "PlayerFullResArtTest"
        const val WIDE_WIDTH_PX = 4_500
        const val WIDE_HEIGHT_PX = 1_500
        const val LOAD_TIMEOUT_MS = 5_000L
        const val WIDE_LOAD_TIMEOUT_MS = 10_000L
    }
}
