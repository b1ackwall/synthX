package com.v1per.synthx.ui.screens.librarymanagement

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.v1per.synthx.ui.theme.Spacing
import com.v1per.synthx.ui.theme.TextSecondary

/**
 * Groups Library Management's rows under a labeled section. Styled after the label used
 * atop [com.v1per.synthx.ui.screens.songs.SortBottomSheet]'s options — the closest existing
 * precedent in the app for a plain text section label — rather than introducing a new type
 * scale/color pairing.
 */
@Composable
internal fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = TextSecondary,
        modifier = Modifier.padding(
            horizontal = Spacing.HorizontalPadding,
            vertical = Spacing.sm,
        ),
    )
}
