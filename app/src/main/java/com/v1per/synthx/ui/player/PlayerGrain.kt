package com.v1per.synthx.ui.player

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.v1per.synthx.BuildConfig
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
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

/** How the grain combines with the background pixels under it. */
internal enum class GrainBlend(val mode: BlendMode) {
    Plain(BlendMode.SrcOver),
    Overlay(BlendMode.Overlay),
    SoftLight(BlendMode.Softlight),
}

/** Everything that shapes the grain layer. The defaults are the tuning panel's starting values. */
internal data class PlayerGrainSettings(
    val enabled: Boolean = true,
    val strength: Float = 0.12f,
    val sizeDp: Float = 1.5f,
    val contrast: Float = 0.5f,
    val blend: GrainBlend = GrainBlend.SoftLight,
    val colored: Boolean = false,
    val pattern: Int = 1,
)

/**
 * The grain to draw, or null when nothing is drawn. Debug builds read [state]; release builds read
 * [releaseStrength] alone, so a strength of 0 draws nothing and allocates nothing.
 */
internal fun effectivePlayerGrain(
    debug: Boolean,
    state: PlayerGrainSettings,
    releaseStrength: Float = PlayerAltStageStyle.GrainStrength,
): PlayerGrainSettings? {
    val settings = if (debug) state else PlayerGrainSettings(strength = releaseStrength)
    return settings.takeIf { it.enabled && it.strength > 0f }
}

/**
 * A [GrainTileSidePx] square of opaque noise. Each value is mid grey plus its random offset scaled by
 * [contrast]; grey mode uses one value per pixel, colour mode one per channel. The same
 * [pattern] always gives the same pixels.
 */
internal fun generateGrainTile(pattern: Int, colored: Boolean, contrast: Float): Bitmap {
    val random = Random(pattern)
    val pixels = IntArray(GrainTileSidePx * GrainTileSidePx)
    fun channel(): Int = ((0.5f + (random.nextFloat() - 0.5f) * contrast) * 255f).toInt().coerceIn(0, 255)
    for (i in pixels.indices) {
        val r = channel()
        val g = if (colored) channel() else r
        val b = if (colored) channel() else r
        pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
    return Bitmap.createBitmap(pixels, GrainTileSidePx, GrainTileSidePx, Bitmap.Config.ARGB_8888)
}

/** The grain for the current build: the Home panel's values in debug builds, else the locked strength. */
internal fun currentPlayerGrain(): PlayerGrainSettings? = effectivePlayerGrain(
    BuildConfig.DEBUG,
    if (BuildConfig.DEBUG) PlayerGrainDebugStateHolder.state.value else PlayerGrainSettings(),
)

/**
 * Draws [grain] over this node's content at full resolution. The tile and its paint are remembered
 * per pattern, colour mode and contrast; strength, size and blend are applied at draw time.
 * Nothing is created while [grain] is null.
 */
@Composable
internal fun Modifier.playerGrain(grain: PlayerGrainSettings?): Modifier {
    if (grain == null) return this
    val density = LocalDensity.current.density
    val paint = remember(grain.pattern, grain.colored, grain.contrast) {
        Paint().apply {
            shader = ImageShader(
                generateGrainTile(grain.pattern, grain.colored, grain.contrast).asImageBitmap(),
                TileMode.Repeated,
                TileMode.Repeated,
            )
            // Nearest sampling keeps each speckle a hard-edged square.
            filterQuality = FilterQuality.None
        }
    }
    val scale = grain.sizeDp * density
    val drawGrain = remember(paint, grain.strength, grain.blend, scale) {
        Modifier.drawWithContent {
            drawContent()
            paint.alpha = grain.strength
            paint.blendMode = grain.blend.mode
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
