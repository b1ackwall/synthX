package com.v1per.synthx.ui.components

import android.graphics.BlurMaskFilter
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RadialGradientShader
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import com.v1per.synthx.ui.theme.Motion
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

enum class BlobShapeMode { Circle, Organic }

data class RandomizerConfig(
    val enabled: Boolean = false,
    val min: Float = 0.2f,
    val max: Float = 0.8f,
    val frequencyMillis: Int = 4000,
    val transitionMillis: Int = 1500,
)

data class BlobParams(
    val enabled: Boolean = true,
    val hue: Float = 125f,
    val saturation: Float = 0.8f,
    val lightness: Float = 0.5f,
    val sizeFraction: Float = 0.5f,
    val speedFraction: Float = 0.08f,
    val collisionUsesEdge: Boolean = true,
    val opacity: Float = 0.4f,
    val opacityRandomizer: RandomizerConfig = RandomizerConfig(),
    val sizeRandomizer: RandomizerConfig = RandomizerConfig(),
    val pointCount: Int = 8,
    val wobbleAmountFraction: Float = 0.15f,
    val wobbleFrequencyMillis: Int = 2500,
    val wobbleTransitionMillis: Int = 1500,
)

@Composable
fun AmbientBlobBackground(
    blobs: List<BlobParams>,
    baseSurfaceColor: Color,
    blurRadiusPx: Float,
    shapeMode: BlobShapeMode,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit = {},
) {
    var layerSize by remember { mutableStateOf(IntSize.Zero) }
    val animations = blobs.mapIndexed { index, blob ->
        key(index) {
            if (blob.enabled) {
                rememberBlobAnimation(index, blob, layerSize)
            } else {
                null
            }
        }
    }
    // Shape-only state lives separately so toggling the mode never recreates movement
    // or opacity/size randomizers. Circle mode and disabled blobs run no wobble loops.
    val wobbleOffsets = blobs.mapIndexed { index, blob ->
        key(index) {
            if (shapeMode == BlobShapeMode.Organic && blob.enabled) {
                rememberOrganicWobble(index, blob)
            } else {
                emptyList()
            }
        }
    }

    val paint = remember(blurRadiusPx) {
        Paint().apply {
            asFrameworkPaint().apply {
                isAntiAlias = true
                // BlurMaskFilter requires a positive radius; zero means no blur.
                maskFilter = if (blurRadiusPx > 0f) {
                    BlurMaskFilter(blurRadiusPx, BlurMaskFilter.Blur.NORMAL)
                } else {
                    null
                }
            }
        }
    }

    Box(modifier = modifier.fillMaxSize().background(baseSurfaceColor)) {
        Spacer(
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds()
                .onSizeChanged { layerSize = it }
                .drawWithCache {
                    val colors = blobs.map { blob ->
                        Color.hsl(blob.hue, blob.saturation, blob.lightness)
                    }
                    val organicPath = Path()
                    onDrawBehind {
                        // All shapes share this drawing layer and blur paint. Read animated
                        // state only here, so drifting invalidates drawing, not composition.
                        drawIntoCanvas { canvas ->
                            animations.forEachIndexed { index, animation ->
                                if (animation == null) return@forEachIndexed
                                val radius = animation.sizeFraction.value * size.minDimension
                                if (radius <= 0f) return@forEachIndexed

                                val center = animation.motion.position.value
                                val color = colors[index].copy(alpha = animation.opacity.value)
                                paint.asFrameworkPaint().shader = RadialGradientShader(
                                    center = center,
                                    radius = radius,
                                    colors = listOf(color, color.copy(alpha = 0f)),
                                )
                                when (shapeMode) {
                                    BlobShapeMode.Circle -> canvas.drawCircle(center, radius, paint)
                                    BlobShapeMode.Organic -> {
                                        updateOrganicPath(organicPath, center, radius, wobbleOffsets[index])
                                        canvas.drawPath(organicPath, paint)
                                    }
                                }
                            }
                        }
                    }
                },
        )
        content()
    }
}

@Composable
private fun rememberOrganicWobble(blobIndex: Int, blob: BlobParams): List<State<Float>> {
    val pointCount = blob.pointCount.coerceIn(6, 10)
    return List(pointCount) { pointIndex ->
        key(blobIndex, pointCount, pointIndex) {
            // Reset when the amplitude changes so offsets stay inside the new range.
            val offset = remember(blob.wobbleAmountFraction) { Animatable(0f) }
            LaunchedEffect(
                blobIndex,
                pointIndex,
                pointCount,
                blob.wobbleFrequencyMillis,
                blob.wobbleTransitionMillis,
                blob.wobbleAmountFraction,
            ) {
                val frequency = blob.wobbleFrequencyMillis.coerceAtLeast(1)
                val amount = blob.wobbleAmountFraction.coerceIn(0f, 0.5f)
                // Independent initial phases prevent the perimeter from pulsing in unison.
                delay(Random.nextInt(frequency).toLong())
                while (isActive) {
                    delay(frequency.toLong())
                    val target = Random.nextFloat() * 2f * amount - amount
                    offset.animateTo(
                        target,
                        tween(
                            blob.wobbleTransitionMillis.coerceAtLeast(0),
                            easing = Motion.EasingStandard,
                        ),
                    )
                }
            }
            offset.asState()
        }
    }
}

private fun updateOrganicPath(path: Path, center: Offset, radius: Float, offsets: List<State<Float>>) {
    path.reset()
    val count = offsets.size
    if (count < 3) return
    val points = List(count) { index ->
        val angle = 2f * PI.toFloat() * index / count
        val pointRadius = radius * (1f + offsets[index].value)
        center + Offset(cos(angle) * pointRadius, sin(angle) * pointRadius)
    }
    path.moveTo(points[0].x, points[0].y)
    for (index in points.indices) {
        val previous = points[(index - 1 + count) % count]
        val current = points[index]
        val next = points[(index + 1) % count]
        val nextNext = points[(index + 2) % count]
        val cp1 = current + (next - previous) / 6f
        val cp2 = next - (nextNext - current) / 6f
        path.cubicTo(cp1.x, cp1.y, cp2.x, cp2.y, next.x, next.y)
    }
    path.close()
}

private class BlobMotionState {
    val position = mutableStateOf(Offset.Zero)
    var initialized = false
    var directionAngleRadians = Random.nextFloat() * 2f * PI.toFloat()
}

private class BlobAnimation(
    val motion: BlobMotionState,
    val opacity: State<Float>,
    val sizeFraction: State<Float>,
)

@Composable
private fun rememberBlobAnimation(index: Int, blob: BlobParams, layerSize: IntSize): BlobAnimation {
    val motion = remember { BlobMotionState() }
    val opacity = rememberRandomizedValue(index, blob.opacity, blob.opacityRandomizer)
    val sizeFraction = rememberRandomizedValue(index, blob.sizeFraction, blob.sizeRandomizer)
    // Switching a randomizer changes its State object; the running movement loop must use
    // the latest one without restarting on every size/color slider update.
    val currentSizeFraction by rememberUpdatedState(sizeFraction)

    LaunchedEffect(index, blob.speedFraction, blob.collisionUsesEdge, blob.enabled, layerSize) {
        if (layerSize.width <= 0 || layerSize.height <= 0) return@LaunchedEffect
        val width = layerSize.width.toFloat()
        val height = layerSize.height.toFloat()
        val shorterDimension = min(width, height)
        if (!motion.initialized) {
            // Spawn across the whole layer, even if the blob initially crosses an edge.
            motion.position.value = Offset(Random.nextFloat() * width, Random.nextFloat() * height)
            motion.initialized = true
        }
        var previousFrame = 0L
        while (isActive) {
            withFrameNanos { frameTime ->
                if (previousFrame != 0L) {
                    val deltaSeconds = (frameTime - previousFrame) / 1_000_000_000f
                    val distance = blob.speedFraction * shorterDimension * deltaSeconds
                    val radius = if (blob.collisionUsesEdge) {
                        currentSizeFraction.value * shorterDimension
                    } else {
                        0f
                    }
                    moveBlob(motion, distance, radius, width, height)
                }
                previousFrame = frameTime
            }
        }
    }
    return BlobAnimation(motion, opacity, sizeFraction)
}

private fun moveBlob(motion: BlobMotionState, distance: Float, radius: Float, width: Float, height: Float) {
    var x = motion.position.value.x + cos(motion.directionAngleRadians) * distance
    var y = motion.position.value.y + sin(motion.directionAngleRadians) * distance

    // If the diameter fills/exceeds an axis, its opposing edge constraints cannot both
    // be satisfied. Use center bounds on just that axis so oversized blobs keep moving.
    val minX = if (radius * 2f < width) radius else 0f
    val maxX = width - minX
    val minY = if (radius * 2f < height) radius else 0f
    val maxY = height - minY
    val hitLeft = x < minX
    val hitRight = x > maxX
    val hitTop = y < minY
    val hitBottom = y > maxY

    if (hitLeft || hitRight || hitTop || hitBottom) {
        val angle = Random.nextFloat() * 2f * PI.toFloat()
        var directionX = cos(angle)
        var directionY = sin(angle)
        // Fold a fresh random heading into the permitted half-plane(s). At corners,
        // both signs are constrained, giving the intersection's inward-facing quadrant.
        if (hitLeft) directionX = abs(directionX)
        if (hitRight) directionX = -abs(directionX)
        if (hitTop) directionY = abs(directionY)
        if (hitBottom) directionY = -abs(directionY)
        motion.directionAngleRadians = atan2(directionY, directionX)

        val insetX = min(0.5f, (maxX - minX) / 2f)
        val insetY = min(0.5f, (maxY - minY) / 2f)
        if (hitLeft) x = minX + insetX
        if (hitRight) x = maxX - insetX
        if (hitTop) y = minY + insetY
        if (hitBottom) y = maxY - insetY
    }
    motion.position.value = Offset(x, y)
}

@Composable
private fun rememberRandomizedValue(index: Int, baseValue: Float, config: RandomizerConfig): State<Float> {
    return if (config.enabled) {
        val animated = remember { Animatable(baseValue) }
        LaunchedEffect(index, config) {
            val lower = min(config.min, config.max).coerceIn(0f, 1f)
            val upper = maxOf(config.min, config.max).coerceIn(0f, 1f)
            while (isActive) {
                delay(config.frequencyMillis.coerceAtLeast(1).toLong())
                val target = Random.nextFloat() * (upper - lower) + lower
                animated.animateTo(
                    target,
                    tween(config.transitionMillis.coerceAtLeast(0), easing = Motion.EasingStandard),
                )
            }
        }
        animated.asState()
    } else {
        rememberUpdatedState(baseValue)
    }
}
