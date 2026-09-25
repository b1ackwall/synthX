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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativePaint
import com.v1per.synthx.ui.theme.Accent
import com.v1per.synthx.ui.theme.Background
import com.v1per.synthx.ui.theme.Motion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val GradientColors = listOf(
    Accent.copy(alpha = 0.4f),
    Background,
    Accent.copy(alpha = 0.4f),
)

// Start/end point pairs (in fractions of the layer size) the gradient drifts between.
private val TargetPoints = listOf(
    Pair(Offset(0f, 0f), Offset(1f, 1f)),
    Pair(Offset(1f, 0f), Offset(0f, 1f)),
    Pair(Offset(0.5f, 0f), Offset(0.5f, 1f)),
    Pair(Offset(0f, 0.5f), Offset(1f, 0.5f)),
    Pair(Offset(0.2f, 0.8f), Offset(0.8f, 0.2f)),
)

// Deliberately its own constant, not Motion.DurationSlow (500ms): that token is sized for UI
// transitions, and would turn this ambient background drift into a jarring snap. Only the
// *easing* is shared with Motion.kt below — same curve shape, independent duration.
private const val ShiftDurationMillis = 8000
private const val BlurRadiusPx = 200f

@Composable
fun AnimatedGradientBackground(
    modifier: Modifier = Modifier,
    gradientAlpha: Float = 1f,
    content: @Composable BoxScope.() -> Unit,
) {
    val startX = remember { Animatable(0f) }
    val startY = remember { Animatable(0f) }
    val endX = remember { Animatable(1f) }
    val endY = remember { Animatable(1f) }

    LaunchedEffect(Unit) {
        // Start at 1: index 0 matches the initial values, animating to it would idle a cycle.
        var targetIndex = 1
        while (true) {
            val next = TargetPoints[targetIndex % TargetPoints.size]
            val animSpec = tween<Float>(
                durationMillis = ShiftDurationMillis,
                easing = Motion.EasingStandard,
            )
            launch { startX.animateTo(next.first.x, animSpec) }
            launch { startY.animateTo(next.first.y, animSpec) }
            launch { endX.animateTo(next.second.x, animSpec) }
            launch { endY.animateTo(next.second.y, animSpec) }
            delay(ShiftDurationMillis.toLong())
            targetIndex++
        }
    }

    // Built once for the composable's lifetime: neither the blur radius nor anti-aliasing
    // depend on the animated points, so there's nothing here that should rebuild per frame.
    val paint = remember {
        Paint().apply {
            nativePaint.apply {
                isAntiAlias = true
                maskFilter = BlurMaskFilter(BlurRadiusPx, BlurMaskFilter.Blur.NORMAL)
            }
        }
    }

    Box(modifier = modifier.fillMaxSize().background(Background)) {
        // Blurred gradient layer; content stays on top, unblurred. The cache block below
        // reads no animated state, so it only reruns on size change, not every frame.
        Spacer(
            modifier = Modifier
                .fillMaxSize()
                // The 200px BlurMaskFilter paints past the layout edges; clip so the glow
                // can't bleed into the system bar areas outside these bounds.
                .clipToBounds()
                .drawWithCache {
                    onDrawBehind {
                        // LinearGradientShader is immutable once built, so a fresh one is
                        // unavoidable each frame the endpoints move; everything else on
                        // `paint` (blur, anti-aliasing) is reused as-is.
                        paint.nativePaint.shader = LinearGradientShader(
                            from = Offset(startX.value * size.width, startY.value * size.height),
                            to = Offset(endX.value * size.width, endY.value * size.height),
                            colors = GradientColors,
                        )
                        // Fades only this gradient layer; the black base coat and the
                        // content slot stay fully opaque.
                        paint.nativePaint.alpha = (gradientAlpha * 255).toInt().coerceIn(0, 255)
                        drawIntoCanvas { canvas ->
                            canvas.drawRect(Rect(Offset.Zero, size), paint)
                        }
                    }
                },
        )
        content()
    }
}
