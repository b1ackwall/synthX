package com.v1per.synthx.ui.screens.onboarding

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.v1per.synthx.ui.testing.TestTimeouts
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals

/** The welcome wordmark is 40% of the screen width, capped at 240dp. */
class OnboardingWordmarkWidthTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.jvm()

    private fun assertWidth(expected: Dp, available: Dp) =
        assertEquals(expected.value, onboardingWordmarkWidth(available).value, 0.01f, "at $available")

    @Test fun phonesGetFortyPercentOfTheWidth() {
        assertWidth(144.dp, 360.dp)
        assertWidth(164.4.dp, 411.dp)
    }

    @Test fun theCapIsReachedAt600dp() {
        assertWidth(240.dp, 600.dp)
        assertWidth(236.dp, 590.dp)
    }

    @Test fun widerScreensStayAtTheCap() {
        assertWidth(240.dp, 840.dp)
        assertWidth(240.dp, 1280.dp)
    }
}
