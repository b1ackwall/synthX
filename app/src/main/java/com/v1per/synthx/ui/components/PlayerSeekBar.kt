package com.v1per.synthx.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.v1per.synthx.ui.theme.Accent
import com.v1per.synthx.ui.theme.Spacing
import com.v1per.synthx.ui.theme.SurfaceVariant
import com.v1per.synthx.ui.theme.TextSecondary
import java.util.Locale

/** Height of the invisible touch-capture area around the 4dp visual line. */
private val TouchTargetHeight = 24.dp
private val TrackHeight = 4.dp

/**
 * Drag-only scrubbing: the thumb/labels follow the finger live via local [dragPositionMs]
 * state, but [onSeek] only fires on release — there is no live audio preview during drag.
 */
@Composable
fun PlayerSeekBar(
    currentPosition: Long,
    duration: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var dragPositionMs by remember { mutableStateOf<Long?>(null) }
    val displayedPosition = dragPositionMs ?: currentPosition

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg),
    ) {
        // Invisible touch-capture area, taller than the visual line for a reliable hit
        // target; the 4dp line stays drawn at its normal size, centered within it.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(TouchTargetHeight)
                .pointerInput(duration) {
                    if (duration <= 0) return@pointerInput
                    detectDragGestures(
                        onDragStart = { offset ->
                            dragPositionMs = positionForX(offset.x, size.width, duration)
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            dragPositionMs = positionForX(change.position.x, size.width, duration)
                        },
                        onDragEnd = {
                            dragPositionMs?.let(onSeek)
                            dragPositionMs = null
                        },
                        onDragCancel = {
                            dragPositionMs = null
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            // Thin progress line
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(TrackHeight),
            ) {
                val fraction = if (duration > 0) {
                    (displayedPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
                } else {
                    0f
                }
                // Unplayed track
                drawLine(
                    color = SurfaceVariant,
                    start = Offset(0f, size.height / 2),
                    end = Offset(size.width, size.height / 2),
                    strokeWidth = size.height,
                )
                // Played track
                if (fraction > 0f) {
                    drawLine(
                        color = Accent,
                        start = Offset(0f, size.height / 2),
                        end = Offset(size.width * fraction, size.height / 2),
                        strokeWidth = size.height,
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(Spacing.sm))
        // Timestamps
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatDuration(displayedPosition),
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
            )
            Text(
                text = formatDuration(duration),
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
            )
        }
    }
}

private fun positionForX(x: Float, widthPx: Int, duration: Long): Long {
    val ratio = (x / widthPx.toFloat()).coerceIn(0f, 1f)
    return (ratio * duration).toLong().coerceIn(0L, duration)
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%d:%02d", minutes, seconds)
}
