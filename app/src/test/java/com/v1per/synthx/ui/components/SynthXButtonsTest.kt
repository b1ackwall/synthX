package com.v1per.synthx.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.v1per.synthx.ui.components.ButtonPairArrangement.SideBySide
import com.v1per.synthx.ui.components.ButtonPairArrangement.Stacked
import com.v1per.synthx.ui.components.SynthXButtonWidth.Compact
import com.v1per.synthx.ui.components.SynthXButtonWidth.Dialog
import com.v1per.synthx.ui.components.SynthXButtonWidth.Standard
import com.v1per.synthx.ui.components.SynthXButtonWidth.Wide
import com.v1per.synthx.ui.testing.TestTimeouts
import com.v1per.synthx.ui.theme.Accent
import com.v1per.synthx.ui.theme.Background
import com.v1per.synthx.ui.theme.ButtonBorder
import com.v1per.synthx.ui.theme.Error
import com.v1per.synthx.ui.theme.SurfaceVariant
import com.v1per.synthx.ui.theme.TextPrimary
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/** The shared buttons' role colours, width tokens and pair layout, which every migrated call site relies on. */
class SynthXButtonsTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.jvm()

    private fun assertColors(role: SynthXButtonRole, container: Color, content: Color, border: Color?) {
        val colors = synthXButtonColors(role)
        assertEquals(container, colors.container, "$role container")
        assertEquals(content, colors.content, "$role label")
        assertEquals(border, colors.border, "$role border")
    }

    @Test fun eachRoleHasItsLockedColours() {
        assertColors(SynthXButtonRole.Primary, container = TextPrimary, content = Background, border = null)
        assertColors(
            SynthXButtonRole.Secondary,
            container = SurfaceVariant,
            content = TextPrimary,
            border = ButtonBorder,
        )
        assertColors(SynthXButtonRole.Tertiary, container = Color.Transparent, content = TextPrimary, border = null)
        assertColors(SynthXButtonRole.Destructive, container = Error, content = Background, border = null)
    }

    @Test fun theSecondaryBorderIsTextPrimaryAt38Percent() {
        assertEquals(TextPrimary.copy(alpha = 0.38f), ButtonBorder)
    }

    @Test fun everyRoleDrawsTheWholeButtonAt38PercentWhileDisabled() {
        SynthXButtonRole.entries.forEach { assertEquals(0.38f, synthXButtonColors(it).disabledAlpha, "$it") }
    }

    @Test fun noRoleResolvesToAccent() {
        SynthXButtonRole.entries.forEach { role ->
            val colors = synthXButtonColors(role)
            listOfNotNull(colors.container, colors.content, colors.border).forEach { color ->
                assertNotEquals(Accent, color, "$role paints Accent")
                assertNotEquals(Accent, color.copy(alpha = 1f), "$role paints Accent at a lower alpha")
            }
        }
    }

    @Test fun screenTokensAreFixedWidthsWithA48dpMinimumHeight() {
        assertEquals(112f, Compact.fixedWidth?.value)
        assertEquals(168f, Standard.fixedWidth?.value)
        assertEquals(240f, Wide.fixedWidth?.value)
        listOf(Compact, Standard, Wide).forEach { assertEquals(48f, it.minHeight.value, "$it height") }
    }

    @Test fun theDialogTokenFollowsTheLabelWithA40dpMinimumHeight() {
        assertNull(Dialog.fixedWidth)
        assertEquals(40f, Dialog.minHeight.value)
    }

    /** [gap] is the layout gap, [visible] the gap between the drawn buttons. */
    private class Expected(
        val arrangement: ButtonPairArrangement,
        val primary: Float,
        val secondary: Float,
        val gap: Float,
        val visible: Float,
        val centered: Boolean = false,
    )

    private fun assertLayout(layout: ButtonPairLayout, expected: Expected) {
        assertEquals(expected.arrangement, layout.arrangement, "arrangement")
        assertEquals(expected.primary, layout.primaryWidth.value, "primary width")
        assertEquals(expected.secondary, layout.secondaryWidth.value, "secondary width")
        assertEquals(expected.gap, layout.gap.value, "layout gap")
        assertEquals(expected.visible, layout.visibleGap.value, "visible gap")
        assertEquals(expected.centered, layout.centered, "centered")
    }

    @Test fun aPairWithAStandardOrWideButtonStacksBelow600dp() {
        listOf(360.dp, 599.dp).forEach {
            assertLayout(resolveButtonPairLayout(it, Wide, Wide), Expected(Stacked, 240f, 240f, 12f, 12f))
        }
        assertLayout(resolveButtonPairLayout(599.dp, Compact, Standard), Expected(Stacked, 168f, 168f, 12f, 12f))
    }

    @Test fun aPairWithAStandardOrWideButtonSitsSideBySideFrom600dp() {
        listOf(600.dp, 840.dp, Dp.Infinity).forEach {
            assertLayout(resolveButtonPairLayout(it, Wide, Wide), Expected(SideBySide, 240f, 240f, 12f, 12f))
        }
        assertLayout(resolveButtonPairLayout(600.dp, Compact, Standard), Expected(SideBySide, 112f, 168f, 12f, 12f))
    }

    @Test fun twoCompactButtonsSitSideBySideAndCentredOnEveryPhoneWidth() {
        listOf(280.dp, 360.dp, 599.dp, 236.dp).forEach {
            assertLayout(
                resolveButtonPairLayout(it, Compact, Compact),
                Expected(SideBySide, 112f, 112f, 12f, 12f, centered = true),
            )
        }
    }

    @Test fun twoCompactButtonsStackOnlyWhenBothDoNotFit() {
        assertLayout(
            resolveButtonPairLayout(235.dp, Compact, Compact),
            Expected(Stacked, 112f, 112f, 12f, 12f, centered = true),
        )
    }

    @Test fun aDialogPairThatFitsSitsSideBySideAtItsOwnWidths() {
        assertLayout(
            resolveButtonPairLayout(232.dp, Dialog, Dialog, 100.dp, 90.dp),
            Expected(SideBySide, 100f, 90f, 8f, 8f),
        )
    }

    @Test fun aStackedDialogGroupTakesTheWidestLabelAndShowsAn8dpGap() {
        // Each 40dp button reserves 4dp of tap area above and below, so the layout gap is 8 - 8 = 0.
        assertLayout(
            resolveButtonPairLayout(232.dp, Dialog, Dialog, 147.dp, 146.dp),
            Expected(Stacked, 147f, 147f, 0f, 8f),
        )
    }

    @Test fun dialogButtonsNeverGoBelow64dp() {
        assertLayout(
            resolveButtonPairLayout(232.dp, Dialog, Dialog, 40.dp, 30.dp),
            Expected(SideBySide, 64f, 64f, 8f, 8f),
        )
        assertLayout(resolveButtonPairLayout(100.dp, Dialog, Dialog, 40.dp, 50.dp), Expected(Stacked, 64f, 64f, 0f, 8f))
    }

    @Test fun aStackedDialogGroupIsCappedAtTheAvailableWidth() {
        assertLayout(
            resolveButtonPairLayout(232.dp, Dialog, Dialog, 300.dp, 120.dp),
            Expected(Stacked, 232f, 232f, 0f, 8f),
        )
    }

    @Test fun aPairMixingDialogAndScreenTokensIsRejected() {
        assertFailsWith<IllegalArgumentException> { resolveButtonPairLayout(400.dp, Dialog, Compact, 80.dp, null) }
        assertFailsWith<IllegalArgumentException> { resolveButtonPairLayout(400.dp, Standard, Dialog, null, 80.dp) }
    }

    @Test fun aDialogPairNeedsBothMeasuredWidths() {
        assertFailsWith<IllegalArgumentException> { resolveButtonPairLayout(400.dp, Dialog, Dialog, 80.dp, null) }
    }
}
