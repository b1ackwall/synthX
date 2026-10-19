package com.v1per.synthx.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color

/** Production rendering policy for Home's ambient background. Debug controls do not feed this. */
internal object ProductionAmbientBackgroundStyle {
    const val BlurRadiusDp = 150f
    const val ScrimSaturation = 0.3f
    const val ScrimLightness = 0.05f
    const val ScrimAlpha = 0.5f
    val ScrimBlendMode = BlendMode.SrcOver
}

internal fun productionAmbientScrimColor(swatches: List<ExtractedSwatchHsl>?): Color? =
    swatches?.firstOrNull()?.let { first ->
        Color.hsl(
            hue = first.hue,
            saturation = ProductionAmbientBackgroundStyle.ScrimSaturation,
            lightness = ProductionAmbientBackgroundStyle.ScrimLightness,
        ).copy(alpha = ProductionAmbientBackgroundStyle.ScrimAlpha)
    }

internal fun Modifier.productionAmbientScrim(scrimColor: Color?): Modifier = if (scrimColor == null) {
    this
} else {
    drawWithContent {
        drawContent()
        drawRect(
            color = scrimColor,
            blendMode = ProductionAmbientBackgroundStyle.ScrimBlendMode,
        )
    }
}
