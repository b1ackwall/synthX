package com.v1per.synthx.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.v1per.synthx.R
import com.v1per.synthx.ui.theme.Accent
import com.v1per.synthx.ui.theme.Background
import com.v1per.synthx.ui.theme.Hairline
import com.v1per.synthx.ui.theme.Spacing
import com.v1per.synthx.ui.theme.SynthXTheme
import com.v1per.synthx.ui.theme.TextPrimary
import com.v1per.synthx.ui.theme.TextTertiary

@Composable
fun SettingsScreen(
    topBar: @Composable () -> Unit,
    onLibraryManagementClick: () -> Unit,
    onDeveloperSettingsClick: () -> Unit,
) {
    // Settings owns no state of its own yet — the ViewModel is instantiated to keep the
    // one-ViewModel-per-screen wiring in place for the next setting that lands here.
    viewModel<SettingsViewModel>(factory = SettingsViewModel.Factory())

    Column(modifier = Modifier.fillMaxSize().background(Background)) {
        topBar()
        SettingsScreenContent(
            onLibraryManagementClick = onLibraryManagementClick,
            onDeveloperSettingsClick = onDeveloperSettingsClick,
        )
    }
}

@Composable
private fun SettingsScreenContent(
    onLibraryManagementClick: () -> Unit,
    onDeveloperSettingsClick: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        SettingsRow(
            title = stringResource(R.string.settings_library_management),
            onClick = onLibraryManagementClick,
        )
        SettingsRow(
            title = stringResource(R.string.settings_developer_settings),
            onClick = onDeveloperSettingsClick,
        )
    }
}

@Composable
internal fun SettingsRow(
    title: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }

    Column {
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
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = Spacing.md),
            )
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = TextTertiary,
            )
        }
        HorizontalDivider(
            thickness = 1.dp,
            color = Hairline,
        )
    }
}

@Preview(name = "Settings", showBackground = true)
@Composable
private fun SettingsScreenPreview() {
    SynthXTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Background,
        ) {
            SettingsScreenContent(
                onLibraryManagementClick = {},
                onDeveloperSettingsClick = {},
            )
        }
    }
}
