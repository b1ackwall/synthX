package com.v1per.synthx.playback

import android.graphics.Bitmap
import kotlin.math.ceil
import kotlin.math.pow

// The same luma weights ColorMatrix.setSaturation uses.
private const val LumaR = 0.213f
private const val LumaG = 0.715f
private const val LumaB = 0.072f

private fun srgbToLinear(c: Float): Float = if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)

/** Median of one brightness metric, in 0..1. */
data class BrightnessStats(val median: Float)

/** WCAG relative luminance statistics of a background bitmap. */
data class BackgroundBrightness(val wcag: BrightnessStats)

private fun nearestRank(sorted: FloatArray, percentile: Int): Float {
    val rank = ceil(percentile / 100f * sorted.size).toInt().coerceIn(1, sorted.size)
    return sorted[rank - 1]
}

private fun statsOf(values: FloatArray): BrightnessStats {
    values.sort()
    return BrightnessStats(nearestRank(values, 50))
}

/**
 * WCAG relative luminance statistics for opaque ARGB [pixels]. Each pixel gets
 * the background chain's [saturation] boost (as ColorMatrix.setSaturation does), clamped to 0..1
 * and converted to linear sRGB; alpha is ignored. Null for no pixels.
 */
internal fun backgroundBrightness(pixels: IntArray, saturation: Float): BackgroundBrightness? {
    if (pixels.isEmpty()) return null
    val wcag = FloatArray(pixels.size)
    val rr = LumaR + (1f - LumaR) * saturation
    val rg = LumaG * (1f - saturation)
    val rb = LumaB * (1f - saturation)
    val gr = LumaR * (1f - saturation)
    val gg = LumaG + (1f - LumaG) * saturation
    val gb = rb
    val br = gr
    val bg = rg
    val bb = LumaB + (1f - LumaB) * saturation
    for (i in pixels.indices) {
        val p = pixels[i]
        val r = ((p shr 16) and 0xFF) / 255f
        val g = ((p shr 8) and 0xFF) / 255f
        val b = (p and 0xFF) / 255f
        val lr = srgbToLinear((rr * r + rg * g + rb * b).coerceIn(0f, 1f))
        val lg = srgbToLinear((gr * r + gg * g + gb * b).coerceIn(0f, 1f))
        val lb = srgbToLinear((br * r + bg * g + bb * b).coerceIn(0f, 1f))
        wcag[i] = 0.2126f * lr + 0.7152f * lg + 0.0722f * lb
    }
    return BackgroundBrightness(statsOf(wcag))
}

/** [backgroundBrightness] of this bitmap's pixels. */
internal fun Bitmap.backgroundBrightness(saturation: Float): BackgroundBrightness? {
    val pixels = IntArray(width * height)
    getPixels(pixels, 0, width, 0, 0, width, height)
    return backgroundBrightness(pixels, saturation)
}
