package com.v1per.synthx.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.min

internal data class ExtractedSwatchHsl(
    val hue: Float,
    val saturation: Float,
    val lightness: Float,
)

internal suspend fun extractArtworkSwatches(context: Context, uri: String): List<ExtractedSwatchHsl> =
    withContext(Dispatchers.Default) {
        try {
            val request = ImageRequest.Builder(context)
                .data(uri)
                // Palette reads pixels on the CPU; hardware bitmaps do not support that.
                .allowHardware(false)
                .size(256)
                .build()
            val result = context.imageLoader.execute(request) as? SuccessResult
                ?: return@withContext emptyList()
            val bitmap = result.drawable.toPaletteBitmap()
            val swatches = Palette.from(bitmap).generate().swatches
            val hsl = FloatArray(3)
            val candidates = swatches.sortedByDescending { it.population }.map { swatch ->
                ColorUtils.colorToHSL(swatch.rgb, hsl)
                ExtractedSwatchHsl(hsl[0], hsl[1], hsl[2])
            }
            val selected = mutableListOf<ExtractedSwatchHsl>()
            for (threshold in 20 downTo 1) {
                // Restart the whole greedy pass; retain population order at each threshold.
                selected.clear()
                for (candidate in candidates) {
                    if (selected.all { previous ->
                            val distance = abs(candidate.hue - previous.hue)
                            min(distance, 360f - distance) > threshold.toFloat()
                        }
                    ) {
                        selected += candidate
                        if (selected.size == 3) break
                    }
                }
                if (selected.size == 3) break
            }
            // Reuse only after exhausting the 1-degree pass, preserving full swatch values.
            if (selected.isEmpty()) emptyList() else List(3) { selected[it % selected.size] }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Missing/unreadable artwork or failed quantization keeps hues and references.
            emptyList()
        }
    }

internal fun Drawable.toPaletteBitmap(): Bitmap {
    if (this is BitmapDrawable) return bitmap
    // Bound the fallback allocation, including drawables without intrinsic dimensions.
    val width = intrinsicWidth.coerceIn(1, 256)
    val height = intrinsicHeight.coerceIn(1, 256)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val originalBounds = Rect(bounds)
    try {
        setBounds(0, 0, width, height)
        draw(Canvas(bitmap))
    } finally {
        bounds = originalBounds
    }
    return bitmap
}
