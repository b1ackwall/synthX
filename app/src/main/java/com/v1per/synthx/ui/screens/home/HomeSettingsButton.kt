package com.v1per.synthx.ui.screens.home

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.v1per.synthx.R
import com.v1per.synthx.ui.theme.Spacing
import com.v1per.synthx.ui.theme.TextSecondary

internal const val HOME_SETTINGS_BUTTON_TAG = "home_settings_button"

/** The gear at Home's top end; the only way into the Settings page. */
@Composable
internal fun BoxScope.HomeSettingsButton(onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(Spacing.sm)
            .testTag(HOME_SETTINGS_BUTTON_TAG),
    ) {
        Icon(
            imageVector = Icons.Filled.Settings,
            contentDescription = stringResource(R.string.screen_settings),
            tint = TextSecondary,
        )
    }
}
