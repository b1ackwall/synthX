package com.v1per.synthx.ui.screens.albumlist

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.v1per.synthx.data.repository.Album
import com.v1per.synthx.ui.testing.ArtFiles
import com.v1per.synthx.ui.testing.ArtTestColor
import com.v1per.synthx.ui.testing.ArtTestImageLoader
import com.v1per.synthx.ui.testing.TestTimeouts
import com.v1per.synthx.ui.testing.flowOnlyRepository
import com.v1per.synthx.ui.testing.showsSquareOf
import com.v1per.synthx.ui.theme.ArtSizes
import com.v1per.synthx.ui.theme.SynthXTheme
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The real Albums screen draws an album's content:// art in its row, not the placeholder. */
@RunWith(AndroidJUnit4::class)
class AlbumListArtInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.device()

    @get:Rule val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val loader = ArtTestImageLoader(context)
    private val art = ArtFiles(context)
    private var viewModel: AlbumListViewModel? = null

    @Before
    fun installImageLoader() {
        loader.install()
    }

    @After
    fun cleanUp() {
        viewModel?.let { built -> composeRule.runOnUiThread { built.viewModelScope.cancel() } }
        loader.reset()
        art.deleteAll()
    }

    private fun artEdgePx(): Int = (ArtSizes.Album.value * composeRule.density.density).toInt()

    private fun showsArt(): Boolean =
        composeRule.onNodeWithTag(HOST_TAG).captureToImage().toPixelMap().showsSquareOf(ArtTestColor, artEdgePx())

    @Test
    fun anAlbumRowDrawsItsArt() {
        val album =
            Album(id = "art-album", title = "Art Album", artist = "Art Artist", albumArtUri = art.jpeg(ArtTestColor))
        val repository = flowOnlyRepository(mapOf("getAlbums" to flowOf(listOf(album))))
        lateinit var built: AlbumListViewModel
        composeRule.runOnUiThread { built = AlbumListViewModel(repository, context) }
        viewModel = built

        composeRule.setContent {
            SynthXTheme {
                Box(modifier = Modifier.testTag(HOST_TAG)) {
                    AlbumListScreen(topBar = {}, onAlbumClick = { _, _ -> }, viewModel = built)
                }
            }
        }

        composeRule.waitUntil(LOAD_TIMEOUT_MS) { loader.successCount > 0 && showsArt() }
        assertEquals("no request failed", 0, loader.failureCount)
    }

    private companion object {
        const val HOST_TAG = "album-list-art-host"
        const val LOAD_TIMEOUT_MS = 5_000L
    }
}
