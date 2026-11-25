package com.v1per.synthx.ui.testing

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.v1per.synthx.ui.components.SynthXButtonRole
import com.v1per.synthx.ui.components.synthXButtonColors
import com.v1per.synthx.ui.theme.Accent
import com.v1per.synthx.ui.theme.Alpha
import com.v1per.synthx.ui.theme.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals

/** The SynthX button labelled [label]; a typed-confirmation field holding the same word is skipped. */
fun ComposeTestRule.dialogButton(label: String): SemanticsNodeInteraction =
    onNode(hasText(label) and hasClickAction() and !hasSetTextAction())

/**
 * Asserts that a button labelled [label] shows in [role]: enabled or not, its label painted in the role's
 * content colour (never Accent), and its fill, drawn over [backdrop] (a dialog's Surface by default), the
 * role's container colour ([backdrop] itself for the unfilled Tertiary), at the disabled alpha when [enabled]
 * is false.
 */
fun ComposeTestRule.assertDialogButton(
    label: String,
    role: SynthXButtonRole,
    enabled: Boolean = true,
    backdrop: Color = Surface,
) {
    val button = dialogButton(label).assertExists()
    if (enabled) button.assertIsEnabled() else button.assertIsNotEnabled()
    val colors = synthXButtonColors(role)

    val text = onNode(
        hasText(label) and !hasSetTextAction() and hasAnyAncestor(hasClickAction()),
        useUnmergedTree = true,
    ).fetchSemanticsNode()
    val layouts = mutableListOf<TextLayoutResult>()
    text.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
    val labelColor = layouts.first().layoutInput.style.color
    assertNotEquals("'$label' paints Accent", Accent, labelColor)
    assertEquals("'$label' label colour for $role", colors.content, labelColor)

    val container = if (colors.container == Color.Transparent) backdrop else colors.container
    val fill = if (enabled) container else container.over(backdrop, Alpha.Disabled)
    val x = with(density) { FILL_INSET.roundToPx() }
    assertPixelEventually("'$label' fill for $role", fill, capture = { dialogButton(label).captureToImage() }) {
        IntOffset(x, it.height / 2)
    }
}

/** Just inside a button's start edge: clear of the 1dp border and of the centred label. */
private val FILL_INSET = 6.dp
