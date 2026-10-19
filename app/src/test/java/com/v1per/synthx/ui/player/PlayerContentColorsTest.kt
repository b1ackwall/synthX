package com.v1per.synthx.ui.player

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import com.v1per.synthx.playback.BackgroundBrightness
import com.v1per.synthx.playback.BrightnessStats
import com.v1per.synthx.ui.testing.TestTimeouts
import com.v1per.synthx.ui.theme.Alpha
import com.v1per.synthx.ui.theme.Background
import com.v1per.synthx.ui.theme.TextPrimary
import org.junit.Rule
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PlayerContentColorsTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.jvm()

    private fun withMedian(median: Float) = BackgroundBrightness(
        wcag = BrightnessStats(mean = 0.9f, median = median, p90 = 0.9f),
        oklab = BrightnessStats(mean = 0.9f, median = 0.9f, p90 = 0.9f),
    )

    @Test fun lightIsTextPrimaryAndDarkIsBackground() {
        assertEquals(TextPrimary, PlayerContentColors.Light.content)
        assertEquals(Background, PlayerContentColors.Dark.content)
    }

    @Test fun offIsTheContentColorAtSixtyPercent() {
        assertEquals(TextPrimary.copy(alpha = 0.6f), PlayerContentColors.Light.off)
        assertEquals(Background.copy(alpha = 0.6f), PlayerContentColors.Dark.off)
    }

    @Test fun disabledIsTheContentColorAtThirtyEightPercent() {
        assertEquals(TextPrimary.copy(alpha = 0.38f), PlayerContentColors.Light.disabled)
        assertEquals(Background.copy(alpha = 0.38f), PlayerContentColors.Dark.disabled)
    }

    @Test fun alphaTokensHaveTheAgreedValues() {
        assertEquals(0.6f, Alpha.ToggleOff)
        assertEquals(0.25f, Alpha.SeekTrackUnplayed)
        assertEquals(0.75f, Alpha.SeekTrackPlayed)
        assertEquals(0.38f, Alpha.Disabled)
    }

    @Test fun exactlyAtTheThresholdStaysLight() {
        assertSame(PlayerContentColors.Light, playerContentColorsFor(withMedian(0.32f)))
    }

    @Test fun justAboveTheThresholdGoesDark() {
        assertSame(PlayerContentColors.Dark, playerContentColorsFor(withMedian(Math.nextUp(0.32f))))
    }

    @Test fun noBrightnessStaysLight() {
        assertSame(PlayerContentColors.Light, playerContentColorsFor(null))
    }

    @Test fun localDefaultsToNullOutsideTheProvider() {
        val recomposer = Recomposer(EmptyCoroutineContext)
        val composition = Composition(NoOpApplier(), recomposer)
        var composed = false
        var read: PlayerContentColors? = PlayerContentColors.Light
        try {
            composition.setContent {
                read = LocalPlayerContentColors.current
                composed = true
            }
            assertTrue(composed, "The initial composition never ran.")
            assertNull(read)
        } finally {
            composition.dispose()
            recomposer.cancel()
        }
    }

    /** No nodes are emitted; the composition only exists to read a CompositionLocal. */
    private class NoOpApplier : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun onClear() = Unit
    }
}
