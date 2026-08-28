package com.v1per.synthx.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.v1per.synthx.ui.theme.Background

/**
 * Shared shell for settings-area detail screens (Settings, Library Management,
 * Developer Settings, Backups and Restore): fixed [topBar] above a scrollable
 * content column, so content that grows past the screen height stays reachable
 * instead of being silently clipped.
 */
@Composable
fun SettingsDetailScaffold(
    topBar: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().background(Background)) {
        topBar()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            content = content,
        )
    }
}
