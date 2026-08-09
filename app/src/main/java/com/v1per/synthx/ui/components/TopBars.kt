package com.v1per.synthx.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.v1per.synthx.R
import com.v1per.synthx.ui.theme.Background
import com.v1per.synthx.ui.theme.TextPrimary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SynthXTopBar(
    onSettingsClick: () -> Unit,
) {
    TopAppBar(
        title = {
            Text(
                text = rememberWordmark(),
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontWeight = FontWeight.Bold,
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                ),
            )
        },
        actions = {
            IconButton(
                onClick = onSettingsClick,
                modifier = Modifier.padding(end = 4.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = stringResource(R.string.screen_settings),
                    tint = TextPrimary,
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Background,
            scrolledContainerColor = Background,
        ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DetailTopBar(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.cd_back),
                    tint = TextPrimary,
                )
            }
        },
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = TextPrimary,
            )
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Background,
            scrolledContainerColor = Background,
        ),
    )
}
