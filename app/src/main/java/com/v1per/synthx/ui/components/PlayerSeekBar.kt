package com.v1per.synthx.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import com.v1per.synthx.ui.theme.Accent
import com.v1per.synthx.ui.theme.Spacing
import com.v1per.synthx.ui.theme.SurfaceVariant
import com.v1per.synthx.ui.theme.TextSecondary
import java.util.Locale

/** Visual-only seek indicator + timestamps. No scrubbing — that's a future task. */
@Composable
fun PlayerSeekBar(
    currentPosition: Long,
    duration: Long,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg),
    ) {
        // Thin progress line
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp),
        ) {
            val fraction = if (duration > 0) {
                (currentPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
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
        Spacer(modifier = Modifier.height(Spacing.sm))
        // Timestamps
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatDuration(currentPosition),
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

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%d:%02d", minutes, seconds)
}
