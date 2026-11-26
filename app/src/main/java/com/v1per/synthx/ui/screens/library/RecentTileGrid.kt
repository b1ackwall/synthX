package com.v1per.synthx.ui.screens.library

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isFinite
import androidx.compose.ui.unit.min
import com.v1per.synthx.ui.theme.TileSizes
import kotlin.math.roundToInt

/**
 * The Recently Added grid at one width: [columns] tiles of [tile] width, [gap] between tiles across
 * and down, and [edge] added to [TileSizes.SideMargin] on each side.
 */
internal data class RecentTileGrid(val columns: Int, val tile: Dp, val gap: Dp, val edge: Dp)

/**
 * The grid for [available], the section's whole width before its side margins. The column count is
 * the one whose tiles come nearest to [TileSizes.MaxWidth] at the narrowest gap, and never fewer than
 * two. Tiles fill the row up to [TileSizes.MaxWidth]; width left over first widens the gap up to
 * [TileSizes.MaxGap], and anything beyond that becomes [RecentTileGrid.edge]. There is no minimum
 * tile width: below about 284dp the two tiles drop under 120dp rather than overflow the row.
 */
internal fun recentTileGrid(available: Dp): RecentTileGrid {
    require(available.isFinite) { "The Recently Added grid needs a bounded width, got $available" }
    val content = available - TileSizes.SideMargin * 2
    // roundToInt rounds a tie up: at 470dp the ratio is exactly 2.5, which must give three columns.
    val nearest = ((content + TileSizes.MinGap) / (TileSizes.MaxWidth + TileSizes.MinGap)).roundToInt()
    val columns = maxOf(MinColumns, nearest)
    val gaps = columns - 1
    // What is left once every tile is at its widest and every gap at its narrowest.
    val spare = content - TileSizes.MaxWidth * columns - TileSizes.MinGap * gaps
    if (spare <= 0.dp) {
        val fill = (content - TileSizes.MinGap * gaps) / columns
        return RecentTileGrid(columns, min(fill, TileSizes.MaxWidth), TileSizes.MinGap, edge = 0.dp)
    }

    val widening = (TileSizes.MaxGap - TileSizes.MinGap) * gaps
    return if (spare <= widening) {
        RecentTileGrid(columns, TileSizes.MaxWidth, TileSizes.MinGap + spare / gaps, edge = 0.dp)
    } else {
        RecentTileGrid(columns, TileSizes.MaxWidth, TileSizes.MaxGap, edge = (spare - widening) / 2)
    }
}

/**
 * The song indices of each row of [columns] tiles, in reading order: row r, column c holds index
 * r * columns + c. Every row is full except possibly the last.
 */
internal fun recentTileRows(count: Int, columns: Int): List<List<Int>> = (0 until count).chunked(columns)

/** How many skeleton tiles to show at [columns]: three full rows, but no more than the section lists. */
internal fun recentSkeletonTileCount(columns: Int): Int = minOf(columns * SkeletonRows, SkeletonMaxTiles)

private const val MinColumns = 2
private const val SkeletonRows = 3

/** The most songs Recently Added lists: LibraryViewModel's recent-songs limit. */
private const val SkeletonMaxTiles = 20
