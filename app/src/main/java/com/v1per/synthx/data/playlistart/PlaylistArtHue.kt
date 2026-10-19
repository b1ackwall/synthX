package com.v1per.synthx.data.playlistart

import kotlin.math.abs
import kotlin.random.Random

/**
 * The 90 HSL hues (degrees, what `art_hue` stores and `Color.hsl` takes) a new playlist's cover
 * can be given, chosen so that they are evenly spaced *perceptually*: about 4 degrees apart in
 * OkLCh hue angle rather than in raw HSL degrees. Even HSL spacing crowds a quarter of the
 * palette into the greens (HSL 90-160 spans only ~40 degrees of perceived hue) and starves the
 * blues; this list has ~15 entries in every 60-degree OkLCh band instead.
 *
 * Derived, not hand-picked: entry k is the integer HSL hue whose OkLCh angle — measured on the
 * lightness-compensated [LitEndRole] colour, see [PerceptualHueAngles] — is closest to
 * `angle(hue 0) + 4k`. An integer HSL degree moves the perceived hue by 0.4-1.9 degrees, so
 * individual gaps land between about 2.8 and 5.5 degrees; PlaylistArtHueTest pins that.
 *
 * Literal so the set of values a library can end up with never depends on floating-point
 * behaviour at runtime. Playlists created before this palette keep the multiples-of-four hue
 * they were given; those are simply "off-palette" values, which still repel new picks (see
 * [selectDistancedHue]) but do not occupy any entry here.
 */
internal val PlaylistArtHues: List<Int> = listOf(
    0, 3, 7, 9, 12, 15, 17, 19, 21, 24, 26, 28, 30, 32, 35, 37, 39, 42, 45, 48,
    51, 54, 57, 61, 64, 68, 73, 78, 86, 95, 105, 117, 128, 137, 144, 150, 155, 159, 163, 166,
    169, 172, 175, 177, 180, 182, 185, 187, 189, 192, 194, 196, 198, 200, 202, 204, 207, 209, 211, 214,
    216, 219, 222, 225, 228, 232, 237, 242, 247, 253, 258, 263, 269, 274, 280, 287, 293, 300, 307, 313,
    319, 324, 329, 333, 338, 342, 345, 349, 353, 356,
)

/**
 * Shortest distance between two hues on the 360-degree wheel, in raw HSL degrees. Inputs are
 * reduced to 0..359 first, so a value outside that range (restored or hand-edited data) is
 * measured as the hue it wraps to instead of producing a negative distance. Selection uses
 * [perceptualHueDistance] instead; this is the plain-degrees measure.
 */
internal fun circularHueDistance(a: Int, b: Int): Int {
    val d = abs(normalizeArtHue(a) - normalizeArtHue(b))
    return minOf(d, 360 - d)
}

/**
 * Picks the hue for one new playlist: farthest-point spreading, so a library's covers fan out
 * across the wheel instead of clustering wherever a run of random draws happened to land.
 * Distance is [perceptualHueDistance], so "far apart" means far apart to the eye.
 *
 * Among the palette hues not already taken, this returns the one whose *nearest* used hue is as
 * far away as possible. Once every palette hue is taken, repeats are unavoidable, so it takes the
 * least-used ones instead of a random pick — no hue is handed a third copy while another still
 * has one — and among those prefers the hue whose nearest *other* used hue is farthest. Ties
 * resolve to the lowest hue ([PlaylistArtHues] order), so a given [existingHues] always maps to
 * one answer; [random] is consulted only for the very first playlist, where nothing distinguishes
 * the candidates.
 *
 * [existingHues] are reduced to 0..359 first; any that are not on the palette (older playlists,
 * restored or hand-edited rows) still repel candidates but do not count as using one.
 */
internal fun selectDistancedHue(existingHues: List<Int>, random: Random = Random.Default): Int {
    if (existingHues.isEmpty()) return PlaylistArtHues.random(random)
    val existing = existingHues.map(::normalizeArtHue)
    val usage = existing.groupingBy { it }.eachCount()

    val unused = PlaylistArtHues.filter { it !in usage }
    if (unused.isNotEmpty()) {
        return unused.maxBy { candidate ->
            existing.minOf { perceptualHueDistance(candidate, it) }
        }
    }

    val fewestUses = PlaylistArtHues.minOf { usage.getValue(it) }
    val leastUsed = PlaylistArtHues.filter { usage.getValue(it) == fewestUses }
    return leastUsed.maxBy { candidate ->
        // The candidate is itself in use here, so measure against everything else — otherwise
        // every candidate scores zero and the tie-break decides nothing.
        existing.filter { it != candidate }.minOfOrNull { perceptualHueDistance(candidate, it) } ?: 0f
    }
}
