package com.v1per.synthx.ui.player

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import com.v1per.synthx.ui.testing.TestTimeouts
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerSheetMotionTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.jvm()

    private fun expandedContentAlpha(progress: Float) = ((progress - 0.4f) * (1f / 0.6f)).coerceIn(0f, 1f)

    private fun motion(progress: Float, sheetOffsetPx: Float = 0f, expandedAnchorPx: Float = 0f) = playerSheetMotion(
        progress = progress,
        expandedContentAlpha = expandedContentAlpha(progress),
        sheetOffsetPx = sheetOffsetPx,
        expandedAnchorPx = expandedAnchorPx,
        density = Density(2f),
    )

    private fun assertAlpha(expected: Float, actual: Float, message: String) =
        assertEquals(expected, actual, 1e-4f, message)

    @Test fun titleFadesAcrossItsLockedWindow() {
        assertEquals(0.8f, PlayerTitleFadeStart)
        assertEquals(1f, PlayerTitleFadeEnd)
        for ((progress, alpha) in listOf(0f to 0f, 0.8f to 0f, 0.85f to 0.25f, 0.95f to 0.75f, 1f to 1f)) {
            assertAlpha(alpha, motion(progress).titleAlpha, "title alpha at $progress")
        }
        assertEquals(false, motion(0.89f).titleEnabled)
        assertEquals(true, motion(0.91f).titleEnabled)
    }

    @Test fun overlayFadesAcrossItsLockedWindow() {
        assertEquals(0.9f, PlayerOverlayFadeStart)
        assertEquals(1f, PlayerOverlayFadeEnd)
        for ((progress, alpha) in listOf(0f to 0f, 0.9f to 0f, 0.925f to 0.25f, 0.975f to 0.75f, 1f to 1f)) {
            assertAlpha(alpha, motion(progress).overlayAlpha, "overlay alpha at $progress")
        }
        assertEquals(false, motion(0.94f).overlayEnabled)
        assertEquals(true, motion(0.96f).overlayEnabled)
    }

    @Test fun queueFadesAcrossItsLockedWindow() {
        assertEquals(0.7f, PlayerQueueFadeStart)
        assertEquals(1f, PlayerQueueFadeEnd)
        for ((progress, alpha) in listOf(
            0f to 0f,
            0.7f to 0f,
            0.775f to 0.25f,
            0.85f to 0.5f,
            0.925f to 0.75f,
            1f to 1f,
        )) {
            assertAlpha(alpha, motion(progress).queueFadeAlpha, "queue alpha at $progress")
        }
    }

    @Test fun maskStrengthIsTheOverlayAlphaTimesItsScrollHide() {
        for (step in 0..100) {
            val progress = step / 100f
            val actual = motion(progress)
            for (visible in listOf(0f, 0.3f, 1f)) {
                assertEquals(actual.overlayAlpha * visible, actual.maskStrength(visible), "at $progress, $visible")
            }
        }
        // Not the sheet's expanded-content alpha, which is already most of the way in here.
        assertEquals(0f, motion(0.9f).maskStrength(1f))
        assertAlpha(0.5f, motion(0.95f).maskStrength(1f), "mask strength at 0.95")
    }

    @Test fun overlaySlidesByTheSheetOffsetFromTheExpandedAnchor() {
        assertEquals(150.dp, motion(0.7f, sheetOffsetPx = 320f, expandedAnchorPx = 20f).overlaySlide)
        assertEquals(0.dp, motion(1f, sheetOffsetPx = 0f).overlaySlide)
        assertEquals(0.dp, motion(1f, sheetOffsetPx = -10f).overlaySlide)
        // No anchors yet: the 40dp slide stands in.
        assertEquals(ContentSlideDistance, motion(0f, sheetOffsetPx = Float.NaN).overlaySlide)
        assertEquals(ContentSlideDistance, motion(0f, expandedAnchorPx = Float.NaN).overlaySlide)
        assertEquals(
            lerp(ContentSlideDistance, 0.dp, expandedContentAlpha(0.7f)),
            motion(0.7f, sheetOffsetPx = Float.NaN).overlaySlide,
        )
    }
}
