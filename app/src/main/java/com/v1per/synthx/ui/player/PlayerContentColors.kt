package com.v1per.synthx.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.v1per.synthx.playback.BackgroundBrightness
import com.v1per.synthx.ui.theme.Alpha
import com.v1per.synthx.ui.theme.Background
import com.v1per.synthx.ui.theme.TextPrimary

/**
 * The one color everything readable in the player sheet is drawn in: [Light] over a dark
 * background, [Dark] over a bright one. Only these two instances exist.
 */
@Immutable
internal class PlayerContentColors private constructor(val content: Color) {
    /** An on/off control in its "off" state (queue button, shuffle, repeat). */
    val off: Color = content.copy(alpha = Alpha.ToggleOff)

    /** A disabled control. */
    val disabled: Color = content.copy(alpha = Alpha.Disabled)

    companion object {
        val Light = PlayerContentColors(TextPrimary)
        val Dark = PlayerContentColors(Background)
    }
}

/**
 * The player sheet's content colors, provided once around the sheet by
 * [ProvidePlayerContentColors]. Null everywhere else, so components shared with other screens
 * can keep their own colors there. Not Material's LocalContentColor.
 */
internal val LocalPlayerContentColors = compositionLocalOf<PlayerContentColors?> { null }

/** [PlayerContentColors.Dark] when the locked adaptive-text rule says the background is bright. */
internal fun playerContentColorsFor(brightness: BackgroundBrightness?): PlayerContentColors {
    val dark = useDarkPlayerTitle(
        enabled = PlayerAltStageStyle.AdaptiveTextEnabled,
        brightness = brightness,
        metric = PlayerAltStageStyle.LockedAdaptiveTextMetric,
        statistic = PlayerAltStageStyle.LockedAdaptiveTextStatistic,
        threshold = PlayerAltStageStyle.AdaptiveTextThreshold,
    )
    return if (dark) PlayerContentColors.Dark else PlayerContentColors.Light
}

/** Decides the player's content colors from [brightness] and provides them to [content]. */
@Composable
internal fun ProvidePlayerContentColors(
    brightness: BackgroundBrightness?,
    content: @Composable () -> Unit,
) {
    val colors = remember(brightness) { playerContentColorsFor(brightness) }
    CompositionLocalProvider(LocalPlayerContentColors provides colors, content = content)
}
