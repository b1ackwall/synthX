package com.v1per.synthx.ui.screens.librarymanagement

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.v1per.synthx.R
import com.v1per.synthx.ui.theme.Error
import com.v1per.synthx.ui.theme.Hairline
import com.v1per.synthx.ui.theme.Spacing
import com.v1per.synthx.ui.theme.SurfaceVariant
import com.v1per.synthx.ui.theme.TextPrimary
import com.v1per.synthx.ui.theme.TextTertiary

/**
 * Safety toggle gating the delete-song action in Issues and the song actions sheet.
 * Off by default and never persisted — see DeleteSongsGate. The switch itself stays
 * off the neon accent, which the design system reserves for five specific contexts
 * this isn't one of.
 */
@Composable
internal fun DeleteSongsRow(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.HorizontalPadding, vertical = Spacing.md),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.settings_delete_songs),
            style = MaterialTheme.typography.bodyLarge,
            color = TextPrimary,
            modifier = Modifier
                .weight(1f)
                .padding(end = Spacing.md),
        )
        Switch(
            checked = enabled,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Error,
                checkedTrackColor = Error.copy(alpha = 0.3f),
                checkedBorderColor = Error,
                uncheckedThumbColor = TextTertiary,
                uncheckedTrackColor = SurfaceVariant,
                uncheckedBorderColor = Hairline,
            ),
        )
    }
}
