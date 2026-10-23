package com.v1per.synthx.ui.player

import com.v1per.synthx.ui.testing.TestTimeouts
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerAdaptiveTextTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.jvm()

    private fun withMedian(median: Float) = median

    private fun dark(brightness: Float?) = useDarkPlayerTitle(
        enabled = PlayerAltStageStyle.AdaptiveTextEnabled,
        brightness = brightness,
        threshold = PlayerAltStageStyle.AdaptiveTextThreshold,
    )

    @Test fun lockedConstantsEqualTheTunedValues() {
        assertEquals(true, PlayerAltStageStyle.AdaptiveTextEnabled)
        assertEquals(0.32f, PlayerAltStageStyle.AdaptiveTextThreshold)
    }

    @Test fun equalToTheThresholdStaysLight() {
        assertEquals(false, dark(withMedian(0.32f)))
    }

    @Test fun justAboveTheThresholdGoesDark() {
        assertEquals(true, dark(withMedian(Math.nextUp(0.32f))))
        assertEquals(true, dark(withMedian(0.5f)))
    }

    @Test fun belowTheThresholdStaysLight() {
        assertEquals(false, dark(withMedian(0.1f)))
    }

    @Test fun noValueStaysLight() {
        assertEquals(false, dark(null))
    }

    @Test fun titleAndArtistAreTextPrimaryOrBackground() {
        // Title and artist both draw PlayerContentColors.content, so one mapping covers both.
        assertEquals(com.v1per.synthx.ui.theme.TextPrimary, playerContentColorsFor(withMedian(0.1f)).content)
        assertEquals(com.v1per.synthx.ui.theme.TextPrimary, playerContentColorsFor(null).content)
        assertEquals(com.v1per.synthx.ui.theme.Background, playerContentColorsFor(withMedian(0.5f)).content)
    }
}
