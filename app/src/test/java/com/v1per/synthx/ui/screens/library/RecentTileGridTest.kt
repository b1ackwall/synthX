package com.v1per.synthx.ui.screens.library

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.v1per.synthx.ui.testing.TestTimeouts
import org.junit.Rule
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

class RecentTileGridTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.jvm()

    @Test
    fun `pins columns, tile, gap and edge at the reference widths`() {
        val references = listOf(
            Reference(280f, columns = 2, tile = 118f, gap = 12f, edge = 0f),
            Reference(320f, columns = 2, tile = 138f, gap = 12f, edge = 0f),
            Reference(360f, columns = 2, tile = 158f, gap = 12f, edge = 0f),
            Reference(392f, columns = 2, tile = 168f, gap = 24f, edge = 0f),
            Reference(411.43f, columns = 2, tile = 168f, gap = 24f, edge = 9.715f),
            Reference(412f, columns = 2, tile = 168f, gap = 24f, edge = 10f),
            Reference(480f, columns = 3, tile = 141.333f, gap = 12f, edge = 0f),
            Reference(520f, columns = 3, tile = 154.667f, gap = 12f, edge = 0f),
            Reference(600f, columns = 3, tile = 168f, gap = 24f, edge = 8f),
            Reference(720f, columns = 4, tile = 163f, gap = 12f, edge = 0f),
            Reference(840f, columns = 5, tile = 152f, gap = 12f, edge = 0f),
            Reference(1024f, columns = 6, tile = 155.333f, gap = 12f, edge = 0f),
            Reference(1280f, columns = 7, tile = 168f, gap = 12f, edge = 0f),
        )
        for (reference in references) {
            val grid = recentTileGrid(reference.width.dp)
            val at = "at ${reference.width}dp"
            assertEquals(reference.columns, grid.columns, "columns $at")
            assertDpEquals(reference.tile.dp, grid.tile, "tile $at")
            assertDpEquals(reference.gap.dp, grid.gap, "gap $at")
            assertDpEquals(reference.edge.dp, grid.edge, "edge $at")
        }
    }

    @Test
    fun `every width from 220 to 2000dp keeps the grid within its limits and filling the row`() {
        forEachSweepWidth { width ->
            val grid = recentTileGrid(width)
            val filled = grid.tile * grid.columns + grid.gap * (grid.columns - 1) + grid.edge * 2
            expect(grid.columns >= 2, width, "at least two columns")
            expect(grid.tile <= 168.dp, width, "tile at most 168dp")
            expect(grid.gap >= 12.dp && grid.gap <= 24.dp, width, "gap from 12 to 24dp")
            expect(grid.edge >= 0.dp, width, "edge never negative")
            expect(abs((filled - (width - 32.dp)).value) < SUM_TOLERANCE, width, "tiles, gaps and edges fill the row")
            if (grid.columns >= 3) {
                expect(grid.tile >= 138.dp - EPSILON.dp, width, "three or more columns keep 138dp tiles")
            }
            if (grid.tile < 120.dp) {
                expect(grid.columns == 2 && width < 284.dp, width, "under 120dp only at two columns below 284dp")
            }
            if (grid.edge > EPSILON.dp) expect(grid.gap == 24.dp, width, "an edge only once the gap is 24dp")
            if (grid.tile < 168.dp) {
                expect(grid.gap == 12.dp && grid.edge == 0.dp, width, "a tile under 168dp keeps gap 12dp, no edge")
            }
        }
    }

    @Test
    fun `a column is added at exactly 470, 650, 830, 1010, 1190 and 1370dp and never removed`() {
        val steps = mutableListOf<Float>()
        var previous = recentTileGrid(SWEEP_START.dp).columns
        forEachSweepWidth { width ->
            val columns = recentTileGrid(width).columns
            expect(columns >= previous, width, "column count never drops")
            if (columns > previous) {
                expect(columns == previous + 1, width, "one column at a time")
                if (width <= 1400.dp) steps += width.value
            }
            previous = columns
        }
        assertEquals(listOf(470f, 650f, 830f, 1010f, 1190f, 1370f), steps)
        for ((width, columns) in listOf(470f to 3, 650f to 4, 830f to 5, 1010f to 6, 1190f to 7, 1370f to 8)) {
            assertEquals(columns - 1, recentTileGrid((width - 0.01f).dp).columns, "just below ${width}dp")
            assertEquals(columns, recentTileGrid(width.dp).columns, "at ${width}dp")
        }
    }

    @Test
    fun `two columns are the real floor, so 280dp gets two 118dp tiles that still fit`() {
        val grid = recentTileGrid(280.dp)
        assertEquals(2, grid.columns)
        assertDpEquals(118.dp, grid.tile)
        assertDpEquals(12.dp, grid.gap)
        assertDpEquals(0.dp, grid.edge)
        assertDpEquals(280.dp - 32.dp, grid.tile * 2 + grid.gap)
    }

    @Test
    fun `an unbounded width is rejected rather than laid out`() {
        assertFailsWith<IllegalArgumentException> { recentTileGrid(Dp.Infinity) }
    }

    @Test
    fun `rows hand out every song index once, in order, as row times columns plus column`() {
        for (columns in 2..9) {
            for (count in 1..20) {
                val rows = recentTileRows(count, columns)
                val at = "$count songs in $columns columns"
                assertEquals((count + columns - 1) / columns, rows.size, "row count, $at")
                assertTrue(rows.last().size in 1..columns, "last row has 1 to $columns tiles, $at")
                rows.dropLast(1).forEach { assertEquals(columns, it.size, "full rows before the last, $at") }
                rows.forEachIndexed { row, indices ->
                    indices.forEachIndexed { column, index ->
                        assertEquals(row * columns + column, index, "row $row column $column, $at")
                    }
                }
                assertEquals((0 until count).toList(), rows.flatten(), "every index once in order, $at")
            }
        }
    }

    @Test
    fun `the skeleton shows three full rows, capped at the 20 songs the section lists`() {
        assertEquals(6, recentSkeletonTileCount(2))
        for (columns in 2..9) {
            val count = recentSkeletonTileCount(columns)
            assertEquals(minOf(3 * columns, 20), count, "skeleton tiles at $columns columns")
            assertTrue(recentTileRows(count, columns).size <= 3, "at most three skeleton rows at $columns columns")
        }
    }

    private data class Reference(val width: Float, val columns: Int, val tile: Float, val gap: Float, val edge: Float)

    /** Every width from 220 to 2000dp in 0.01dp steps, each one exact hundredths rather than a running sum. */
    private inline fun forEachSweepWidth(action: (Dp) -> Unit) {
        for (hundredths in (SWEEP_START * 100).toInt()..(SWEEP_END * 100).toInt()) action((hundredths / 100f).dp)
    }

    private fun expect(condition: Boolean, width: Dp, what: String) {
        if (!condition) fail("$what, failed at $width: ${recentTileGrid(width)}")
    }

    private fun assertDpEquals(expected: Dp, actual: Dp, message: String? = null) {
        assertEquals(expected.value, actual.value, absoluteTolerance = EPSILON, message = message)
    }

    private companion object {
        const val SWEEP_START = 220f
        const val SWEEP_END = 2000f
        const val EPSILON = 0.001f
        const val SUM_TOLERANCE = 0.01f
    }
}
