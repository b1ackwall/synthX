package com.v1per.synthx.data.playlistart

import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.graphics.colorspace.connect
import kotlin.math.abs
import kotlin.math.atan2

/**
 * Perceptual (OkLab / OkLCh) helpers behind the playlist-art palette. Kept free of `Color` so
 * the palette and its tests stay plain-JVM; only [ColorSpaces] — pure maths, no Android
 * framework — is borrowed from Compose, rather than carrying our own copy of the Ottosson
 * matrices.
 *
 * The stored `art_hue` stays a raw HSL degree (what `Color.hsl` takes). Everything here exists to
 * answer perceptual questions *about* that HSL hue: how far apart two hues look, and how light
 * an HSL colour at that hue really is.
 */

/**
 * One tinted colour in a cover. HSL lightness is not perceptual lightness — at the same
 * `hsl(h, s, 0.8)` yellow reads far brighter than blue — so a role fixes the saturation and the
 * OkLab lightness it should *look* like, and [hslLightness] finds the HSL lightness that
 * delivers that for a given hue.
 */
internal class PlaylistArtRole(val saturation: Float, val targetOkLabLightness: Float) {

    /** The HSL lightness at which this role's colour, at [hue], has [targetOkLabLightness]. */
    fun hslLightness(hue: Float): Float = solveHslLightness(hue, saturation, targetOkLabLightness)
}

// Targets are the means of the original fixed-HSL colours (hsl(h, 0.5, 0.2), hsl(h, 0.8, 0.8),
// hsl(h, 0.3, 0.5)) over the old 90-hue palette, so a cover's overall brightness stays where it
// was and only the hue-to-hue drift (OkLab L 0.75-0.95 for the bright stroke end) is removed.
internal val LitStartRole = PlaylistArtRole(saturation = 0.5f, targetOkLabLightness = 0.335f)
internal val LitEndRole = PlaylistArtRole(saturation = 0.8f, targetOkLabLightness = 0.854f)
internal val DiamondRole = PlaylistArtRole(saturation = 0.3f, targetOkLabLightness = 0.611f)

private val srgbToOkLab = ColorSpaces.Srgb.connect(ColorSpaces.Oklab)

/** sRGB -> OkLab (L, a, b) for an HSL colour, at full float precision (no 8-bit packing). */
internal fun hslToOkLab(hue: Float, saturation: Float, lightness: Float): FloatArray {
    // The W3C/CSS HSL -> RGB formulation: one expression per channel, offset 0/8/4 for R/G/B.
    val amplitude = saturation * minOf(lightness, 1f - lightness)
    fun channel(n: Int): Float {
        val k = (n + hue / 30f) % 12f
        return lightness - amplitude * minOf(k - 3f, 9f - k, 1f).coerceIn(-1f, 1f)
    }
    return srgbToOkLab.transform(channel(0), channel(8), channel(4))
}

/**
 * HSL lightness in 0..1 whose OkLab L equals [target] at ([hue], [saturation]). OkLab L rises
 * monotonically with HSL lightness at a fixed hue and saturation, so bisection is exact; every
 * HSL colour is inside sRGB, so unlike solving in OkLCh directly this can never leave the gamut.
 * A target outside what the hue can reach saturates at 0 or 1.
 */
internal fun solveHslLightness(hue: Float, saturation: Float, target: Float): Float {
    var low = 0f
    var high = 1f
    repeat(BISECTION_STEPS) {
        val mid = (low + high) / 2f
        if (hslToOkLab(hue, saturation, mid)[0] < target) low = mid else high = mid
    }
    return (low + high) / 2f
}

/**
 * OkLCh hue angle, in degrees, of the palette's reference colour — the lightness-compensated
 * bright stroke end ([LitEndRole]) — for each stored HSL hue 0..359. This is the axis on which
 * hues are "evenly spaced": the palette is picked to be 4 degrees apart here, and selection
 * measures distance here, rather than in raw HSL degrees. Lazily built once: 360 small
 * bisections.
 */
internal val PerceptualHueAngles: FloatArray by lazy {
    FloatArray(HUE_COUNT) { hue ->
        val lab = hslToOkLab(hue.toFloat(), LitEndRole.saturation, LitEndRole.hslLightness(hue.toFloat()))
        var degrees = Math.toDegrees(atan2(lab[2], lab[1]).toDouble()).toFloat()
        if (degrees < 0f) degrees += 360f
        degrees
    }
}

/** Distance in degrees between two OkLCh angles, both in 0..360, along the shorter way round. */
internal fun circularAngleDistance(a: Float, b: Float): Float {
    val d = abs(a - b)
    return minOf(d, 360f - d)
}

/** Reduces any integer hue to 0..359, including negatives. */
internal fun normalizeArtHue(hue: Int): Int = Math.floorMod(hue, HUE_COUNT)

/**
 * How far apart two stored hues look, in OkLCh degrees. Inputs are normalised first, so a
 * hand-edited or restored out-of-range value is treated as the hue it wraps to.
 */
internal fun perceptualHueDistance(a: Int, b: Int): Float =
    circularAngleDistance(PerceptualHueAngles[normalizeArtHue(a)], PerceptualHueAngles[normalizeArtHue(b)])

private const val HUE_COUNT = 360
private const val BISECTION_STEPS = 24
