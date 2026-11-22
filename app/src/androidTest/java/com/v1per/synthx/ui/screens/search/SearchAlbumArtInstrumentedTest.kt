package com.v1per.synthx.ui.screens.search

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.v1per.synthx.data.repository.Album
import com.v1per.synthx.playback.PlaybackController
import com.v1per.synthx.playback.PlaybackViewModel
import com.v1per.synthx.ui.testing.ArtFiles
import com.v1per.synthx.ui.testing.ArtTestColor
import com.v1per.synthx.ui.testing.ArtTestImageLoader
import com.v1per.synthx.ui.testing.FakePlaybackEngine
import com.v1per.synthx.ui.testing.TestTimeouts
import com.v1per.synthx.ui.testing.flowOnlyRepository
import com.v1per.synthx.ui.testing.showsSquareOf
import com.v1per.synthx.ui.theme.ArtSizes
import com.v1per.synthx.ui.theme.SynthXTheme
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The real Search screen draws a matching album's content:// art in its result row, not the placeholder. */
@RunWith(AndroidJUnit4::class)
class SearchAlbumArtInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.device()

    @get:Rule val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val loader = ArtTestImageLoader(context)
    private val art = ArtFiles(context)
    private val viewModels = mutableListOf<ViewModel>()

    @Before
    fun installImageLoader() {
        loader.install()
    }

    @After
    fun cleanUp() {
        composeRule.runOnUiThread { viewModels.forEach { it.viewModelScope.cancel() } }
        loader.reset()
        art.deleteAll()
    }

    private fun <T : ViewModel> onUi(build: () -> T): T {
        lateinit var built: T
        composeRule.runOnUiThread { built = build() }
        viewModels += built
        return built
    }

    private fun artEdgePx(): Int = (ArtSizes.Album.value * composeRule.density.density).toInt()

    private fun showsArt(): Boolean =
        composeRule.onNodeWithTag(HOST_TAG).captureToImage().toPixelMap().showsSquareOf(ArtTestColor, artEdgePx())

    @Test
    fun aMatchingAlbumResultDrawsItsArt() {
        val album =
            Album(id = "art-album", title = "Art Album", artist = "Art Artist", albumArtUri = art.jpeg(ArtTestColor))
        val repository = flowOnlyRepository(
            mapOf(
                "getSongs" to flowOf(emptyList<Any>()),
                "getArtists" to flowOf(emptyList<Any>()),
                "getAlbums" to flowOf(listOf(album)),
                "getAllPlaylists" to flowOf(emptyList<Any>()),
            ),
        )
        val playback = onUi { PlaybackViewModel(PlaybackController(FakePlaybackEngine())) }
        val viewModel = onUi { SearchViewModel(repository, context) }

        composeRule.setContent {
            SynthXTheme {
                Box(modifier = Modifier.testTag(HOST_TAG)) {
                    SearchScreen(
                        topBar = {},
                        onNavigateToArtistDetail = { _, _ -> },
                        onNavigateToAlbumDetail = { _, _ -> },
                        onNavigateToPlaylistDetail = { _, _ -> },
                        playbackViewModel = playback,
                        searchReselectEvents = MutableSharedFlow(),
                        viewModel = viewModel,
                    )
                }
            }
        }
        composeRule.onNode(hasSetTextAction()).performTextInput(album.title)

        composeRule.waitUntil(LOAD_TIMEOUT_MS) { loader.successCount > 0 && showsArt() }
        assertEquals("no request failed", 0, loader.failureCount)
    }

    private companion object {
        const val HOST_TAG = "search-album-art-host"
        const val LOAD_TIMEOUT_MS = 5_000L
    }
}
