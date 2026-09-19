package com.v1per.synthx.data.playlistart

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaylistArtHueTest {

    // ------------------------------------------------------------------ the palette itself

    @Test
    fun `the palette is ninety distinct in-range hues in ascending order`() {
        assertEquals(90, PlaylistArtHues.size)
        assertEquals(90, PlaylistArtHues.toSet().size)
        assertTrue(PlaylistArtHues.all { it in 0..359 }, "Out-of-range hue in $PlaylistArtHues")
        assertEquals(PlaylistArtHues.sorted(), PlaylistArtHues, "selection's tie-break relies on ascending order")
    }

    @Test
    fun `palette hues are evenly spaced in perceived hue, not in HSL degrees`() {
        val gaps = perceptualGaps(PlaylistArtHues)
        assertEquals(360f, gaps.sum(), 0.5f, "Gaps must go once round the wheel")
        // Ideal is 4.0. Integer HSL degrees move perceived hue by 0.4-1.9 degrees each, so
        // individual gaps cannot be tighter than roughly this.
        assertTrue(gaps.min() > 2.5f, "Palette entries crowd to ${gaps.min()} degrees apart")
        assertTrue(gaps.max() < 6.0f, "Palette has a ${gaps.max()}-degree hole")

        // The old palette (0, 4, ... 356) for comparison: same measure, far worse.
        val oldGaps = perceptualGaps((0 until 360 step 4).toList())
        assertTrue(oldGaps.max() > 7f && oldGaps.min() < 2f, "Baseline changed: ${oldGaps.min()}..${oldGaps.max()}")
        assertTrue(gaps.max() - gaps.min() < (oldGaps.max() - oldGaps.min()) / 1.5f)
    }

    @Test
    fun `every sixty degree band of perceived hue holds about the same number of palette hues`() {
        val perBand = IntArray(6)
        PlaylistArtHues.forEach { perBand[(PerceptualHueAngles[it] / 60f).toInt()]++ }
        // 15 each when perfectly even; the HSL-spaced palette was [11, 12, 23, 10, 14, 20].
        assertTrue(perBand.all { it in 14..16 }, "Uneven bands: ${perBand.toList()}")
    }

    @Test
    fun `perceived hue rises monotonically with HSL hue, so the palette derivation is well defined`() {
        val angles = PerceptualHueAngles
        val wraps = (0 until 360).count { angles[(it + 1) % 360] < angles[it] }
        assertEquals(1, wraps, "Perceived hue should go round the wheel exactly once")
    }

    // ------------------------------------------------------------------ distance

    @Test
    fun `hue distance wraps around the wheel rather than running through it`() {
        assertEquals(8, circularHueDistance(4, 356))
        assertEquals(8, circularHueDistance(356, 4))
        assertEquals(180, circularHueDistance(0, 180))
        assertEquals(0, circularHueDistance(120, 120))
    }

    @Test
    fun `hue distance is never negative, whatever range its inputs are in`() {
        // (400, 4) used to give min(396, 360 - 396) = -36.
        assertEquals(36, circularHueDistance(400, 4))
        assertEquals(8, circularHueDistance(-4, 4))
        assertEquals(0, circularHueDistance(720, 0))
        assertEquals(0, circularHueDistance(360, 0))
        val rng = Random(5)
        repeat(2_000) {
            val a = rng.nextInt(-2_000, 2_000)
            val b = rng.nextInt(-2_000, 2_000)
            val d = circularHueDistance(a, b)
            assertTrue(d in 0..180, "circularHueDistance($a, $b) = $d")
            assertEquals(d, circularHueDistance(b, a))
            assertEquals(d, circularHueDistance(normalizeArtHue(a), normalizeArtHue(b)))
        }
    }

    @Test
    fun `normalising reduces any hue to 0 through 359`() {
        assertEquals(40, normalizeArtHue(400))
        assertEquals(350, normalizeArtHue(-10))
        assertEquals(0, normalizeArtHue(360))
        assertEquals(0, normalizeArtHue(-720))
        assertEquals(359, normalizeArtHue(359))
    }

    // ------------------------------------------------------------------ selection: unused hues

    @Test
    fun `the first playlist takes some hue from the palette`() {
        assertContains(PlaylistArtHues, selectDistancedHue(emptyList(), Random(7)))
    }

    @Test
    fun `the second hue lands opposite the first, as the eye sees it`() {
        PlaylistArtHues.forEach { first ->
            val second = selectDistancedHue(listOf(first))
            assertContains(PlaylistArtHues, second)
            // Opposite is 180; palette gaps of up to ~5.5 degrees leave the best on offer within it.
            assertTrue(perceptualHueDistance(first, second) > 174f, "$first -> $second")
        }
    }

    @Test
    fun `the third hue splits the widest remaining gap`() {
        val first = PlaylistArtHues[0]
        val second = selectDistancedHue(listOf(first))
        val third = selectDistancedHue(listOf(first, second))
        assertTrue(perceptualHueDistance(third, first) > 84f, "third=$third first=$first")
        assertTrue(perceptualHueDistance(third, second) > 84f, "third=$third second=$second")
    }

    @Test
    fun `an uneven library is filled in at its widest gap, not next to a used hue`() {
        val crowded = PlaylistArtHues.take(3)
        val chosen = selectDistancedHue(crowded)
        assertTrue(
            crowded.minOf { perceptualHueDistance(chosen, it) } > 170f,
            "Expected the widest-gap hue, got $chosen",
        )
    }

    @Test
    fun `a growing library stays evenly spread by eye at every size`() {
        // The reason selection measures distance in perceived hue: spread by raw HSL degrees, a
        // library of 8 left a 76-degree hole (ideal 45) and one of 20 had two covers 4 degrees
        // apart, because equal HSL steps are wildly unequal perceptual ones. Farthest-point
        // spreading itself never does better than largest gap <= ~2x smallest gap (n=12 is
        // 45- and 22-degree gaps), so that, plus "nobody crowded", is what is asserted.
        val library = mutableListOf(PlaylistArtHues[0])
        while (library.size < 30) {
            library += selectDistancedHue(library)
            val n = library.size
            if (n in listOf(4, 8, 12, 20, 30)) {
                val gaps = perceptualGaps(library)
                val ideal = 360f / n
                assertTrue(gaps.min() > 0.5f * ideal, "n=$n: two covers only ${gaps.min()} apart (ideal $ideal)")
                assertTrue(
                    gaps.max() < 2.5f * gaps.min(),
                    "n=$n: gaps run ${gaps.min()}..${gaps.max()} degrees (ideal $ideal)",
                )
            }
        }
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
    fun `a library from the previous palette gets new palette hues clear of the old ones`() {
        // Existing rows keep their multiples-of-four hues; they are off-palette now, so they
        // repel candidates but claim no palette entry.
        val old = (0 until 360 step 4).toList()
        val chosen = selectDistancedHue(old)
        assertContains(PlaylistArtHues, chosen)
        assertEquals(chosen, selectDistancedHue(old, Random(1)), "deterministic once anything exists")
    }

    // ------------------------------------------------------------------ selection: exhaustion

    @Test
    fun `an exhausted palette picks the one hue used least, not a random one`() {
        val spare = PlaylistArtHues[10]
        val twice = PlaylistArtHues + PlaylistArtHues
        val existing = twice - spare // every hue twice except `spare`, used once
        assertEquals(spare, selectDistancedHue(existing, Random(3)))
        assertEquals(spare, selectDistancedHue(existing, Random(4)), "Randomness must not enter here")
    }

    @Test
    fun `reuse stays balanced, so no hue gets another copy while one has fewer`() {
        val library = PlaylistArtHues.toMutableList()
        repeat(200) { library += selectDistancedHue(library, Random(1)) }
        val counts = PlaylistArtHues.map { hue -> library.count { it == hue } }
        assertTrue(counts.max() - counts.min() <= 1, "Usage drifted apart: min ${counts.min()} max ${counts.max()}")
        assertEquals(290, counts.sum())
    }

    @Test
    fun `among equally used hues the exhausted pick prefers the most isolated one`() {
        // Everything used exactly once: only the neighbour distance can decide. The winner must
        // be the entry whose nearer neighbour is farthest away (lowest hue on a tie).
        val chosen = selectDistancedHue(PlaylistArtHues, Random(1))
        val nearestOther = PlaylistArtHues.associateWith { hue ->
            PlaylistArtHues.filter { it != hue }.minOf { perceptualHueDistance(hue, it) }
        }
        val best = nearestOther.values.max()
        assertEquals(PlaylistArtHues.first { nearestOther.getValue(it) == best }, chosen)
    }

    // ------------------------------------------------------------------ selection: dirty input

    @Test
    fun `stored hues outside 0 to 359 are read as the hue they wrap to`() {
        val expected = selectDistancedHue(listOf(40, 200))
        assertEquals(expected, selectDistancedHue(listOf(400, 200)))
        assertEquals(expected, selectDistancedHue(listOf(-320, 560)))
        assertEquals(expected, selectDistancedHue(listOf(40 + 3_600, 200 - 3_600)))
    }

    @Test
    fun `an out-of-range value that wraps onto a palette hue counts as using that hue`() {
        // Distance already normalises for itself; this is the used/unused bookkeeping. 360 wraps
        // to the palette's hue 0, so a library holding every palette hue as `hue + 360` is an
        // exhausted one, and must be answered exactly like the plain palette is.
        val wrapped = PlaylistArtHues.map { it + 360 }
        assertEquals(selectDistancedHue(PlaylistArtHues), selectDistancedHue(wrapped))

        // ...and with one hue genuinely unused, that hue is the one handed out.
        val spare = PlaylistArtHues[42]
        assertEquals(spare, selectDistancedHue(wrapped - (spare + 360)))
        assertEquals(spare, selectDistancedHue(PlaylistArtHues.map { it - 360 } - (spare - 360)))
    }

    @Test
    fun `hues already in use that are not on the palette still push candidates away`() {
        // 1 is not a palette entry, so nothing is removed from the candidates, but it must
        // still repel: the pick sits on the far side of the wheel from it.
        assertTrue(1 !in PlaylistArtHues)
        val chosen = selectDistancedHue(listOf(1))
        assertContains(PlaylistArtHues, chosen)
        assertTrue(perceptualHueDistance(chosen, 1) > 170f, "Expected a hue far from 1, got $chosen")
    }

    private fun perceptualGaps(hues: List<Int>): List<Float> {
        val angles = hues.map { PerceptualHueAngles[it] }.sorted()
        return angles.indices.map { i ->
            val next = if (i + 1 < angles.size) angles[i + 1] else angles[0] + 360f
            next - angles[i]
        }
    }
}
