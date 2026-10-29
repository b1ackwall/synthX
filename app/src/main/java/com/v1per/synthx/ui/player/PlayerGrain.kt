package com.v1per.synthx.ui.player

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.platform.LocalDensity
import kotlin.random.Random

/** Side of the square noise tile, in pixels. */
internal const val GrainTileSidePx = 256

/**
 * The pixels of a [GrainTileSidePx] square of opaque grey noise. Each value is mid grey plus its random
 * offset scaled by [contrast], one value per pixel. The same [seed] always gives the same pixels.
 */
internal fun generateGrainPixels(seed: Int, contrast: Float): IntArray {
    val random = Random(seed)
    val pixels = IntArray(GrainTileSidePx * GrainTileSidePx)
    for (i in pixels.indices) {
        val v = ((0.5f + (random.nextFloat() - 0.5f) * contrast) * 255f).toInt().coerceIn(0, 255)
        pixels[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    }
    return pixels
}

/** Device pixels covered by one tile pixel at [density]. */
internal fun grainScale(density: Float): Float = PlayerAltStageStyle.GrainSizeDp * density

/**
 * Draws the grain over this node's content at full resolution. The tile and its paint are created once
 * and reused by every draw; strength and blend come from [PlayerAltStageStyle].
 */
@Composable
internal fun Modifier.playerGrain(): Modifier {
    val density = LocalDensity.current.density
    val paint = remember {
        val pixels = generateGrainPixels(PlayerAltStageStyle.GrainSeed, PlayerAltStageStyle.GrainContrast)
        val tile = Bitmap.createBitmap(pixels, GrainTileSidePx, GrainTileSidePx, Bitmap.Config.ARGB_8888)
        Paint().apply {
            shader = ImageShader(tile.asImageBitmap(), TileMode.Repeated, TileMode.Repeated)
            // Nearest sampling keeps each speckle a hard-edged square.
            filterQuality = FilterQuality.None
            alpha = PlayerAltStageStyle.GrainStrength
            blendMode = PlayerAltStageStyle.GrainBlendMode
        }
    }
    val scale = grainScale(density)
    val drawGrain = remember(paint, scale) {
        Modifier.drawWithContent {
            drawContent()
            drawIntoCanvas { canvas ->
                canvas.save()
                canvas.scale(scale, scale)
                canvas.drawRect(0f, 0f, size.width / scale, size.height / scale, paint)
                canvas.restore()
            }
        }
    }
    return then(drawGrain)
}
