package com.v1per.synthx.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Sizes for the Library's Recently Added tile grid (ui/screens/library/RecentTileGrid.kt). A tile
 * fills its row up to [MaxWidth]; width left over widens the gap from [MinGap] up to [MaxGap], and
 * anything beyond that is added to [SideMargin] on both sides.
 */
object TileSizes {
    /** The widest a Recently Added tile gets. */
    val MaxWidth = 168.dp

    /** The narrowest gap between two tiles, across and down. */
    val MinGap: Dp = Spacing.ItemSpacing

    /** The widest gap between two tiles, across and down. */
    val MaxGap: Dp = Spacing.lg

    /** The grid's side margin before any width left over beyond [MaxGap] is added to it. */
    val SideMargin: Dp = Spacing.HorizontalPadding
}
