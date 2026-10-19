package com.v1per.synthx.ui.player

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.unit.IntSize
import android.graphics.RenderEffect as NativeEffect

/** Name of the shader uniform that receives the previous effect's output. */
private const val ContentUniform = "content"

/**
 * Rotates sample coordinates around center inside radius px by angle * ((radius - d) / radius)^2.
 * Inside the radius the rotated sample point is clamped to the layer bounds; outside it the
 * content sample at the same coordinate is returned unchanged.
 */
internal const val AltStageTwistShaderSource = """
uniform shader content;
uniform float2 center;
uniform float radius;
uniform float angle;
uniform float2 size;

half4 main(float2 coord) {
    float2 d = coord - center;
    float dist = length(d);
    if (dist >= radius) return content.eval(coord);
    float t = (radius - dist) / radius;
    float a = angle * t * t;
    float s = sin(a);
    float c = cos(a);
    float2 p = center + float2(d.x * c - d.y * s, d.x * s + d.y * c);
    return content.eval(clamp(p, float2(0.5), size - 0.5));
}
"""

/** True when the AGSL twist can run on [sdkInt]: RuntimeShader needs API 33. */
internal fun altStageTwistSupported(sdkInt: Int): Boolean = sdkInt >= Build.VERSION_CODES.TIRAMISU

@get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.TIRAMISU)
private val twistSupportedOnDevice: Boolean
    get() = altStageTwistSupported(Build.VERSION.SDK_INT)

/**
 * One effect for the whole layer: blur, then twist, then saturation, each fed the previous
 * output (see [altStageChain]). The blur uses [blurEdgeMode] and runs only for a positive
 * [blurDp]. The twist is skipped below API 33 or with [twistEnabled] false.
 */
internal fun altStageEffect(
    size: IntSize,
    density: Float,
    blurDp: Float = PlayerAltStageStyle.BlurDp,
    twistEnabled: Boolean = PlayerAltStageStyle.TwistEnabled,
    blurEdgeMode: AltStageBlurEdgeMode = PlayerAltStageStyle.BlurEdgeMode,
): RenderEffect? {
    if (size.width <= 0 || size.height <= 0) return null
    val blurPx = blurDp * density
    var effect: NativeEffect? = null
    for (step in altStageChain(blurPx, twistEnabled, twistSupportedOnDevice)) {
        effect = when (step) {
            AltStageStep.Blur -> blurEffect(blurPx, blurEdgeMode.toTileMode(), effect)
            AltStageStep.Twist -> twistEffect(size, effect)
            AltStageStep.Saturation -> saturationEffect(effect)
        }
    }
    return effect?.asComposeRenderEffect()
}

private fun blurEffect(blurPx: Float, tileMode: Shader.TileMode, input: NativeEffect?): NativeEffect =
    input?.let { NativeEffect.createBlurEffect(blurPx, blurPx, it, tileMode) }
        ?: NativeEffect.createBlurEffect(blurPx, blurPx, tileMode)

private fun saturationEffect(input: NativeEffect?): NativeEffect {
    val filter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(PlayerAltStageStyle.Saturation) })
    return input?.let { NativeEffect.createColorFilterEffect(filter, it) }
        ?: NativeEffect.createColorFilterEffect(filter)
}

private fun AltStageBlurEdgeMode.toTileMode(): Shader.TileMode = when (this) {
    AltStageBlurEdgeMode.Clamp -> Shader.TileMode.CLAMP
    AltStageBlurEdgeMode.Mirror -> Shader.TileMode.MIRROR
    AltStageBlurEdgeMode.Decal -> Shader.TileMode.DECAL
    AltStageBlurEdgeMode.Repeat -> Shader.TileMode.REPEAT
}

private fun twistEffect(size: IntSize, input: NativeEffect?): NativeEffect {
    // altStageChain adds the twist only on API 33 and up.
    if (!twistSupportedOnDevice) return requireNotNull(input)
    return twistEffectApi33(size, input)
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun twistEffectApi33(size: IntSize, input: NativeEffect?): NativeEffect {
    val width = size.width.toFloat()
    val shader = RuntimeShader(AltStageTwistShaderSource).apply {
        setFloatUniform(
            "center",
            PlayerAltStageStyle.TwistOffsetX * width,
            PlayerAltStageStyle.TwistOffsetY * size.height,
        )
        setFloatUniform("radius", (PlayerAltStageStyle.TwistRadius * width).coerceAtLeast(1f))
        setFloatUniform("angle", PlayerAltStageStyle.TwistAngle)
        setFloatUniform("size", width, size.height.toFloat())
    }
    val twist = NativeEffect.createRuntimeShaderEffect(shader, ContentUniform)
    // The shader effect takes no input effect, so the previous step is chained in as its source.
    return input?.let { NativeEffect.createChainEffect(twist, it) } ?: twist
}
