package com.v1per.synthx.ui.player

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp

/** Side of one image cell, in pixels. The shadow is smooth, so a coarse grid scaled up bilinearly is enough. */
internal const val GlowCellPx = 4

/** The image extends this many sigmas past the box on each side; the last ring rounds to transparent. */
private const val GlowTailSigmas = 4f

/** Gaussian sigma, in pixels, that a blur [radiusPx] produces (Skia's radius-to-sigma conversion). */
internal fun glowSigmaPx(radiusPx: Float): Float = if (radiusPx > 0f) 0.57735f * radiusPx + 0.5f else 0f

/** Cells of transparent padding on each side of the box so the whole tail fits. */
internal fun glowPadCells(sigmaCells: Float): Int = ceil(GlowTailSigmas * sigmaCells).toInt()

/** Cells that cover a box of [boxPx] pixels, at least one. */
internal fun glowBoxCells(boxPx: Int, cellPx: Int): Int = ((boxPx + cellPx - 1) / cellPx).coerceAtLeast(1)

/** Standard normal cumulative distribution (Abramowitz-Stegun 7.1.26, error below 1e-7). */
private fun gaussianCdf(z: Float): Float {
    val x = abs(z) / SQRT_TWO
    val t = 1f / (1f + 0.3275911f * x)
    val poly = ((((1.061405429f * t - 1.453152027f) * t) + 1.421413741f) * t - 0.284496736f) * t + 0.254829592f
    val erf = 1f - poly * t * exp(-x * x)
    return if (z >= 0f) 0.5f * (1f + erf) else 0.5f * (1f - erf)
}

private const val SQRT_TWO = 1.4142135f

/**
 * Alpha bytes (0..255, row-major) of a square box of [boxCells] cells blurred by a Gaussian of
 * [sigmaCells], with [padCells] of padding on every side, scaled by [peakAlpha]. The side is
 * [boxCells] + 2 * [padCells]. A box blur is separable, so each pixel is the product of two
 * one-dimensional edge profiles; the 2dp corner rounding is far below the sigma and is not drawn.
 */
internal fun generateGlowAlpha(boxCells: Int, padCells: Int, sigmaCells: Float, peakAlpha: Float): ByteArray {
    require(boxCells > 0 && padCells >= 0 && sigmaCells > 0f) {
        "Glow needs boxCells > 0, padCells >= 0 and sigmaCells > 0 (got $boxCells, $padCells, $sigmaCells)"
    }
    val side = boxCells + 2 * padCells
    val profile = FloatArray(side)
    // Mirrored so both halves are bit-identical.
    for (i in 0..(side - 1) / 2) {
        val x = i + 0.5f - padCells
        profile[i] = gaussianCdf(x / sigmaCells) - gaussianCdf((x - boxCells) / sigmaCells)
        profile[side - 1 - i] = profile[i]
    }
    val bytes = ByteArray(side * side)
    for (y in 0 until side) {
        for (x in 0 until side) {
            val alpha = peakAlpha * (profile[x] * profile[y])
            bytes[y * side + x] = (alpha * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()
        }
    }
    return bytes
}

/** A generated shadow: [image] covers the box plus [padCells] cells of padding per side, [cellPx] pixels each. */
internal class GlowImage(val image: ImageBitmap, val padCells: Int, val cellPx: Float)

/** Builds the shadow of a square box of [boxPx] pixels for a blur [radiusPx], as an Alpha8 image. */
internal fun createGlowImage(boxPx: Int, radiusPx: Float, peakAlpha: Float): GlowImage {
    val boxCells = glowBoxCells(boxPx, GlowCellPx)
    // The cells tile the box exactly, so the box edges land on whole cells.
    val cellPx = if (boxPx > 0) boxPx.toFloat() / boxCells else GlowCellPx.toFloat()
    val sigmaCells = glowSigmaPx(radiusPx) / cellPx
    val padCells = glowPadCells(sigmaCells)
    val bytes = generateGlowAlpha(boxCells, padCells, sigmaCells, peakAlpha)
    val side = boxCells + 2 * padCells
    val bitmap = createBitmap(side, side, Bitmap.Config.ALPHA_8)
    bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(bytes))
    return GlowImage(bitmap.asImageBitmap(), padCells, cellPx)
}
