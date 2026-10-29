package com.v1per.synthx.ui.player

import androidx.compose.runtime.mutableStateOf
import java.util.Locale

internal const val GrainStrengthMax = 0.40f
internal const val GrainSizeMinDp = 0.5f
internal const val GrainSizeMaxDp = 4.0f
internal const val GrainPatternMin = 1
internal const val GrainPatternMax = 5

/** [settings] with every value limited to its panel range; a NaN falls back to the default. */
internal fun clampPlayerGrain(settings: PlayerGrainSettings): PlayerGrainSettings {
    val defaults = PlayerGrainSettings()
    fun Float.limited(default: Float, min: Float, max: Float) = if (isNaN()) default else coerceIn(min, max)
    return settings.copy(
        strength = settings.strength.limited(defaults.strength, 0f, GrainStrengthMax),
        sizeDp = settings.sizeDp.limited(defaults.sizeDp, GrainSizeMinDp, GrainSizeMaxDp),
        contrast = settings.contrast.limited(defaults.contrast, 0f, 1f),
        pattern = settings.pattern.coerceIn(GrainPatternMin, GrainPatternMax),
    )
}

/** One line with every current value, for reading back from the tuning panel. */
internal fun playerGrainReadout(settings: PlayerGrainSettings): String = String.format(
    Locale.US,
    "grain on=%b strength=%.2f size=%.1fdp contrast=%.2f blend=%s mode=%s pattern=%d",
    settings.enabled,
    settings.strength,
    settings.sizeDp,
    settings.contrast,
    settings.blend.name,
    if (settings.colored) "colour" else "grey",
    settings.pattern,
)

// Process-lifetime state; nothing is persisted.
internal object PlayerGrainDebugStateHolder {
    val state = mutableStateOf(PlayerGrainSettings())

    fun update(change: (PlayerGrainSettings) -> PlayerGrainSettings) {
        state.value = clampPlayerGrain(change(state.value))
    }

    fun reset() {
        state.value = PlayerGrainSettings()
    }
}
