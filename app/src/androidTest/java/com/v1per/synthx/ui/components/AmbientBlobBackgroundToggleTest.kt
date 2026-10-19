package com.v1per.synthx.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.ui.testing.TestTimeouts
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * R5-F17: rememberBlobAnimation/rememberOrganicWobble used to be composed conditionally inside
 * `if (blob.enabled)`, so toggling a blob off disposed its remember slots (position, heading,
 * wobble phase) and re-enabling it respawned at a fresh random position. The fix keeps
 * composition unconditional and gates only the draw — this proves state survives the toggle
 * by pixel capture.
 */
@RunWith(AndroidJUnit4::class)
class AmbientBlobBackgroundToggleTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.device()

    @get:Rule val composeRule = createComposeRule()

    @Test fun togglingBlobEnabledOffAndOnPreservesItsSpawnedPosition() {
        composeRule.mainClock.autoAdvance = false
        // paused=true freezes the movement while-loop, but BlobMotionState's initial spawn
        // (Random-based x/y) still happens the first time layerSize becomes available — so a
        // preserved remember slot renders the SAME frame every time, while a reset slot
        // (the bug) re-spawns at a fresh random position almost certainly producing a
        // different frame.
        val blob = mutableStateOf(
            BlobParams(
                hue = 120f,
                saturation = 0.8f,
                lightness = 0.5f,
                sizeFraction = 0.5f,
                opacity = 1f,
                gradientFalloffFraction = 0f,
            ),
        )
        composeRule.setContent {
            Box(Modifier.size(200.dp).testTag("bg")) {
                AmbientBlobBackground(
                    blobs = listOf(blob.value),
                    baseSurfaceColor = Color.Black,
                    blurRadiusDp = 0.dp,
                    paused = true,
                )
            }
        }
        // Let the initial random spawn happen and settle.
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        val beforeDisable = captureBackground()

        composeRule.runOnUiThread { blob.value = blob.value.copy(enabled = false) }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        // Nothing drawn for this blob while disabled: the draw-time skip leaves only the base
        // surface color.
        assertAllPixelsMatch(Color.Black.toArgb(), captureBackground())

        composeRule.runOnUiThread { blob.value = blob.value.copy(enabled = true) }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        val afterReenable = captureBackground()

        assertSamePixels(beforeDisable, afterReenable)
    }

    private fun captureBackground(): PixelMap = composeRule.onNodeWithTag("bg").captureToImage().toPixelMap()

    private fun assertAllPixelsMatch(expectedArgb: Int, pixels: PixelMap) {
        for (x in 1 until pixels.width - 1) {
            for (y in 1 until pixels.height - 1) {
                assertEquals("Pixel ($x, $y)", expectedArgb, pixels[x, y].toArgb())
            }
        }
    }

    private fun assertSamePixels(expected: PixelMap, actual: PixelMap) {
        assertEquals(expected.width, actual.width)
        assertEquals(expected.height, actual.height)
        for (x in 0 until expected.width) {
            for (y in 0 until expected.height) {
                assertEquals("Pixel ($x, $y)", expected[x, y].toArgb(), actual[x, y].toArgb())
            }
        }
    }
}
