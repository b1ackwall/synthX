package com.v1per.synthx.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
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
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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

    // P8-T6: the 40dp list row and the 160dp detail cover now draw the same playlist. The process-wide
    // model cache is keyed on size, so neither may hand the other's model back or disturb it.

    @Test fun rowAndDetailSizesGetSeparateCachedModels() {
        val density = 2.75f
        val rowPx = 40 * density
        val detailPx = 160 * density

        val row = cachedPlaylistArtRenderModel(120, 42L, rowPx)
        val detail = cachedPlaylistArtRenderModel(120, 42L, detailPx)

        assertNotSame("a second size must not be served the first size's model", row, detail)
        assertSame("the row's entry must survive the detail cover being built", row, cachedPlaylistArtRenderModel(120, 42L, rowPx))
        assertSame(detail, cachedPlaylistArtRenderModel(120, 42L, detailPx))
        // Geometry is scaled per size from the same reference space, so a 4x edge is a 4x stroke.
        assertEquals(4f, detail.strokeWidth / row.strokeWidth, 0.05f)
        assertEquals(row.lit.size, detail.lit.size)
        assertEquals(4f, detail.lit.first().end.x / row.lit.first().end.x, 0.05f)
    }

    @Test fun drawingTheDetailCoverBesideTheRowCoverLeavesTheRowPixelsUnchanged() {
        val showDetail = mutableStateOf(false)
        composeRule.setContent {
            Row {
                Box(Modifier.testTag("row")) { PlaylistArt(hue = 200, structureSeed = 77L, size = 40.dp) }
                if (showDetail.value) {
                    Box(Modifier.testTag("detail")) { PlaylistArt(hue = 200, structureSeed = 77L, size = 160.dp) }
                }
            }
        }
        composeRule.waitForIdle()
        val before = composeRule.onNodeWithTag("row").captureToImage().toPixelMap()

        composeRule.runOnIdle { showDetail.value = true }
        composeRule.onNodeWithTag("detail").assertIsDisplayed()
        val after = composeRule.onNodeWithTag("row").captureToImage().toPixelMap()
        val detail = composeRule.onNodeWithTag("detail").captureToImage().toPixelMap()

        for (x in 0 until before.width) for (y in 0 until before.height) {
            assertEquals("row pixel ($x,$y) changed", before[x, y].toArgb(), after[x, y].toArgb())
        }
        var generated = 0
        for (x in 0 until detail.width step 3) for (y in 0 until detail.height step 3) {
            if (detail[x, y].toArgb() != SurfaceVariant.toArgb()) generated++
        }
        assertTrue("the 160dp cover should be generated art", generated > 100)
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
