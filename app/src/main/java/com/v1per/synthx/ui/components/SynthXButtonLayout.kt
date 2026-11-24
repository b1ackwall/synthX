package com.v1per.synthx.ui.components

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MultiContentMeasurePolicy
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.min
import com.v1per.synthx.ui.theme.ButtonSizes

/**
 * A button's width token. Compact, Standard and Wide are fixed screen widths with a 48dp minimum
 * height; Dialog is as wide as its label (at least [ButtonSizes.DialogMinWidth]) with a 40dp
 * minimum height. [fixedWidth] is null for Dialog.
 */
enum class SynthXButtonWidth(val fixedWidth: Dp?, val minHeight: Dp) {
    Compact(ButtonSizes.Compact, ButtonSizes.ScreenMinHeight),
    Standard(ButtonSizes.Standard, ButtonSizes.ScreenMinHeight),
    Wide(ButtonSizes.Wide, ButtonSizes.ScreenMinHeight),
    Dialog(null, ButtonSizes.DialogMinHeight),
}

/** How a [SynthXButtonPair] places its two buttons. */
enum class ButtonPairArrangement { SideBySide, Stacked }

/**
 * The result of [resolveButtonPairLayout]. [gap] is the layout space between the two buttons'
 * layout boxes; [visibleGap] is the space between their drawn edges, which is larger when a
 * button's 48dp tap area extends past its drawn height. [centered] pairs (every screen pair) take
 * the whole available width and centre the buttons in it; Dialog pairs are as wide as their
 * buttons.
 */
@Immutable
data class ButtonPairLayout(
    val arrangement: ButtonPairArrangement,
    val primaryWidth: Dp,
    val secondaryWidth: Dp,
    val gap: Dp,
    val visibleGap: Dp,
    val centered: Boolean,
)

/**
 * Decides how a pair of buttons is laid out in [availableWidth] ([Dp.Infinity] when unbounded).
 *
 * Every screen pair is centred in [availableWidth], stacked or side by side, with a
 * [ButtonSizes.ScreenGap] gap that is drawn as well as laid out. Two Compact buttons sit side by
 * side whenever both fit and stack only when the width cannot hold both. Other screen pairs (with
 * a Standard or Wide button) sit side by side when [availableWidth] is at least
 * [ButtonSizes.PairSideBySideMinWidth] and both fit, and stack otherwise. Side by side, each
 * button keeps its own fixed width; stacked, both take the wider of the two.
 *
 * Dialog pairs need each button's measured natural width (label plus padding), floored at
 * [ButtonSizes.DialogMinWidth]. They sit side by side, [ButtonSizes.DialogGap] apart, when both
 * fit; otherwise they stack, both at the wider width, capped at [availableWidth]. Stacked, the
 * layout gap is [ButtonSizes.StackedDialogLayoutGap], because each button's tap-area reserve already
 * separates the drawn buttons; the visible gap stays [ButtonSizes.DialogGap].
 *
 * @throws IllegalArgumentException when the pair mixes Dialog with a screen width, or a Dialog
 *   button's measured width is missing.
 */
fun resolveButtonPairLayout(
    availableWidth: Dp,
    primary: SynthXButtonWidth,
    secondary: SynthXButtonWidth,
    primaryMeasuredWidth: Dp? = null,
    secondaryMeasuredWidth: Dp? = null,
): ButtonPairLayout {
    val primaryFixed = primary.fixedWidth
    val secondaryFixed = secondary.fixedWidth
    if (primaryFixed != null && secondaryFixed != null) {
        val bothCompact = primary == SynthXButtonWidth.Compact && secondary == SynthXButtonWidth.Compact
        return screenPairLayout(availableWidth, primaryFixed, secondaryFixed, bothCompact)
    }
    require(primaryFixed == null && secondaryFixed == null) {
        "A button pair is two Dialog buttons or two screen buttons, not $primary with $secondary"
    }
    return dialogPairLayout(
        availableWidth = availableWidth,
        primary = requireNotNull(primaryMeasuredWidth) { "The primary Dialog button's measured width is missing" },
        secondary = requireNotNull(secondaryMeasuredWidth) {
            "The secondary Dialog button's measured width is missing"
        },
    )
}

private fun screenPairLayout(availableWidth: Dp, primary: Dp, secondary: Dp, bothCompact: Boolean): ButtonPairLayout {
    val gap = ButtonSizes.ScreenGap
    val fits = primary + gap + secondary <= availableWidth
    val sideBySide = fits && (bothCompact || availableWidth >= ButtonSizes.PairSideBySideMinWidth)
    if (sideBySide) {
        return ButtonPairLayout(ButtonPairArrangement.SideBySide, primary, secondary, gap, gap, centered = true)
    }
    val shared = max(primary, secondary)
    return ButtonPairLayout(ButtonPairArrangement.Stacked, shared, shared, gap, gap, centered = true)
}

private fun dialogPairLayout(availableWidth: Dp, primary: Dp, secondary: Dp): ButtonPairLayout {
    val primaryWidth = max(primary, ButtonSizes.DialogMinWidth)
    val secondaryWidth = max(secondary, ButtonSizes.DialogMinWidth)
    val gap = ButtonSizes.DialogGap
    if (primaryWidth + gap + secondaryWidth <= availableWidth) {
        return ButtonPairLayout(
            ButtonPairArrangement.SideBySide,
            primaryWidth,
            secondaryWidth,
            gap,
            gap,
            centered = false,
        )
    }
    val shared = min(max(primaryWidth, secondaryWidth), availableWidth)
    return ButtonPairLayout(
        arrangement = ButtonPairArrangement.Stacked,
        primaryWidth = shared,
        secondaryWidth = shared,
        gap = ButtonSizes.StackedDialogLayoutGap,
        visibleGap = gap,
        centered = false,
    )
}

/**
 * Applies [token]'s width and minimum height: a fixed width for the screen tokens, the label's
 * width with a [ButtonSizes.DialogMinWidth] floor for Dialog. Never fills the available width.
 */
internal fun Modifier.buttonSize(token: SynthXButtonWidth): Modifier {
    val fixed = token.fixedWidth
    val sized = if (fixed != null) this.width(fixed) else this.widthIn(min = ButtonSizes.DialogMinWidth)
    return sized.heightIn(min = token.minHeight)
}

/**
 * Two buttons laid out by [resolveButtonPairLayout] in the width this pair is given.
 *
 * [primary] is the emphasised button (Primary or Destructive): at the end when side by side, on
 * top when stacked. [secondary] is the other one. Each slot must emit exactly one SynthX button,
 * which hands its width token to the pair; do not set a layoutId on it. The pair measures each
 * button at the resolved width, so stacked buttons share one width. Side by side, a shorter button
 * is centred vertically against a taller one.
 *
 * A screen pair (Compact, Standard or Wide) fills the available width and centres its buttons,
 * stacked or side by side; a Dialog pair is as wide as its buttons. Stacked Dialog buttons are
 * drawn [ButtonSizes.DialogGap] apart.
 */
@Composable
fun SynthXButtonPair(
    primary: @Composable () -> Unit,
    secondary: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Layout(contents = listOf(primary, secondary), modifier = modifier, measurePolicy = ButtonPairMeasurePolicy)
}

private val ButtonPairMeasurePolicy = MultiContentMeasurePolicy { measurables, constraints ->
    val primary = measurables[0].singleButton("primary")
    val secondary = measurables[1].singleButton("secondary")
    val primaryToken = primary.widthToken("primary")
    val secondaryToken = secondary.widthToken("secondary")
    val resolved = resolveButtonPairLayout(
        availableWidth = if (constraints.hasBoundedWidth) constraints.maxWidth.toDp() else Dp.Infinity,
        primary = primaryToken,
        secondary = secondaryToken,
        primaryMeasuredWidth = primary.measuredWidthIfDialog(primaryToken)?.toDp(),
        secondaryMeasuredWidth = secondary.measuredWidthIfDialog(secondaryToken)?.toDp(),
    )
    val primaryPlaceable = primary.measure(constraints.atWidth(resolved.primaryWidth.roundToPx()))
    val secondaryPlaceable = secondary.measure(constraints.atWidth(resolved.secondaryWidth.roundToPx()))
    val gap = resolved.gap.roundToPx()
    val sideBySide = resolved.arrangement == ButtonPairArrangement.SideBySide
    val contentWidth = if (sideBySide) {
        secondaryPlaceable.width + gap + primaryPlaceable.width
    } else {
        maxOf(primaryPlaceable.width, secondaryPlaceable.width)
    }
    val width = if (resolved.centered && constraints.hasBoundedWidth) constraints.maxWidth else contentWidth
    val start = ((width - contentWidth) / 2).coerceAtLeast(0)
    if (sideBySide) {
        val height = maxOf(primaryPlaceable.height, secondaryPlaceable.height)
        layout(constraints.constrainWidth(width), constraints.constrainHeight(height)) {
            secondaryPlaceable.place(start, (height - secondaryPlaceable.height) / 2)
            primaryPlaceable.place(start + secondaryPlaceable.width + gap, (height - primaryPlaceable.height) / 2)
        }
    } else {
        val height = primaryPlaceable.height + gap + secondaryPlaceable.height
        layout(constraints.constrainWidth(width), constraints.constrainHeight(height)) {
            primaryPlaceable.place(start + (contentWidth - primaryPlaceable.width) / 2, 0)
            secondaryPlaceable.place(
                start + (contentWidth - secondaryPlaceable.width) / 2,
                primaryPlaceable.height + gap,
            )
        }
    }
}

private fun List<Measurable>.singleButton(slot: String): Measurable {
    require(size == 1) { "SynthXButtonPair's $slot slot must emit exactly one SynthX button, not $size layout nodes" }
    return first()
}

private fun Measurable.widthToken(slot: String): SynthXButtonWidth = requireNotNull(layoutId as? SynthXButtonWidth) {
    "SynthXButtonPair's $slot slot holds no SynthX button width token (found layoutId $layoutId); use a " +
        "SynthXPrimaryButton, SynthXSecondaryButton, SynthXTextButton or SynthXDestructiveButton without its own layoutId"
}

private fun Measurable.measuredWidthIfDialog(token: SynthXButtonWidth): Int? =
    if (token == SynthXButtonWidth.Dialog) maxIntrinsicWidth(Constraints.Infinity) else null

private fun Constraints.atWidth(width: Int): Constraints =
    Constraints(minWidth = width, maxWidth = width, minHeight = 0, maxHeight = maxHeight)
