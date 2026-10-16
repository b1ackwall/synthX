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
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil.Coil
import coil.EventListener
import coil.ImageLoader
import coil.intercept.Interceptor
import coil.request.CachePolicy
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.ImageResult
import coil.request.SuccessResult
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.ui.common.UiState
import com.v1per.synthx.ui.components.DetailHeroSize
import com.v1per.synthx.ui.theme.SurfaceVariant
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
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
 * The test installs its own singleton ImageLoader: caches off, an event listener that reports when
 * the request has finished, and an interceptor that turns off the crossfade for requests that reach
 * the interceptor chain, so a finished error state is drawn at once instead of fading over 100ms.
 * A null cover never reaches the chain (Coil fails it up front); it fades in from nothing, which
 * the null test waits out.
 */
@RunWith(AndroidJUnit4::class)
class AlbumDetailCoverInstrumentedTest {
    @get:Rule val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val failed = AtomicBoolean(false)
    private val succeeded = AtomicBoolean(false)
    private var artFile: File? = null

    @Before
    fun installImageLoader() {
        Coil.setImageLoader(
            ImageLoader.Builder(context)
                .memoryCachePolicy(CachePolicy.DISABLED)
                .diskCachePolicy(CachePolicy.DISABLED)
                .components { add(NoCrossfadeInterceptor) }
                .eventListener(object : EventListener {
                    override fun onError(request: ImageRequest, result: ErrorResult) = failed.set(true)
                    override fun onSuccess(request: ImageRequest, result: SuccessResult) = succeeded.set(true)
                })
                .build(),
        )
    }

    @After
    fun resetImageLoader() {
        Coil.reset()
        artFile?.delete()
    }

    private fun show(coverUri: String?) {
        val songs = listOf(
            Song(
                id = "t1", title = "t1", artist = "Artist", album = "Album", duration = 60_000L,
                filePath = "/music/t1.mp3", albumArtUri = coverUri,
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

        composeRule.waitUntil(LOAD_TIMEOUT_MS) { failed.get() }
        composeRule.waitUntil(LOAD_TIMEOUT_MS) { centrePixel() == SurfaceVariant.toArgb() }
        // The tag is inside the box's vertical padding, so this is the art's own height.
        composeRule.onNodeWithTag(ALBUM_DETAIL_COVER_TAG).assertHeightIsEqualTo(DetailHeroSize)
    }

    @Test
    fun aCoverWhoseFileIsGoneShowsTheFlatSquareNotAHole() {
        val gone = File(context.cacheDir, "album_cover_that_does_not_exist.png").apply { delete() }
        show(Uri.fromFile(gone).toString())

        composeRule.waitUntil(LOAD_TIMEOUT_MS) { failed.get() }
        composeRule.waitForIdle()
        assertEquals(SurfaceVariant.toArgb(), centrePixel())
    }

    @Test
    fun aReadableCoverDrawsTheArtAtHeroSize() {
        val file = File(context.cacheDir, "album_cover_test.png").also { artFile = it }
        val bitmap = Bitmap.createBitmap(ART_EDGE_PX, ART_EDGE_PX, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.RED)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        show(Uri.fromFile(file).toString())

        composeRule.waitUntil(LOAD_TIMEOUT_MS) { succeeded.get() && mostInteriorPixelsDifferFromThePlaceholder() }
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
        for (x in left + inset until left + side - inset) for (y in inset until side - inset) {
            sampled++
            if (pixels[x, y].toArgb() != SurfaceVariant.toArgb()) differing++
        }
        return differing > sampled / 2
    }

    private object NoCrossfadeInterceptor : Interceptor {
        override suspend fun intercept(chain: Interceptor.Chain): ImageResult =
            chain.proceed(chain.request.newBuilder().crossfade(false).build())
    }

    private companion object {
        const val LOAD_TIMEOUT_MS = 5_000L
        const val ART_EDGE_PX = 64
    }
}
