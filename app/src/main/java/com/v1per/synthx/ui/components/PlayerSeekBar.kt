package com.v1per.synthx.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.v1per.synthx.R
import com.v1per.synthx.ui.player.LocalPlayerContentColors
import com.v1per.synthx.ui.player.PlayerContentColors
import com.v1per.synthx.ui.theme.Spacing
import java.util.Locale
import kotlin.math.abs

/**
 * Height of the invisible touch-capture area around the visual line. The line itself
 * stays drawn at [TrackHeight], centered inside this box.
 */
private val TouchTargetHeight = 16.dp
private val TrackHeight = 6.dp

/**
 * How close a polled `currentPosition` must land to the optimistic seek target before
 * control releases back to it — wide enough to absorb normal playback drift between the
 * seek landing and the next 500ms poll tick, in either direction.
 */
private const val SeekCatchUpToleranceMs = 750L

/**
 * Pinned rather than left to the text's own line height, so this composable's total height
 * stays independent of the system font scale. PlayerSheetSkeleton's ControlsOverlayHeight
 * budgets around that fixed total via its `weight(1f)` remainder.
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
    unplayedColor: Color,
    playedColor: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var dragPositionMs by remember { mutableStateOf<Long?>(null) }
    // Held from drag release until the polled currentPosition catches up to it, so the
    // bar doesn't visibly snap back to the stale pre-seek position for up to one 500ms
    // tick while the real position is still in flight.
    var seekTargetMs by remember { mutableStateOf<Long?>(null) }
    val displayedPosition = dragPositionMs ?: seekTargetMs ?: currentPosition
    val safeDuration = duration.coerceAtLeast(0L)
    val seekBarLabel = stringResource(R.string.cd_seek_bar)
    val contentColors = LocalPlayerContentColors.current ?: PlayerContentColors.Light

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
        // target; the line stays drawn at TrackHeight, centered within it.
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .height(TouchTargetHeight)
                .pointerInput(duration, enabled) {
                    if (duration <= 0 || !enabled) return@pointerInput
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
                }
                // No native Slider to fall back on — this is a hand-rolled drag control, so
                // TalkBack gets nothing for free unless we declare it here. progressBarRangeInfo
                // + stateDescription make position discoverable/announced; setProgress is the
                // same "adjustable" mechanism a real Slider/SeekBar exposes (TalkBack's swipe
                // up/down-to-adjust gesture), chosen over a pair of custom "seek forward/back"
                // actions since it's the standard mechanism for exactly this control shape and
                // avoids offering two redundant ways to do the same thing.
                .semantics(mergeDescendants = true) {
                    contentDescription = seekBarLabel
                    stateDescription = "${formatDuration(displayedPosition)} of ${formatDuration(duration)}"
                    progressBarRangeInfo = ProgressBarRangeInfo(
                        current = displayedPosition.toFloat(),
                        range = 0f..safeDuration.toFloat().coerceAtLeast(1f),
                    )
                    if (safeDuration > 0) {
                        setProgress { targetValue ->
                            onSeek(targetValue.toLong().coerceIn(0L, safeDuration))
                            true
                        }
                    }
                },
        ) {
            // Thin progress line, centered within the touch target box.
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
                    color = unplayedColor,
                    start = Offset(0f, size.height / 2),
                    end = Offset(size.width, size.height / 2),
                    strokeWidth = size.height,
                    cap = StrokeCap.Round,
                )
                // Played track
                if (fraction > 0f) {
                    drawLine(
                        color = playedColor,
                        start = Offset(0f, size.height / 2),
                        end = Offset(size.width * fraction, size.height / 2),
                        strokeWidth = size.height,
                        cap = StrokeCap.Round,
                    )
                }
            }
        }
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
                color = contentColors.content,
            )
            Text(
                text = formatDuration(duration),
                style = MaterialTheme.typography.labelSmall,
                color = contentColors.content,
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
