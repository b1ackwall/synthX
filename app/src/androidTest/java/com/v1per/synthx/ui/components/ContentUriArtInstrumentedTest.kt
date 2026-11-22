package com.v1per.synthx.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.v1per.synthx.ui.testing.ArtFiles
import com.v1per.synthx.ui.testing.ArtTestColor
import com.v1per.synthx.ui.testing.ArtTestImageLoader
import com.v1per.synthx.ui.testing.TestTimeouts
import com.v1per.synthx.ui.testing.isNear
import com.v1per.synthx.ui.theme.SurfaceVariant
import com.v1per.synthx.ui.theme.SynthXTheme
import java.io.FileNotFoundException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Album art addressed the way the app stores it: a JPEG under filesDir/art behind the app's
 * FileProvider, passed as a content:// string through crossfadeModel and AsyncImage (the detail
 * hero). A readable file draws its own colour; a missing file draws the flat SurfaceVariant square,
 * and the request must fail because the file is missing, not because the URI cannot be opened.
 */
@RunWith(AndroidJUnit4::class)
class ContentUriArtInstrumentedTest {
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

    private fun show(coverUri: String) {
        composeRule.setContent {
            SynthXTheme {
                Box(modifier = Modifier.testTag(HOST_TAG)) { DetailHeroArt(coverUri = coverUri) }
            }
        }
    }

    private fun centre(): Color {
        val pixels = composeRule.onNodeWithTag(HOST_TAG).captureToImage().toPixelMap()
        return pixels[pixels.width / 2, pixels.height / 2]
    }

    @Test
    fun aFileProviderArtUriDrawsTheArt() {
        val uri = art.jpeg(ArtTestColor)
        assertTrue("not the app's art URI shape: $uri", uri.startsWith(art.uriPrefix))

        show(uri)

        composeRule.waitUntil(LOAD_TIMEOUT_MS) { loader.successCount > 0 && centre().isNear(ArtTestColor) }
        assertEquals("no request failed", 0, loader.failureCount)
    }

    @Test
    fun aFileProviderArtUriWhoseFileIsMissingDrawsThePlaceholder() {
        show(art.missing())

        composeRule.waitUntil(LOAD_TIMEOUT_MS) { loader.failureCount > 0 }
        composeRule.waitForIdle()
        // The provider was reached and had no file; a loader that cannot open content:// fails otherwise.
        assertTrue("failed for another reason: ${loader.lastFailure}", loader.lastFailure is FileNotFoundException)
        assertEquals(SurfaceVariant.toArgb(), centre().toArgb())
        assertEquals("nothing loaded", 0, loader.successCount)
    }

    private companion object {
        const val HOST_TAG = "content-uri-art-host"
        const val LOAD_TIMEOUT_MS = 5_000L
    }
}
