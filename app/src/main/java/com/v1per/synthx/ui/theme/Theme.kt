package com.v1per.synthx.ui.theme

import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

private val DarkColorScheme = darkColorScheme(
    background = Background,
    surface = Surface,
    surfaceVariant = SurfaceVariant,
    primary = Accent,
    onPrimary = OnAccent,
    secondary = Accent,
    onSecondary = OnAccent,
    tertiary = TextSecondary,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    onSurfaceVariant = TextSecondary,
    outline = Hairline,
    error = Error,
    onError = Background,
)

// Text selection (cursor handles + highlight) defaults to the `primary` role, which would leak
// Accent into every text field — outside the five approved accent contexts. Overridden once here
// so no individual TextField has to remember to do it.
private val SynthXTextSelectionColors = TextSelectionColors(
    handleColor = TextPrimary,
    backgroundColor = TextPrimary.copy(alpha = 0.24f),
)

// TODO: light theme — not in scope, synthX is dark-only by design

@Composable
fun SynthXTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = SynthXTypography,
        shapes = SynthXShapes,
    ) {
        CompositionLocalProvider(
            LocalTextSelectionColors provides SynthXTextSelectionColors,
            content = content,
        )
    }
}
