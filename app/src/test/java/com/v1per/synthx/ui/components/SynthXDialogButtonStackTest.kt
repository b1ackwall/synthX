package com.v1per.synthx.ui.components

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.v1per.synthx.ui.testing.TestTimeouts
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The dialog button stack's one shared width and its gaps, which the emergency restore dialog relies on. */
class SynthXDialogButtonStackTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.jvm()

    private fun assertStack(layout: DialogButtonStackLayout, width: Float) {
        assertEquals(width, layout.width.value, "shared width")
        // Each 40dp button reserves 4dp of tap area above and below, so the layout gap is 8 - 8 = 0.
        assertEquals(0f, layout.gap.value, "layout gap")
        assertEquals(8f, layout.visibleGap.value, "visible gap")
    }

    @Test fun everyButtonTakesTheWidestLabel() {
        assertStack(resolveDialogButtonStackLayout(232.dp, listOf(144.2.dp, 165.8.dp, 82.7.dp)), 165.8f)
    }

    @Test fun theOrderOfTheButtonsDoesNotChangeTheWidth() {
        assertStack(resolveDialogButtonStackLayout(232.dp, listOf(82.7.dp, 144.2.dp, 165.8.dp)), 165.8f)
    }

    @Test fun shortLabelsStillGetThe64dpMinimum() {
        assertStack(resolveDialogButtonStackLayout(232.dp, listOf(40.dp, 50.dp)), 64f)
    }

    @Test fun aLoneButtonIsItsOwnWidth() {
        assertStack(resolveDialogButtonStackLayout(232.dp, listOf(120.dp)), 120f)
    }

    @Test fun theStackIsCappedAtTheAvailableWidth() {
        assertStack(resolveDialogButtonStackLayout(150.dp, listOf(165.8.dp, 82.7.dp)), 150f)
    }

    @Test fun anUnboundedRowKeepsTheWidestLabel() {
        assertStack(resolveDialogButtonStackLayout(Dp.Infinity, listOf(165.8.dp, 82.7.dp)), 165.8f)
    }

    @Test fun anEmptyStackIsRejected() {
        assertFailsWith<IllegalArgumentException> { resolveDialogButtonStackLayout(232.dp, emptyList()) }
    }
}
