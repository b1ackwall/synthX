package com.v1per.synthx.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlinx.coroutines.isActive

data class RandomizerConfig(
    val enabled: Boolean = false,
    val min: Float = 0.2f,
    val max: Float = 0.8f,
    val speed: Float = 0.08f, // Fixed radians per second, matching wobbleSpeed.
)

data class BlobParams(
    val enabled: Boolean = true,
    // Caller-supplied degrees (0..360); live palette updates preserve animation state.
    val hue: Float = 125f,
    val saturation: Float = 0.8f,
    val lightness: Float = 0.5f,
    val sizeFraction: Float = 0.5f,
    val speedFraction: Float = 0.08f,
    val opacity: Float = 0.4f,
    val gradientFalloffFraction: Float = 0.0f, // Solid-color radius fraction, 0f..0.9f.
    val opacityRandomizer: RandomizerConfig = RandomizerConfig(),
    val sizeRandomizer: RandomizerConfig = RandomizerConfig(),
    val pointCount: Int = 6,
    val wobbleAmountFraction: Float = 0.5f,
    val wobbleSpeed: Float = 0.08f,
)

@Composable
fun AmbientBlobBackground(
    blobs: List<BlobParams>,
    baseSurfaceColor: Color,
    blurRadiusDp: Dp,
    modifier: Modifier = Modifier,
    paused: Boolean = false,
    content: @Composable BoxScope.() -> Unit = {},
) {
    var layerSize by remember { mutableStateOf(IntSize.Zero) }
    val animations = blobs.mapIndexed { index, blob ->
        key(index) {
            if (blob.enabled) {
                rememberBlobAnimation(index, blob, layerSize, paused)
            } else {
                null
            }
        }
    }
    // Wobble lives separately from movement and opacity/size randomizers.
    val wobbles = blobs.mapIndexed { index, blob ->
        key(index) {
            if (blob.enabled) {
                rememberOrganicWobble(index, blob, layerSize, paused)
            } else {
                null
            }
        }
    }

    Box(modifier = modifier.fillMaxSize().background(baseSurfaceColor)) {
        Spacer(
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds()
                // Keep the blur's graphics layer while paused. Frozen animation state
                // stops frame-driven draw invalidations without changing its appearance.
                .blur(radius = blurRadiusDp)
                .onSizeChanged { layerSize = it }
                .drawWithCache {
                    val colors = blobs.map { blob ->
                        Color.hsl(blob.hue, blob.saturation, blob.lightness)
                    }
                    val falloffs = blobs.map { it.gradientFalloffFraction.coerceIn(0f, 0.9f) }
                    val organicPath = Path()
                    onDrawBehind {
                        // All shapes share this blurred drawing layer. Read animated
                        // state only here, so drifting invalidates drawing, not composition.
                        animations.forEachIndexed { index, animation ->
                            if (animation == null) return@forEachIndexed
                            val wobble = wobbles[index] ?: return@forEachIndexed
                            val radius = animation.sizeFraction.value * size.minDimension
                            if (radius <= 0f) return@forEachIndexed

                            val center = animation.motion.position.value
                            val color = colors[index].copy(alpha = animation.opacity.value)
                            val gradientRadius = updateOrganicPath(
                                organicPath, center, radius, wobble.offsets.value, wobble.directions,
                            )
                            drawPath(
                                path = organicPath,
                                brush = Brush.radialGradient(
                                    0f to color,
                                    falloffs[index] to color,
                                    1f to color.copy(alpha = 0f),
                                    center = center,
                                    radius = gradientRadius,
                                ),
                                blendMode = BlendMode.Plus,
                            )
                        }
                    }
                },
        )
        content()
    }
}

@Composable
private fun rememberOrganicWobble(
    blobIndex: Int,
    blob: BlobParams,
    layerSize: IntSize,
    paused: Boolean,
): OrganicWobble {
    val pointCount = blob.pointCount.coerceIn(6, 8)
    var phase by remember { mutableStateOf(0.0) }
    val speed by rememberUpdatedState(blob.wobbleSpeed)
    val amount by rememberUpdatedState(blob.wobbleAmountFraction.coerceIn(0f, 0.5f))
    LaunchedEffect(blobIndex, layerSize, paused) {
        if (paused || layerSize.width <= 0 || layerSize.height <= 0) return@LaunchedEffect
        // Restart only the frame baseline; the remembered phase survives pauses/resizes.
        var previousFrame = 0L
        while (isActive) {
            withFrameNanos { frameTime ->
                if (previousFrame != 0L) {
                    val deltaSeconds = (frameTime - previousFrame) / 1_000_000_000.0
                    // Speed is measured in radians per second.
                    phase += deltaSeconds * speed.coerceAtLeast(0f)
                }
                previousFrame = frameTime
            }
        }
    }
    val directions = remember(pointCount) {
        List(pointCount) { index ->
            val angle = 2f * PI.toFloat() * index / pointCount
            Offset(cos(angle), sin(angle))
        }
    }
    return remember(blobIndex, pointCount) {
        val startingAngles = DoubleArray(pointCount) { pointIndex ->
            2.0 * PI * pointIndex / pointCount + blobIndex * 0.731
        }
        OrganicWobble(
            directions = directions,
            offsets = derivedStateOf {
                // One snapshot reader/pass per blob, retaining both original sine terms.
                val currentPhase = phase
                val currentAmount = amount
                FloatArray(pointCount) { pointIndex ->
                    val angle = currentPhase + startingAngles[pointIndex]
                    ((sin(angle) * 0.7 + sin(angle * 1.618 + pointIndex) * 0.3) * currentAmount).toFloat()
                }
            },
        )
    }
}

private class OrganicWobble(
    val directions: List<Offset>,
    val offsets: State<FloatArray>,
)

private fun updateOrganicPath(
    path: Path,
    center: Offset,
    radius: Float,
    offsets: FloatArray,
    directions: List<Offset>,
): Float {
    path.reset()
    val count = offsets.size
    if (count < 3) return radius
    val points = List(count) { index ->
        val pointRadius = radius * (1f + offsets[index])
        center + directions[index] * pointRadius
    }
    path.moveTo(points[0].x, points[0].y)
    var gradientRadiusSquared = points.maxOf { (it - center).getDistanceSquared() }
    for (index in points.indices) {
        val previous = points[(index - 1 + count) % count]
        val current = points[index]
        val next = points[(index + 1) % count]
        val nextNext = points[(index + 2) % count]
        val cp1 = current + (next - previous) / 6f
        val cp2 = next - (nextNext - current) / 6f
        // A cubic stays inside its control-point hull. Include the handles so the
        // gradient also covers any curve overshoot beyond the live perimeter points.
        gradientRadiusSquared = maxOf(
            gradientRadiusSquared,
            (cp1 - center).getDistanceSquared(),
            (cp2 - center).getDistanceSquared(),
        )
        path.cubicTo(cp1.x, cp1.y, cp2.x, cp2.y, next.x, next.y)
    }
    path.close()
    return sqrt(gradientRadiusSquared)
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
private fun rememberBlobAnimation(
    index: Int,
    blob: BlobParams,
    layerSize: IntSize,
    paused: Boolean,
): BlobAnimation {
    val motion = remember { BlobMotionState() }
    val inactive = paused || layerSize.width <= 0 || layerSize.height <= 0
    val opacity = rememberRandomizedValue(index, blob.opacity, blob.opacityRandomizer, inactive)
    val sizeFraction = rememberRandomizedValue(index, blob.sizeFraction, blob.sizeRandomizer, inactive)
    LaunchedEffect(index, blob.speedFraction, blob.enabled, layerSize, paused) {
        if (layerSize.width <= 0 || layerSize.height <= 0) return@LaunchedEffect
        val width = layerSize.width.toFloat()
        val height = layerSize.height.toFloat()
        val shorterDimension = min(width, height)
        if (!motion.initialized) {
            // Spawn across the whole layer, even if the blob initially crosses an edge.
            motion.position.value = Offset(Random.nextFloat() * width, Random.nextFloat() * height)
            motion.initialized = true
        }
        // Even an initially collapsed player needs its full-canvas static spawn positions.
        if (paused) return@LaunchedEffect
        var previousFrame = 0L
        while (isActive) {
            withFrameNanos { frameTime ->
                if (previousFrame != 0L) {
                    val deltaSeconds = (frameTime - previousFrame) / 1_000_000_000f
                    val distance = blob.speedFraction * shorterDimension * deltaSeconds
                    moveBlob(motion, distance, width, height)
                }
                previousFrame = frameTime
            }
        }
    }
    return BlobAnimation(motion, opacity, sizeFraction)
}

private fun moveBlob(motion: BlobMotionState, distance: Float, width: Float, height: Float) {
    var x = motion.position.value.x + cos(motion.directionAngleRadians) * distance
    var y = motion.position.value.y + sin(motion.directionAngleRadians) * distance

    val hitLeft = x < 0f
    val hitRight = x > width
    val hitTop = y < 0f
    val hitBottom = y > height

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

        val insetX = min(0.5f, width / 2f)
        val insetY = min(0.5f, height / 2f)
        if (hitLeft) x = insetX
        if (hitRight) x = width - insetX
        if (hitTop) y = insetY
        if (hitBottom) y = height - insetY
    }
    motion.position.value = Offset(x, y)
}

@Composable
private fun rememberRandomizedValue(
    index: Int,
    baseValue: Float,
    config: RandomizerConfig,
    paused: Boolean,
): State<Float> {
    return if (config.enabled) {
        // Each opacity/size call remembers its own phase, independently across blobs.
        var phase by remember(index) { mutableStateOf(Random.nextDouble() * 2.0 * PI) }
        val currentConfig by rememberUpdatedState(config)
        LaunchedEffect(index, paused) {
            if (paused) return@LaunchedEffect
            var previousFrame = 0L
            while (isActive) {
                withFrameNanos { frameTime ->
                    if (previousFrame != 0L) {
                        val deltaSeconds = (frameTime - previousFrame) / 1_000_000_000.0
                        // Match wobble's phase rate; zero pauses without resetting phase.
                        phase += deltaSeconds * currentConfig.speed.coerceIn(0f, 1f)
                    }
                    previousFrame = frameTime
                }
            }
        }
        remember(index) {
            derivedStateOf {
                val lower = min(currentConfig.min, currentConfig.max).coerceIn(0f, 1f)
                val upper = maxOf(currentConfig.min, currentConfig.max).coerceIn(0f, 1f)
                val midpoint = (lower + upper) / 2f
                val amplitude = (upper - lower) / 2f
                (midpoint + sin(phase) * amplitude).toFloat().coerceIn(lower, upper)
            }
        }
    } else {
        rememberUpdatedState(baseValue)
    }
}
