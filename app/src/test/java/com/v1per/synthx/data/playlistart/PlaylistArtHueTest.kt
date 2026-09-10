package com.v1per.synthx.data.playlistart

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaylistArtHueTest {

    @Test
    fun `the palette is the ninety hues four degrees apart`() {
        assertEquals(90, PlaylistArtHues.size)
        assertEquals(0, PlaylistArtHues.first())
        assertEquals(356, PlaylistArtHues.last())
        assertTrue(PlaylistArtHues.all { it % 4 == 0 })
    }

    @Test
    fun `hue distance wraps around the wheel rather than running through it`() {
        assertEquals(8, circularHueDistance(4, 356))
        assertEquals(8, circularHueDistance(356, 4))
        assertEquals(180, circularHueDistance(0, 180))
        assertEquals(0, circularHueDistance(120, 120))
    }

    @Test
    fun `the first playlist takes some hue from the palette`() {
        assertContains(PlaylistArtHues, selectDistancedHue(emptyList(), Random(7)))
    }

    @Test
    fun `the second hue lands opposite the first`() {
        assertEquals(180, selectDistancedHue(listOf(0)))
        assertEquals(0, selectDistancedHue(listOf(180)))
        // Opposite 100 is 280, wherever on the wheel the first hue happened to fall.
        assertEquals(280, selectDistancedHue(listOf(100)))
    }

    @Test
    fun `the third hue splits the widest remaining gap`() {
        // Both halves of the 0/180 wheel are equally empty; ties resolve to the lowest hue,
        // and 88 is the closest quantised hue to the 90-degree midpoint.
        assertEquals(88, selectDistancedHue(listOf(0, 180)))
    }

    @Test
    fun `an uneven library is filled in at its widest gap, not next to a used hue`() {
        // 0, 4, 8 are crowded together; everything from 12 round to 356 is free, so the new
        // hue must land near the far side of that gap rather than at 12.
        val chosen = selectDistancedHue(listOf(0, 4, 8))
        assertEquals(184, chosen)
        assertTrue(
            listOf(0, 4, 8).minOf { circularHueDistance(chosen, it) } > 170,
            "Expected the widest-gap hue, got $chosen",
        )
    }

    @Test
    fun `no hue repeats while the palette still has unused entries`() {
        val assigned = mutableListOf<Int>()
        repeat(PlaylistArtHues.size) {
            assigned += selectDistancedHue(assigned, Random(11))
        }
        assertEquals(PlaylistArtHues.size, assigned.size)
        assertEquals(PlaylistArtHues.toSet(), assigned.toSet())
    }

    @Test
    fun `an exhausted palette falls back to a random repeat instead of failing`() {
        val everything = PlaylistArtHues.toList()
        val chosen = selectDistancedHue(everything, Random(3))
        assertContains(PlaylistArtHues, chosen)
        // Same seed, same answer — the fallback is still deterministic for a given RNG.
        assertEquals(chosen, selectDistancedHue(everything, Random(3)))
    }

    @Test
    fun `hues already in use that are not on the palette still push candidates away`() {
        // A hue that could only arrive from restored or hand-edited data must not be ignored:
        // it is not in the palette, so nothing is removed, but it still repels.
        val chosen = selectDistancedHue(listOf(1))
        assertContains(PlaylistArtHues, chosen)
        assertTrue(circularHueDistance(chosen, 1) > 170, "Expected a hue far from 1, got $chosen")
    }
}
