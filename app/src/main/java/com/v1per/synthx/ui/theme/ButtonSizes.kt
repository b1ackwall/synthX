package com.v1per.synthx.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max

/**
 * Sizes for the shared buttons in ui/components/SynthXButtons.kt and the player's toggles in
 * ui/components/PlayerToggleButton.kt. The three screen widths are fixed: a button keeps its width
 * whatever its label, and a label that does not fit wraps onto more lines while the height grows past
 * its minimum.
 */
object ButtonSizes {
    /** The fixed width of a Compact screen button. */
    val Compact = 112.dp

    /** The fixed width of a Standard screen button. */
    val Standard = 168.dp

    /** The fixed width of a Wide screen button. */
    val Wide = 240.dp

    /** The narrowest a Dialog button gets; above this it is as wide as its label. */
    val DialogMinWidth = 64.dp

    /** The minimum height of a Compact, Standard or Wide button. */
    val ScreenMinHeight = 48.dp

    /** The minimum visible height of a Dialog button. Its tap area is still 48dp tall. */
    val DialogMinHeight = 40.dp

    /** The space between the label and the button's start and end edges. */
    val HorizontalPadding: Dp = Spacing.lg

    /** The gap between the two buttons of a screen pair, side by side or stacked. */
    val ScreenGap: Dp = Spacing.ItemSpacing

    /** The visible gap between the two buttons of a dialog pair, side by side or stacked. */
    val DialogGap: Dp = Spacing.sm

    /**
     * The extra height the 48dp minimum tap area adds around a 40dp Dialog button, split evenly
     * above and below it.
     */
    val DialogTapAreaReserve: Dp = ScreenMinHeight - DialogMinHeight

    /**
     * The layout gap between two stacked Dialog buttons. The tap-area reserve below the upper button
     * and above the lower one already counts towards [DialogGap], so only the rest is added.
     */
    val StackedDialogLayoutGap: Dp = max(DialogGap - DialogTapAreaReserve, 0.dp)

    /**
     * The available width from which a screen pair with a Standard or Wide button sits side by side;
     * below it the pair stacks. Two Compact buttons ignore it.
     */
    val PairSideBySideMinWidth = 600.dp

    /** The edge of a player toggle (shuffle, repeat, queue): drawn and tapped at this square size. */
    val PlayerToggle = 48.dp
}
