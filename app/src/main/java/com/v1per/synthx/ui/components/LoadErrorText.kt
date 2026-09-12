package com.v1per.synthx.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.v1per.synthx.ui.theme.Error
import com.v1per.synthx.ui.theme.Spacing

/**
 * Shared body for a [com.v1per.synthx.ui.common.UiState.Error] branch: a centered message in
 * the app's error color. Callers are expected to place this inside a `Box` and align it
 * themselves (see GroupDetailScreen/IssuesScreen) — this composable only renders the text.
 */
@Composable
fun LoadErrorText(message: String, modifier: Modifier = Modifier) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodyLarge,
        color = Error,
        textAlign = TextAlign.Center,
        modifier = modifier.padding(horizontal = Spacing.HorizontalPadding),
    )
}
