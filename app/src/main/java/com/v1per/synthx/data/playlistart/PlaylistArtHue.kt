package com.v1per.synthx.data.playlistart

import kotlin.math.abs
import kotlin.random.Random

/**
 * The 90 hues a playlist's generated cover art can take: 0, 4, 8, ..., 356.
 *
 * Quantised rather than continuous so [selectDistancedHue] has a finite candidate set to
 * spread across, and so two playlists either share a hue exactly or sit at least 4 degrees
 * apart — never one indistinguishable degree.
 */
internal val PlaylistArtHues: List<Int> = (0 until 360 step 4).toList()

/** Shortest distance between two hues on the 360-degree wheel, in degrees. */
internal fun circularHueDistance(a: Int, b: Int): Int {
    val d = abs(a - b)
    return minOf(d, 360 - d)
}

/**
 * Picks the hue for one new playlist: farthest-point spreading, so a library's covers fan out
 * across the wheel instead of clustering wherever a run of random draws happened to land.
 *
 * Among the hues not already taken, this returns the one whose *nearest* used hue is as far
 * away as possible. Ties resolve to the lowest such hue ([PlaylistArtHues] order), which keeps
 * a given [existingHues] set mapping to one deterministic answer — [random] is consulted only
 * for the two genuinely arbitrary cases: the very first playlist, and a library that has
 * exhausted all 90 hues, where repeats stop being avoidable and distancing means nothing.
 */
internal fun selectDistancedHue(
    existingHues: List<Int>,
    random: Random = Random.Default,
): Int {
    if (existingHues.isEmpty()) return PlaylistArtHues.random(random)
    val unused = PlaylistArtHues - existingHues.toSet()
    if (unused.isEmpty()) return PlaylistArtHues.random(random)
    return unused.maxBy { candidate ->
        existingHues.minOf { existing -> circularHueDistance(candidate, existing) }
    }
}
