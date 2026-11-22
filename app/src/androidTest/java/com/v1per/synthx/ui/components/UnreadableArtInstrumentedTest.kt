package com.v1per.synthx.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.ui.screens.library.RecentlyAddedSection
import com.v1per.synthx.ui.testing.ArtTestImageLoader
import com.v1per.synthx.ui.testing.TestTimeouts
import com.v1per.synthx.ui.theme.ArtSizes
import com.v1per.synthx.ui.theme.Spacing
import com.v1per.synthx.ui.theme.SynthXTheme
import com.v1per.synthx.ui.theme.SurfaceVariant
import java.io.File
import kotlinx.collections.immutable.persistentListOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A song whose art file cannot be read shows the flat SurfaceVariant square (the same pixels as
 * ArtPlaceholder), never a hole. Each site sits on a red host, so a hole reads as red.
 *
 * The test installs [ArtTestImageLoader]: caches off, failed requests counted, and the crossfade off
 * so a failed request is drawn at once.
 */
@RunWith(AndroidJUnit4::class)
class UnreadableArtInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.device()

    @get:Rule val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val loader = ArtTestImageLoader(context)
    private val goneUri = File(context.cacheDir, "unreadable_art_that_does_not_exist.png")
        .apply { delete() }.toURI().toString()

    @Before
    fun installImageLoader() {
        loader.install()
    }

    @After
    fun resetImageLoader() {
        loader.reset()
    }

    private fun song(id: String) = Song(
        id = id,
        title = "Title $id",
        artist = "Artist",
        album = "Album",
        duration = 60_000L,
        filePath = "/music/$id.mp3",
        albumArtUri = goneUri,
    )

    private fun host(content: @Composable () -> Unit) {
        composeRule.setContent {
            SynthXTheme {
                Box(modifier = Modifier.testTag(HOST_TAG).background(Color.Red)) { content() }
            }
        }
    }

    private fun pixelAt(xPx: Int, yPx: Int): Int {
        val pixels = composeRule.onNodeWithTag(HOST_TAG).captureToImage().toPixelMap()
        return pixels[xPx, yPx].toArgb()
    }

    private fun dpPx(value: Float): Int = (value * composeRule.density.density).toInt()

    @Test
    fun songIdentityShowsTheFlatSquareForUnreadableArt() {
        host {
            Box(modifier = Modifier.width(300.dp).height(ArtSizes.Song)) {
                SongIdentity(song("identity"), Modifier.fillMaxSize())
            }
        }

        composeRule.waitUntil(LOAD_TIMEOUT_MS) { loader.failureCount > 0 }
        composeRule.waitForIdle()
        val x = dpPx(Spacing.md.value + ArtSizes.Song.value / 2)
        assertEquals(SurfaceVariant.toArgb(), pixelAt(x, dpPx(ArtSizes.Song.value / 2)))
    }

    @Test
    fun playerAlbumArtShowsTheFlatSquareForUnreadableArt() {
        host {
            Box(modifier = Modifier.size(ArtSizes.Album)) {
                PlayerAlbumArt(song("player"), isPlaying = true, size = ArtSizes.Album, scale = 1f)
            }
        }

        composeRule.waitUntil(LOAD_TIMEOUT_MS) { loader.failureCount > 0 }
        composeRule.waitForIdle()
        val centre = dpPx(ArtSizes.Album.value / 2)
        assertEquals(SurfaceVariant.toArgb(), pixelAt(centre, centre))
    }

    @Test
    fun recentlyAddedTilesShowTheFlatSquareForUnreadableArt() {
        host {
            Box(modifier = Modifier.width(360.dp)) {
                RecentlyAddedSection(
                    songs = persistentListOf(song("recent-1"), song("recent-2")),
                    onSeeAllClick = {},
                    onSongClick = {},
                )
            }
        }

        composeRule.waitUntil(LOAD_TIMEOUT_MS) { loader.failureCount > 0 }
        composeRule.waitForIdle()
        // Nothing else in the section paints SurfaceVariant, so any such pixel is a placeholder square.
        val pixels = composeRule.onNodeWithTag(HOST_TAG).captureToImage().toPixelMap()
        var flat = 0
        for (x in 0 until pixels.width) {
            for (y in 0 until pixels.height) {
                if (pixels[x, y].toArgb() == SurfaceVariant.toArgb()) flat++
            }
        }
        assertTrue("recently added tiles drew no flat placeholder square", flat > MIN_FLAT_PIXELS)
    }

    private companion object {
        const val HOST_TAG = "unreadable-art-host"
        const val LOAD_TIMEOUT_MS = 5_000L
        const val MIN_FLAT_PIXELS = 1_000
    }
}
