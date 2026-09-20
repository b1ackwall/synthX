package com.v1per.synthx.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
 *
 * [bottomBar] is for a form that needs its primary action anchored to the bottom of the screen
 * rather than the bottom of the scrollable content (Create Playlist's button); it sits below the
 * scroll region and never scrolls. [modifier] applies to the whole shell, e.g. `imePadding()` so
 * the keyboard lifts [bottomBar] instead of covering it. All four settings screens use the
 * defaults, for which the layout is unchanged.
 */
@Composable
fun SettingsDetailScaffold(
    topBar: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    bottomBar: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxSize().background(Background)) {
        topBar()
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            content = content,
        )
        bottomBar?.invoke()
    }
}
