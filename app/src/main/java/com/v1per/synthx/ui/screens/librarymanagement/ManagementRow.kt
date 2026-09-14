package com.v1per.synthx.ui.screens.librarymanagement

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.v1per.synthx.ui.theme.Accent
import com.v1per.synthx.ui.theme.Spacing
import com.v1per.synthx.ui.theme.TextPrimary
import com.v1per.synthx.ui.theme.TextSecondary
import com.v1per.synthx.ui.theme.TextTertiary

/**
 * Shared shape for management-settings menu rows: title, optional secondary line, optional
 * trailing text badge, trailing chevron. Unifies what were four near-identical composables
 * (the backup TwoLineRow, MusicFolderSection's MusicFolderRow, ScanSection's RescanRow,
 * IssuesRow) — as a side effect this also fixes those rows' ripple, which previously fell
 * back to the platform default instead of the design system's [ripple] with [Accent].
 */
@Composable
internal fun ManagementRow(
    title: String,
    subtitle: String? = null,
    trailingText: String? = null,
    enabled: Boolean = true,
    subtitleMaxLines: Int = 1,
    subtitleColor: Color? = null,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = ripple(color = Accent),
                onClick = onClick,
            )
            .padding(horizontal = Spacing.HorizontalPadding, vertical = Spacing.md),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = Spacing.md),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary,
            )
            if (subtitle != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = subtitleColor ?: TextSecondary,
                    maxLines = subtitleMaxLines,
                    // Ellipsis only makes sense when a real line cap can be hit. Int.MAX_VALUE
                    // is the sentinel for "wrap without limit" (see RescanRow) — a message of
                    // unbounded length must never truncate, so there is nothing to clip either.
                    overflow = if (subtitleMaxLines == Int.MAX_VALUE) {
                        TextOverflow.Clip
                    } else {
                        TextOverflow.Ellipsis
                    },
                )
            }
        }
        if (trailingText != null) {
            Text(
                text = trailingText,
                style = MaterialTheme.typography.bodyLarge,
                color = TextSecondary,
                modifier = Modifier.padding(end = Spacing.sm),
            )
        }
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = TextTertiary,
        )
    }
}
