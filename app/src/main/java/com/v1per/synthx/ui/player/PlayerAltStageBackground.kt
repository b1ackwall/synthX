package com.v1per.synthx.ui.player

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.v1per.synthx.BuildConfig
import com.v1per.synthx.ui.components.rememberSystemReducedMotionActive
import com.v1per.synthx.ui.theme.Background
import com.v1per.synthx.ui.theme.SurfaceVariant
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Copy indices back to front: largest first. */
private val CopyDrawOrder = PlayerAltStageStyle.Copies.indices
    .sortedByDescending { PlayerAltStageStyle.Copies[it].sizeFraction }

/**
 * The expanded player's art background: an opaque SurfaceVariant fill, the stretched and drifting
 * base layer, then the four spinning and orbiting copies, all centered on this layer, under one
 * blur, twist and saturation effect. With no [art] only the fill is drawn.
 */
@Composable
internal fun BoxScope.PlayerAltStageBackground(
    art: Bitmap?,
    hasSong: Boolean,
    fullHeight: Dp,
    progress: Float,
) {
    val paused = shouldPausePlayerAltStageMotion(hasSong, progress, rememberSystemReducedMotionActive())
    val motion = rememberAltStageMotion(paused)
    val image = remember(art) { art?.asImageBitmap() }
    val hasArt = image != null
    var layerSize by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current.density
    // Rebuilt only when the layer size, density, art presence, blur radius, twist or edge mode changes.
    // Debug builds draw with the Home debug sheet's values; release builds use the locked ones.
    val debugState = PlayerBackgroundDebugStateHolder.state.value
    val blurDp = effectivePlayerBackgroundBlurDp(BuildConfig.DEBUG, debugState)
    val twistEnabled = effectivePlayerBackgroundTwistEnabled(BuildConfig.DEBUG, debugState)
    val blurEdgeMode = effectivePlayerBackgroundBlurEdgeMode(BuildConfig.DEBUG, debugState)
    val effect = remember(layerSize, density, hasArt, blurDp, twistEnabled, blurEdgeMode) {
        if (hasArt) altStageEffect(layerSize, density, blurDp, twistEnabled, blurEdgeMode) else null
    }
    // Full-height and bottom-anchored, so the layer keeps one size through the sheet's morph.
    val boundsModifier = remember(fullHeight) {
        Modifier
            .fillMaxWidth()
            .align(Alignment.BottomCenter)
            .wrapContentHeight(align = Alignment.Bottom, unbounded = true)
            .requiredHeight(fullHeight)
    }
    Canvas(
        boundsModifier
            .altStageScrim(altStageScrimColor(PlayerAltStageStyle.ScrimAlpha))
            .onSizeChanged { layerSize = it }
            .graphicsLayer {
                renderEffect = effect
                clip = true
            },
    ) {
        // Read here so a motion update invalidates only this draw pass.
        motion.frame.longValue
        drawRect(SurfaceVariant)
        val source = image ?: return@Canvas
        drawAltStageArt(source, motion.angles)
    }
}

/** The scrim drawn over the finished background, or null while [alpha] is 0. */
internal fun altStageScrimColor(alpha: Float): Color? =
    if (alpha > 0f) Background.copy(alpha = alpha) else null

private fun Modifier.altStageScrim(color: Color?): Modifier =
    if (color == null) {
        this
    } else {
        drawWithContent {
            drawContent()
            drawRect(color)
        }
    }

/** Draws the base layer, then the copies largest first, each spinning about its own center. */
private fun DrawScope.drawAltStageArt(art: ImageBitmap, angles: AltStageAngles) {
    val width = size.width
    val center = this.center
    val src = IntSize(art.width, art.height)
    drawBaseLayer(art, src, angles.baseRotation)
    for (index in CopyDrawOrder) {
        val copy = PlayerAltStageStyle.Copies[index]
        val side = (copy.sizeFraction * width).roundToInt()
        if (side <= 0) continue
        val slot = PlayerAltStageStyle.OrbitingCopies.indexOf(index)
        val position = if (slot < 0) center else {
            val angle = angles.orbit[slot]
            val r = copy.orbitRadius * width
            center + Offset(cos(angle) * r, sin(angle) * r)
        }
        withTransform({
            translate(position.x, position.y)
            rotate(Math.toDegrees(angles.spin[index].toDouble()).toFloat(), pivot = Offset.Zero)
        }) {
            drawImage(
                image = art,
                srcOffset = IntOffset.Zero,
                srcSize = src,
                dstOffset = IntOffset(-side / 2, -side / 2),
                dstSize = IntSize(side, side),
                filterQuality = FilterQuality.High,
            )
        }
    }
}

/**
 * Draws the art stretched to [PlayerAltStageStyle.BaseScale] times the layer's width and height,
 * rotated in place by [rotation] and scaled so both sides reach the layer diagonal, so no corner
 * is exposed at any angle.
 */
private fun DrawScope.drawBaseLayer(art: ImageBitmap, src: IntSize, rotation: Float) {
    val layerWidth = size.width
    val layerHeight = size.height
    val scale = PlayerAltStageStyle.BaseScale.coerceAtLeast(1f)
    var width = scale * layerWidth
    var height = scale * layerHeight
    val diagonal = sqrt(layerWidth * layerWidth + layerHeight * layerHeight)
    val cover = maxOf(1f, diagonal / width, diagonal / height)
    width *= cover
    height *= cover
    val dst = IntSize(ceil(width).toInt() + 2, ceil(height).toInt() + 2)
    withTransform({
        translate(center.x, center.y)
        rotate(Math.toDegrees(rotation.toDouble()).toFloat(), pivot = Offset.Zero)
    }) {
        drawImage(
            image = art,
            srcOffset = IntOffset.Zero,
            srcSize = src,
            dstOffset = IntOffset(-dst.width / 2, -dst.height / 2),
            dstSize = dst,
            filterQuality = FilterQuality.High,
        )
    }
}
