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
import androidx.compose.ui.graphics.drawscope.withTransform
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
    // Caller-supplied degrees (0..360); changing it preserves animation state.
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
    blobAlphaMultiplier: Float = 1f,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val motionPaused = shouldPauseAmbientMotion(paused, rememberSystemReducedMotionActive())
    val drawAlphaMultiplier = blobAlphaMultiplier.coerceIn(0f, 1f)
    var layerSize by remember { mutableStateOf(IntSize.Zero) }
    // R5-F17: composition must stay unconditional regardless of blob.enabled; only the draw is
    // gated (see the blobAlphaMultiplier/radius<=0f skip below). Gating rememberBlobAnimation/rememberOrganicWobble
    // themselves behind `if (blob.enabled)` disposes their remember slots when a blob is disabled,
    // so re-enabling respawns at a fresh random position with phase reset to 0 instead of resuming.
    val animations = blobs.mapIndexed { index, blob ->
        key(index) {
            rememberBlobAnimation(index, blob, layerSize, motionPaused)
        }
    }
    // Wobble lives separately from movement and opacity/size randomizers.
    val wobbles = blobs.mapIndexed { index, blob ->
        key(index) {
            rememberOrganicWobble(index, blob, layerSize, motionPaused)
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
                    // Build gradients in normalized local coordinates. Per-frame translation and
                    // scaling move them with each blob, so immutable Brushes only need rebuilding
                    // when their color/falloff cache inputs change.
                    val brushes = colors.mapIndexed { index, color ->
                        Brush.radialGradient(
                            0f to color,
                            falloffs[index] to color,
                            1f to color.copy(alpha = 0f),
                            center = Offset.Zero,
                            radius = 1f,
                        )
                    }
                    val organicPath = Path()
                    val pointBuffers = blobs.map { blob ->
                        FloatArray(blob.pointCount.coerceIn(6, 8) * 2)
                    }
                    onDrawBehind {
                        if (drawAlphaMultiplier == 0f) return@onDrawBehind
                        // All shapes share this blurred drawing layer. Read animated
                        // state only here, so drifting invalidates drawing, not composition.
                        animations.forEachIndexed { index, animation ->
                            // Enabled is a draw-time visibility flag only; the composition above
                            // always runs so a disabled blob's motion/wobble state keeps living.
                            if (!blobs[index].enabled) return@forEachIndexed
                            val wobble = wobbles[index]
                            val radius = animation.sizeFraction.value * size.minDimension
                            if (radius <= 0f) return@forEachIndexed

                            val center = animation.motion.position.value
                            val normalizedGradientRadius = updateOrganicPath(
                                organicPath,
                                wobble.updateOffsets(),
                                wobble.directions,
                                pointBuffers[index],
                            )
                            val drawScale = radius * normalizedGradientRadius
                            withTransform({
                                translate(center.x, center.y)
                                scale(drawScale, drawScale, pivot = Offset.Zero)
                            }) {
                                drawPath(
                                    path = organicPath,
                                    brush = brushes[index],
                                    alpha = animation.opacity.value * drawAlphaMultiplier,
                                    blendMode = BlendMode.Plus,
                                )
                            }
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
    val phase = remember { mutableStateOf(0.0) }
    val speed by rememberUpdatedState(blob.wobbleSpeed)
    val amount = rememberUpdatedState(blob.wobbleAmountFraction.coerceIn(0f, 0.5f))
    LaunchedEffect(blobIndex, layerSize, paused) {
        if (paused || layerSize.width <= 0 || layerSize.height <= 0) return@LaunchedEffect
        // Restart only the frame baseline; the remembered phase survives pauses/resizes.
        var previousFrame = 0L
        while (isActive) {
            withFrameNanos { frameTime ->
                if (previousFrame != 0L) {
                    val deltaSeconds = (frameTime - previousFrame) / 1_000_000_000.0
                    // Speed is measured in radians per second.
                    phase.value += deltaSeconds * speed.coerceAtLeast(0f)
                }
                previousFrame = frameTime
            }
        }
    }
    val directions = remember(pointCount) {
        FloatArray(pointCount * 2).also { values ->
            repeat(pointCount) { index ->
                val angle = 2f * PI.toFloat() * index / pointCount
                values[index * 2] = cos(angle)
                values[index * 2 + 1] = sin(angle)
            }
        }
    }
    return remember(blobIndex, pointCount) {
        val startingAngles = DoubleArray(pointCount) { pointIndex ->
            2.0 * PI * pointIndex / pointCount + blobIndex * 0.731
        }
        OrganicWobble(
            directions = directions,
            offsets = FloatArray(pointCount),
            startingAngles = startingAngles,
            phase = phase,
            amount = amount,
        )
    }
}

private class OrganicWobble(
    val directions: FloatArray,
    private val offsets: FloatArray,
    private val startingAngles: DoubleArray,
    private val phase: State<Double>,
    private val amount: State<Float>,
) {
    fun updateOffsets(): FloatArray {
        // Reading phase/amount from the draw pass retains snapshot-driven invalidation while the
        // same primitive buffer is rewritten instead of allocating a new array every frame.
        val currentPhase = phase.value
        val currentAmount = amount.value
        for (pointIndex in offsets.indices) {
            val angle = currentPhase + startingAngles[pointIndex]
            offsets[pointIndex] =
                ((sin(angle) * 0.7 + sin(angle * 1.618 + pointIndex) * 0.3) * currentAmount).toFloat()
        }
        return offsets
    }
}

private fun updateOrganicPath(
    path: Path,
    offsets: FloatArray,
    directions: FloatArray,
    points: FloatArray,
): Float {
    path.reset()
    val count = offsets.size
    if (count < 3) return 1f
    for (index in 0 until count) {
        val pointRadius = 1f + offsets[index]
        points[index * 2] = directions[index * 2] * pointRadius
        points[index * 2 + 1] = directions[index * 2 + 1] * pointRadius
    }
    var gradientRadiusSquared = 0f
    for (index in 0 until count) {
        val x = points[index * 2]
        val y = points[index * 2 + 1]
        gradientRadiusSquared = maxOf(gradientRadiusSquared, x * x + y * y)
    }
    for (index in 0 until count) {
        val previous = (index - 1 + count) % count
        val next = (index + 1) % count
        val nextNext = (index + 2) % count
        val currentX = points[index * 2]
        val currentY = points[index * 2 + 1]
        val nextX = points[next * 2]
        val nextY = points[next * 2 + 1]
        val cp1X = currentX + (nextX - points[previous * 2]) / 6f
        val cp1Y = currentY + (nextY - points[previous * 2 + 1]) / 6f
        val cp2X = nextX - (points[nextNext * 2] - currentX) / 6f
        val cp2Y = nextY - (points[nextNext * 2 + 1] - currentY) / 6f
        // A cubic stays inside its control-point hull. Include the handles so the
        // gradient also covers any curve overshoot beyond the live perimeter points.
        gradientRadiusSquared = maxOf(
            gradientRadiusSquared,
            cp1X * cp1X + cp1Y * cp1Y,
            cp2X * cp2X + cp2Y * cp2Y,
        )
    }
    val gradientRadius = sqrt(gradientRadiusSquared)
    path.moveTo(points[0] / gradientRadius, points[1] / gradientRadius)
    for (index in 0 until count) {
        val previous = (index - 1 + count) % count
        val next = (index + 1) % count
        val nextNext = (index + 2) % count
        val currentX = points[index * 2]
        val currentY = points[index * 2 + 1]
        val nextX = points[next * 2]
        val nextY = points[next * 2 + 1]
        val cp1X = currentX + (nextX - points[previous * 2]) / 6f
        val cp1Y = currentY + (nextY - points[previous * 2 + 1]) / 6f
        val cp2X = nextX - (points[nextNext * 2] - currentX) / 6f
        val cp2Y = nextY - (points[nextNext * 2 + 1] - currentY) / 6f
        path.cubicTo(
            cp1X / gradientRadius,
            cp1Y / gradientRadius,
            cp2X / gradientRadius,
            cp2Y / gradientRadius,
            nextX / gradientRadius,
            nextY / gradientRadius,
        )
    }
    path.close()
    return gradientRadius
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
