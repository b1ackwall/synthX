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
import androidx.compose.runtime.LaunchedEffect
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
import kotlin.math.abs

/** Height of the invisible touch-capture area around the 4dp visual line. */
private val TouchTargetHeight = 24.dp
private val TrackHeight = 4.dp

/**
 * How close a polled `currentPosition` must land to the optimistic seek target before
 * control releases back to it — wide enough to absorb normal playback drift between the
 * seek landing and the next 500ms poll tick, in either direction.
 */
private const val SeekCatchUpToleranceMs = 750L

/**
 * Pinned rather than left to the text's own line height, so this composable's total height is
 * a constant 48dp (24 touch target + 8 spacer + 16 labels) independent of the system font
 * scale. PlayerExpandedContent's ControlsOverlayHeight budgets exactly that 48dp; if this row
 * grew with font scale it could push the overlay's fixed children past its fixed height and
 * collapse the flexible gap inside it to zero.
 */
private val TimestampRowHeight = 16.dp

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
    // Held from drag release until the polled currentPosition catches up to it, so the
    // bar doesn't visibly snap back to the stale pre-seek position for up to one 500ms
    // tick while the real position is still in flight.
    var seekTargetMs by remember { mutableStateOf<Long?>(null) }
    val displayedPosition = dragPositionMs ?: seekTargetMs ?: currentPosition

    LaunchedEffect(currentPosition) {
        val target = seekTargetMs ?: return@LaunchedEffect
        if (abs(currentPosition - target) <= SeekCatchUpToleranceMs) {
            seekTargetMs = null
        }
    }

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
                            dragPositionMs?.let { target ->
                                seekTargetMs = target
                                onSeek(target)
                            }
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
            modifier = Modifier
                .fillMaxWidth()
                .height(TimestampRowHeight),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
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
