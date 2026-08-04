package com.v1per.synthx.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.v1per.synthx.ui.theme.Accent
import com.v1per.synthx.ui.theme.Motion
import com.v1per.synthx.ui.theme.SurfaceVariant
import com.v1per.synthx.ui.theme.TextTertiary

@Composable
fun GlitchPlaceholder(
    songId: String,
    isPlaying: Boolean,
    size: Dp = 48.dp,
    modifier: Modifier = Modifier,
) {
    val strips = remember(songId) {
        val hash = songId.hashCode()
        val count = 4 + ((hash and Int.MAX_VALUE) % 3)
        (0 until count).map { i ->
            val h1 = hash xor (i * 0x9e3779b9.toInt())
            val h2 = h1 * 0x6c62272e.toInt()
            val h3 = h2 xor (h2 ushr 16)
            GlitchStrip(
                yFrac = (h1 and Int.MAX_VALUE).toFloat() / Int.MAX_VALUE.toFloat(),
                heightFrac = 0.04f + ((h2 and Int.MAX_VALUE).toFloat() / Int.MAX_VALUE.toFloat()) * 0.22f,
                widthFrac = 0.3f + ((h3 and Int.MAX_VALUE).toFloat() / Int.MAX_VALUE.toFloat()) * 0.7f,
                colorIndex = (h1 and Int.MAX_VALUE) % 3,
                offsetSign = if (i % 2 == 0) 1f else -1f,
            )
        }
    }

    val glitchColors = listOf(
        Accent.copy(alpha = 0.6f),
        TextTertiary.copy(alpha = 0.4f),
        SurfaceVariant.copy(alpha = 0.8f),
    )

    // Always running — value only applied when isPlaying
    val infiniteTransition = rememberInfiniteTransition(label = "glitch")
    val animOffset by infiniteTransition.animateFloat(
        initialValue = -3f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(
            animation = tween(Motion.DurationNormal, easing = Motion.EasingStandard),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "glitch_offset",
    )
    val pixelOffset = if (isPlaying) animOffset else 0f

    Box(
        modifier = modifier.size(size)
            .clip(MaterialTheme.shapes.small),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(color = SurfaceVariant)
            strips.forEach { strip ->
                val y = strip.yFrac * this.size.height
                val h = strip.heightFrac * this.size.height
                val w = strip.widthFrac * this.size.width
                val xOff = strip.offsetSign * pixelOffset.dp.toPx()
                drawRect(
                    color = glitchColors[strip.colorIndex],
                    topLeft = Offset(xOff, y),
                    size = Size(w, h),
                )
            }
        }
    }
}

private data class GlitchStrip(
    val yFrac: Float,
    val heightFrac: Float,
    val widthFrac: Float,
    val colorIndex: Int,
    val offsetSign: Float,
)
