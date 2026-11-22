package com.v1per.synthx.ui.screens.albumdetail

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.core.graphics.createBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.ui.common.UiState
import com.v1per.synthx.ui.components.DetailHeroSize
import com.v1per.synthx.ui.testing.ArtTestImageLoader
import com.v1per.synthx.ui.testing.TestTimeouts
import com.v1per.synthx.ui.theme.SurfaceVariant
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The album hero at [DetailHeroSize]: real art when the cover file decodes, and the flat
 * SurfaceVariant square (the same pixels as ArtPlaceholder) for a null cover or an unreadable file.
 *
 * The test installs [ArtTestImageLoader]: caches off, finished requests recorded, and the crossfade
 * off for requests that reach the interceptor chain, so a finished error state is drawn at once
 * instead of fading over 100ms. A null cover never reaches the chain (Coil fails it up front); it
 * fades in from nothing, which the null test waits out.
 */
@RunWith(AndroidJUnit4::class)
class AlbumDetailCoverInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.device()

    @get:Rule val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val loader = ArtTestImageLoader(context)
    private var artFile: File? = null

    @Before
    fun installImageLoader() {
        loader.install()
    }

    @After
    fun resetImageLoader() {
        loader.reset()
        artFile?.delete()
    }

    private fun show(coverUri: String?) {
        val songs = listOf(
            Song(
                id = "t1",
                title = "t1",
                artist = "Artist",
                album = "Album",
                duration = 60_000L,
                filePath = "/music/t1.mp3",
                albumArtUri = coverUri,
            ),
        )
        composeRule.setContent {
            AlbumDetailContent(
                uiState = UiState.Success(albumDetailData(songs)),
                currentlyPlayingSongId = null,
                onSongTap = { _, _ -> },
                onPlay = {},
                onShuffle = {},
            )
        }
    }

    private fun centrePixel(): Int {
        val pixels = composeRule.onNodeWithTag(ALBUM_DETAIL_COVER_TAG).captureToImage().toPixelMap()
        return pixels[pixels.width / 2, pixels.height / 2].toArgb()
    }

    @Test
    fun aNullCoverShowsTheFlatSquareAtHeroSize() {
        show(null)

        composeRule.waitUntil(LOAD_TIMEOUT_MS) { loader.failureCount > 0 }
        composeRule.waitUntil(LOAD_TIMEOUT_MS) { centrePixel() == SurfaceVariant.toArgb() }
        // The tag is inside the box's vertical padding, so this is the art's own height.
        composeRule.onNodeWithTag(ALBUM_DETAIL_COVER_TAG).assertHeightIsEqualTo(DetailHeroSize)
    }

    @Test
    fun aCoverWhoseFileIsGoneShowsTheFlatSquareNotAHole() {
        val gone = File(context.cacheDir, "album_cover_that_does_not_exist.png").apply { delete() }
        show(Uri.fromFile(gone).toString())

        composeRule.waitUntil(LOAD_TIMEOUT_MS) { loader.failureCount > 0 }
        composeRule.waitForIdle()
        assertEquals(SurfaceVariant.toArgb(), centrePixel())
    }

    @Test
    fun aReadableCoverDrawsTheArtAtHeroSize() {
        val file = File(context.cacheDir, "album_cover_test.png").also { artFile = it }
        val bitmap = createBitmap(ART_EDGE_PX, ART_EDGE_PX, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.RED)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        show(Uri.fromFile(file).toString())

        composeRule.waitUntil(LOAD_TIMEOUT_MS) {
            loader.successCount > 0 && mostInteriorPixelsDifferFromThePlaceholder()
        }
        composeRule.onNodeWithTag(ALBUM_DETAIL_COVER_TAG).assertHeightIsEqualTo(DetailHeroSize)
    }

    // Same sampling as PlaylistDetailCoverInstrumentedTest: the tagged box is full width with the
    // hero square centred in it; sample its interior, inset clear of the rounded-corner clip.
    private fun mostInteriorPixelsDifferFromThePlaceholder(): Boolean {
        val pixels = composeRule.onNodeWithTag(ALBUM_DETAIL_COVER_TAG).captureToImage().toPixelMap()
        val density = composeRule.density.density
        val side = (DetailHeroSize.value * density).toInt()
        val inset = (16 * density).toInt()
        val left = (pixels.width - side) / 2
        var differing = 0
        var sampled = 0
        for (x in left + inset until left + side - inset) {
            for (y in inset until side - inset) {
                sampled++
                if (pixels[x, y].toArgb() != SurfaceVariant.toArgb()) differing++
            }
        }
        return differing > sampled / 2
    }

    private companion object {
        const val LOAD_TIMEOUT_MS = 5_000L
        const val ART_EDGE_PX = 64
    }
}
