package com.v1per.synthx.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import com.v1per.synthx.ui.theme.Alpha
import com.v1per.synthx.ui.theme.Background
import com.v1per.synthx.ui.theme.TextPrimary

/**
 * The colors everything readable in the player sheet is drawn in: [Light] over a dark background,
 * [Dark] over a bright one. Only these two instances exist. [content] is the one color text, icons and
 * controls use; [onContent] is the other one, for an icon drawn on a [content]-filled shape.
 */
@Immutable
internal class PlayerContentColors private constructor(val content: Color, val onContent: Color) {
    /** A disabled control. */
    val disabled: Color = content.copy(alpha = Alpha.Disabled)

    companion object {
        val Light = PlayerContentColors(content = TextPrimary, onContent = Background)
        val Dark = PlayerContentColors(content = Background, onContent = TextPrimary)
    }
}

/**
 * The player sheet's content colors, provided once around the sheet by
 * [ProvidePlayerContentColors]. Null everywhere else, so components shared with other screens
 * can keep their own colors there. Not Material's LocalContentColor.
 */
internal val LocalPlayerContentColors = compositionLocalOf<PlayerContentColors?> { null }

/** [PlayerContentColors.Dark] when the locked adaptive-text rule says the background is bright. */
internal fun playerContentColorsFor(brightness: Float?): PlayerContentColors {
    val dark = useDarkPlayerTitle(
        enabled = PlayerAltStageStyle.AdaptiveTextEnabled,
        brightness = brightness,
        threshold = PlayerAltStageStyle.AdaptiveTextThreshold,
    )
    return if (dark) PlayerContentColors.Dark else PlayerContentColors.Light
}

/**
 * Provides the content colors [transition] displays to [content]. They change once per flip, halfway
 * through a fade, so readers recompose once rather than on every frame of it.
 */
@Composable
internal fun ProvidePlayerContentColors(transition: PlayerArtTransition<*>, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalPlayerContentColors provides transition.displayedColors, content = content)
}
