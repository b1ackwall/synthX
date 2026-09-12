package com.v1per.synthx.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.ui.theme.SurfaceVariant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [PlaylistArt] runs [Color.hsl] on a persisted `hue` during composition - this proves an
 * out-of-range value (a hand-edited row, a restore from a divergent writer) falls back to
 * [ArtPlaceholder] instead of throwing, the same as a missing hue does today.
 *
 * Compose's own [androidx.compose.ui.graphics.Color.hsl] precondition is pure JVM math with no
 * Android-framework dependency, but rendering [PlaylistArt] itself (Canvas draw calls, density,
 * MaterialTheme shapes) needs a real composition - hence instrumented rather than a local unit
 * test, matching the pattern in ui/player/StoredPaletteBackgroundTest.kt. A single `setContent`
 * driven by mutable state (rather than calling `setContent` again per case) matches that same
 * file's pattern for exercising several inputs against one composition.
 */
@RunWith(AndroidJUnit4::class)
class PlaylistArtInstrumentedTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun outOfRangeHueFallsBackToPlaceholderInsteadOfThrowing() {
        val hue = mutableStateOf(120)
        composeRule.setContent {
            Box(Modifier.testTag("art")) {
                PlaylistArt(hue = hue.value, structureSeed = 1L, size = 40.dp)
            }
        }
        composeRule.waitForIdle()
        assertGeneratedArt()

        for (badHue in listOf(400, -10, 360)) {
            composeRule.runOnIdle { hue.value = badHue }
            assertFlatPlaceholder()
        }

        // Recovers to generated art once back in range - proves the guard isn't sticky/latched.
        composeRule.runOnIdle { hue.value = 120 }
        assertGeneratedArt()
    }

    private fun assertGeneratedArt() {
        // Generated art is not a flat SurfaceVariant fill - some pixel must differ.
        val pixels = composeRule.onNodeWithTag("art").captureToImage().toPixelMap()
        var anyDifferent = false
        for (x in 0 until pixels.width) {
            for (y in 0 until pixels.height) {
                if (pixels[x, y].toArgb() != SurfaceVariant.toArgb()) anyDifferent = true
            }
        }
        assertEquals(true, anyDifferent)
    }

    private fun assertFlatPlaceholder() {
        // ArtPlaceholder clips to MaterialTheme.shapes.small, so a corner pixel can legitimately
        // fall outside the rounded rect at high density - only the center is reliably interior.
        val pixels = composeRule.onNodeWithTag("art").captureToImage().toPixelMap()
        assertEquals(SurfaceVariant.toArgb(), pixels[pixels.width / 2, pixels.height / 2].toArgb())
    }
}
