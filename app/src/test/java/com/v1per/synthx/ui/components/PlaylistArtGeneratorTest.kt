package com.v1per.synthx.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaylistArtGeneratorTest {

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
        assertTrue(patterns.toSet().size > 20, "Seeds produced only ${patterns.toSet().size} distinct patterns")
        // Every plane should turn up across 200 draws of a uniform 1-in-4 choice.
        assertEquals(SymmetryPlane.entries.toSet(), patterns.mapTo(mutableSetOf()) { it.plane })
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

    private data class Corner(val r: Int, val c: Int, val dr: Int, val dc: Int)

    private fun decode(index: Int) = Corner(
        r = index / (COLS * 4),
        c = (index / 4) % COLS,
        dr = (index % 4) / 2,
        dc = index % 2,
    )

    private companion object {
        const val ROWS = PlaylistArtGeometry.ROWS
        const val COLS = PlaylistArtGeometry.COLS
        const val MAX_INDEX = PlaylistArtGeometry.COLS - 1
        const val TOLERANCE = 0.001f
    }
}
