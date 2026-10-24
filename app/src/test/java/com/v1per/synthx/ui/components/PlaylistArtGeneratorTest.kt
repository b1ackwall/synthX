package com.v1per.synthx.ui.components

import com.v1per.synthx.ui.testing.TestTimeouts
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaylistArtGeneratorTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.jvm()

    @Test
    fun `the grid holds one segment per cell corner, indexed in build order`() {
        assertEquals(ROWS * COLS * 4, PlaylistArtGeometry.segments.size)

        val seen = mutableSetOf<Int>()
        for (r in 0 until ROWS) {
            for (c in 0 until COLS) {
                PlaylistArtGeometry.CORNERS.forEach { (dr, dc) ->
                    val index = PlaylistArtGeometry.segmentIndex(r, c, dr, dc)
                    assertTrue(index in PlaylistArtGeometry.segments.indices, "Index $index out of range")
                    assertTrue(seen.add(index), "Index $index assigned twice")
                }
            }
        }
        assertEquals(PlaylistArtGeometry.segments.size, seen.size)
    }

    @Test
    fun `every segment stays inside the reference square and runs corner to centre`() {
        val limit = PlaylistArtGeometry.REFERENCE_SIZE
        PlaylistArtGeometry.segments.forEachIndexed { index, segment ->
            listOf(segment.x1, segment.y1, segment.x2, segment.y2).forEach { coordinate ->
                assertTrue(coordinate in 0f..limit, "Segment $index escapes the frame at $coordinate")
            }
            assertTrue(segment.x1 != segment.x2 || segment.y1 != segment.y2, "Segment $index has no length")
        }
    }

    @Test
    fun `a cell's four inner ends form a square centred on the cell`() {
        // The diamond fill is drawn from these four points, so they have to be a real convex
        // quad around the centre rather than four points that happen to be near it.
        val topLeft = PlaylistArtGeometry.segments[PlaylistArtGeometry.segmentIndex(2, 3, 0, 0)]
        val topRight = PlaylistArtGeometry.segments[PlaylistArtGeometry.segmentIndex(2, 3, 0, 1)]
        val bottomLeft = PlaylistArtGeometry.segments[PlaylistArtGeometry.segmentIndex(2, 3, 1, 0)]
        val bottomRight = PlaylistArtGeometry.segments[PlaylistArtGeometry.segmentIndex(2, 3, 1, 1)]

        assertEquals(topLeft.x2, bottomLeft.x2, TOLERANCE)
        assertEquals(topRight.x2, bottomRight.x2, TOLERANCE)
        assertEquals(topLeft.y2, topRight.y2, TOLERANCE)
        assertEquals(bottomLeft.y2, bottomRight.y2, TOLERANCE)
        assertTrue(topRight.x2 - topLeft.x2 > 1f, "Inner ends collapsed onto the cell centre")
        assertEquals(topRight.x2 - topLeft.x2, bottomLeft.y2 - topLeft.y2, TOLERANCE)
    }

    @Test
    fun `the same seed always produces the same pattern`() {
        listOf(0L, 1L, -1L, Long.MIN_VALUE, Long.MAX_VALUE, 8_675_309L).forEach { seed ->
            assertEquals(generatePlaylistArt(seed), generatePlaylistArt(seed))
        }
    }

    @Test
    fun `different seeds do not all collapse onto one picture`() {
        val patterns = (0L until 200L).map(::generatePlaylistArt)
        // Measured 190 of 200, so 180 leaves room for noise while still failing on a more
        // concentrated generator. A `> 20` bar would pass a generator four times more
        // concentrated than the current one.
        assertTrue(
            patterns.toSet().size >= 180,
            "Seeds produced only ${patterns.toSet().size} distinct patterns of 200",
        )
        // Every plane should turn up across 200 draws of a uniform 1-in-4 choice.
        assertEquals(SymmetryPlane.entries.toSet(), patterns.mapTo(mutableSetOf()) { it.plane })
    }

    @Test
    fun `the structure space is wide rather than concentrated on a few common shapes`() {
        // 1/sum(p^2) estimated without the sampling bias: sum of c*(c-1) counts ordered colliding
        // pairs. Measured ~1,825, so 1,000 sits well inside current behaviour and far from the
        // ~260 of a concentrated generator.
        val counts = sample.groupingBy { it }.eachCount().values
        val collidingPairs = counts.sumOf { it.toDouble() * (it - 1) }
        val effective = SAMPLE_SIZE.toDouble() * (SAMPLE_SIZE - 1) / collidingPairs
        assertTrue(effective >= 1_000, "Effective distinct patterns fell to ${effective.toInt()} (expected >= 1000)")

        // The audit's most common pattern took 3.55% of seeds; now ~0.7%.
        val topShare = counts.max().toDouble() / SAMPLE_SIZE
        assertTrue(
            topShare <= 0.015,
            "One pattern accounts for ${"%.2f".format(topShare * 100)}% of seeds (expected <= 1.5%)",
        )
    }

    @Test
    fun `a good share of covers contain at least one filled diamond`() {
        // Diamond fills are a consequence of the same short-walk tendency as the variety above.
        // Measured ~54% with the current walk tuning and ~23% without it; 45% catches a slide back without being
        // brittle to a small change in how the walk is tuned.
        val withDiamond = sample.count { pattern ->
            (0 until ROWS).any { r -> (0 until COLS).any { c -> PlaylistArtGeometry.isFullyLit(pattern, r, c) } }
        }
        val share = withDiamond.toDouble() / SAMPLE_SIZE
        assertTrue(share >= 0.45, "Only ${"%.1f".format(share * 100)}% of covers contain a diamond (expected >= 45%)")
    }

    @Test
    fun `every pattern lights real segments and is never empty`() {
        (0L until 200L).forEach { seed ->
            val pattern = generatePlaylistArt(seed)
            assertTrue(pattern.litSegments.isNotEmpty(), "Seed $seed lit nothing")
            pattern.litSegments.forEach { index ->
                assertTrue(index in PlaylistArtGeometry.segments.indices, "Seed $seed lit out-of-range segment $index")
            }
        }
    }

    @Test
    fun `every pattern is unchanged by reflecting it across its own plane`() {
        // The mirror is written out directly here rather than reusing the generator's
        // vertex-difference implementation, so a sign error in that implementation cannot
        // agree with itself and pass.
        (0L until 200L).forEach { seed ->
            val pattern = generatePlaylistArt(seed)
            val mirrored = pattern.litSegments.mapTo(mutableSetOf()) { index ->
                val (r, c, dr, dc) = decode(index)
                when (pattern.plane) {
                    SymmetryPlane.VERTICAL ->
                        PlaylistArtGeometry.segmentIndex(r, MAX_INDEX - c, dr, 1 - dc)

                    SymmetryPlane.HORIZONTAL ->
                        PlaylistArtGeometry.segmentIndex(MAX_INDEX - r, c, 1 - dr, dc)

                    SymmetryPlane.DIAG1 ->
                        PlaylistArtGeometry.segmentIndex(c, r, dc, dr)

                    SymmetryPlane.DIAG2 ->
                        PlaylistArtGeometry.segmentIndex(MAX_INDEX - c, MAX_INDEX - r, 1 - dc, 1 - dr)
                }
            }
            assertEquals(pattern.litSegments, mirrored, "Seed $seed is not symmetric across ${pattern.plane}")
        }
    }

    @Test
    fun `a cell counts as fully lit only with all four of its corners`() {
        val threeCorners = PlaylistArtGeometry.CORNERS.drop(1)
            .mapTo(mutableSetOf()) { (dr, dc) -> PlaylistArtGeometry.segmentIndex(1, 4, dr, dc) }
        val partial = PlaylistArtPattern(SymmetryPlane.VERTICAL, threeCorners)
        assertFalse(PlaylistArtGeometry.isFullyLit(partial, 1, 4))

        val complete = PlaylistArtPattern(
            SymmetryPlane.VERTICAL,
            threeCorners + PlaylistArtGeometry.segmentIndex(1, 4, 0, 0),
        )
        assertTrue(PlaylistArtGeometry.isFullyLit(complete, 1, 4))
        // A different cell's corners must not satisfy this one.
        assertFalse(PlaylistArtGeometry.isFullyLit(complete, 1, 3))
    }

    @Test
    fun `perpendicular planes pair off and are their own inverse`() {
        SymmetryPlane.entries.forEach { plane ->
            assertTrue(plane != plane.perpendicular())
            assertEquals(plane, plane.perpendicular().perpendicular())
        }
    }

    @Test
    fun `every pattern lights all four quadrants of the frame`() {
        // The property generatePlaylistArt's KDoc promises. Checked per plane as well as overall
        // so a regression confined to one plane cannot hide behind the others' passing counts.
        val emptyByPlane = mutableMapOf<SymmetryPlane, MutableList<Long>>()
        val seenPlanes = mutableSetOf<SymmetryPlane>()
        (0L until 4_000L).forEach { seed ->
            val pattern = generatePlaylistArt(seed)
            seenPlanes += pattern.plane
            if (quadrantsLit(pattern).size < 4) {
                emptyByPlane.getOrPut(pattern.plane) { mutableListOf() } += seed
            }
        }
        assertEquals(SymmetryPlane.entries.toSet(), seenPlanes)
        assertTrue(emptyByPlane.isEmpty(), "Seeds leaving a quadrant dark, by plane: $emptyByPlane")
    }

    @Test
    fun `the documented diagonal example seeds fill the frame`() {
        // Two diagonal example seeds (DIAG1, DIAG2) whose walks fall short of one quadrant
        // before the fill-the-frame fallback runs, so they exercise it - checked against an
        // instrumented copy of the generator, and by mutation (see the test below).
        val diag1 = generatePlaylistArt(1238145679872042884L)
        assertEquals(SymmetryPlane.DIAG1, diag1.plane)
        assertEquals(setOf(0, 1, 2, 3), quadrantsLit(diag1))

        val diag2 = generatePlaylistArt(-1006892909181018201L)
        assertEquals(SymmetryPlane.DIAG2, diag2.plane)
        assertEquals(setOf(0, 1, 2, 3), quadrantsLit(diag2))
    }

    @Test
    fun `a diagonal pattern that needed the fallback is symmetric under all eight square symmetries`() {
        // Spread across only two of the symmetries would leave the picture symmetric about its own
        // plane but lopsided about everything else; the orbit has to be closed under all of them.
        val examples = listOf(1238145679872042884L, -1006892909181018201L)
        examples.forEach { seed ->
            val lit = generatePlaylistArt(seed).litSegments
            listOf(::reflectVertical, ::reflectHorizontal, ::reflectDiag1, ::reflectDiag2).forEach { reflect ->
                assertEquals(lit, lit.mapTo(mutableSetOf(), reflect), "Seed $seed is not fully symmetric")
            }
        }
    }

    @Test
    fun `axis-aligned patterns match the recorded post-tuning output`() {
        // Captured from the generator with GREEDY_EXIT_PROBABILITY 0.4 and no forced centre
        // exit: seed, plane, lit count, hash of the sorted lit-segment indices. Any change to how
        // VERTICAL/HORIZONTAL patterns come out must be deliberate: re-record these and say so.
        // Four of these ten seeds run through the perpendicular-axis fallback and six do not, so
        // both paths are pinned.
        val golden = listOf(
            Golden(-6367288518484839692L, SymmetryPlane.VERTICAL, 40, 436484065),
            Golden(-3350035376205273921L, SymmetryPlane.VERTICAL, 54, -1225228884),
            Golden(-2252118164433982605L, SymmetryPlane.VERTICAL, 44, 1414225191),
            Golden(-2780912622345900595L, SymmetryPlane.VERTICAL, 28, 2131454901),
            Golden(2036865227790292842L, SymmetryPlane.VERTICAL, 88, -1761161735),
            Golden(7288696731122253832L, SymmetryPlane.HORIZONTAL, 48, 1118770369),
            Golden(4129736453975454884L, SymmetryPlane.HORIZONTAL, 56, -709132143),
            Golden(357622638577873973L, SymmetryPlane.HORIZONTAL, 20, 1009211249),
            Golden(6025340343269824406L, SymmetryPlane.HORIZONTAL, 36, 398287039),
            Golden(-766224462991485607L, SymmetryPlane.HORIZONTAL, 46, -859859639),
        )
        golden.forEach { g ->
            val pattern = generatePlaylistArt(g.seed)
            assertEquals(g.plane, pattern.plane, "Seed ${g.seed} changed plane")
            assertEquals(g.litCount, pattern.litSegments.size, "Seed ${g.seed} lit count changed")
            assertEquals(g.hash, pattern.litSegments.sorted().hashCode(), "Seed ${g.seed} lit set changed")
        }
    }

    private data class Golden(val seed: Long, val plane: SymmetryPlane, val litCount: Int, val hash: Int)

    private data class Corner(val r: Int, val c: Int, val dr: Int, val dc: Int)

    private fun decode(index: Int) = Corner(
        r = index / (COLS * 4),
        c = (index / 4) % COLS,
        dr = (index % 4) / 2,
        dc = index % 2,
    )

    /** Which of the four grid quadrants (0=TL, 1=TR, 2=BL, 3=BR) hold at least one lit segment. */
    private fun quadrantsLit(pattern: PlaylistArtPattern): Set<Int> =
        pattern.litSegments.mapTo(mutableSetOf()) { index ->
            val (r, c) = decode(index)
            (if (r < ROWS / 2) 0 else 2) + (if (c < COLS / 2) 0 else 1)
        }

    private fun reflectVertical(index: Int): Int {
        val (r, c, dr, dc) = decode(index)
        return PlaylistArtGeometry.segmentIndex(r, MAX_INDEX - c, dr, 1 - dc)
    }

    private fun reflectHorizontal(index: Int): Int {
        val (r, c, dr, dc) = decode(index)
        return PlaylistArtGeometry.segmentIndex(MAX_INDEX - r, c, 1 - dr, dc)
    }

    private fun reflectDiag1(index: Int): Int {
        val (r, c, dr, dc) = decode(index)
        return PlaylistArtGeometry.segmentIndex(c, r, dc, dr)
    }

    private fun reflectDiag2(index: Int): Int {
        val (r, c, dr, dc) = decode(index)
        return PlaylistArtGeometry.segmentIndex(MAX_INDEX - c, MAX_INDEX - r, 1 - dc, 1 - dr)
    }

    private companion object {
        const val SAMPLE_SIZE = 20_000
        val sample: List<PlaylistArtPattern> by lazy { (0L until SAMPLE_SIZE).map(::generatePlaylistArt) }

        const val ROWS = PlaylistArtGeometry.ROWS
        const val COLS = PlaylistArtGeometry.COLS
        const val MAX_INDEX = PlaylistArtGeometry.COLS - 1
        const val TOLERANCE = 0.001f
    }
}
