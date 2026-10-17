package com.v1per.synthx.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.ui.testing.TestTimeouts
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The loading cover on detail routes: solid black with the content not composed at all while
 * loading, then the content in one cut. Checked on the paused clock with a pixel read, so a fade
 * (still dark after two frames) fails.
 */
@RunWith(AndroidJUnit4::class)
class ReadinessCoverOverlayInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER) val testTimeout = TestTimeouts.device()

    @get:Rule val composeRule = createComposeRule()

    private val ready = mutableStateOf(false)

    private fun setCover() {
        composeRule.setContent {
            // Raw size: a fixed probe box for the pixel read, not app layout.
            Box(Modifier.size(64.dp).testTag(COVER)) {
                ReadinessCoverOverlay(isReady = ready.value) {
                    Box(Modifier.fillMaxSize().background(Color.White)) { Text(CONTENT) }
                }
            }
        }
    }

    private fun pixel(): Color {
        val pixels = composeRule.onNodeWithTag(COVER).captureToImage().toPixelMap()
        return pixels[pixels.width / 2, pixels.height / 4]
    }

    @Test fun loadingShowsBlackAndDoesNotComposeTheContent() {
        setCover()
        composeRule.onNodeWithText(CONTENT).assertDoesNotExist()
        assertEquals(Color.Black, pixel())
    }

    @Test fun readyCutsToTheContentWithinTwoFrames() {
        setCover()
        composeRule.mainClock.autoAdvance = false
        try {
            ready.value = true
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.mainClock.advanceTimeByFrame()
            assertEquals("content must be fully shown, not fading in", Color.White, pixel())
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
        composeRule.onNodeWithText(CONTENT).assertExists()
    }

    private companion object {
        const val COVER = "cover-under-test"
        const val CONTENT = "cover content"
    }
}
