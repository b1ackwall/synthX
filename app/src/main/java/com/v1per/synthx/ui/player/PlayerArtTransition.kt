package com.v1per.synthx.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.v1per.synthx.ui.theme.Motion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The transition's progress runs linearly in time; [playerArtFadeAlpha] applies the Standard
 * easing when the incoming picture is drawn. So progress 0.5, the content color flip, is the
 * fade's time midpoint (150 ms), where the eased alpha is about 0.78.
 */
internal val PlayerArtFadeSpec: TweenSpec<Float> = tween(Motion.DurationNormal, easing = LinearEasing)

/** The progress at which the displayed content colors switch to the target's. */
internal const val PlayerArtFlipProgress = 0.5f

/** The alpha the incoming picture is drawn at for [progress]; exactly 0 and 1 at the ends. */
internal fun playerArtFadeAlpha(progress: Float): Float = Motion.EasingStandard.transform(progress.coerceIn(0f, 1f))

/**
 * The player background's picture and content colors across song changes: one [progress] drives a
 * fade from [from] to [to], and [displayedColors] switches once, when progress reaches
 * [PlayerArtFlipProgress], so its readers recompose once per flip rather than every frame.
 *
 * A picture that lands while a fade runs restarts the fade: [freeze] blends what is on screen into
 * one picture, which becomes the new [from]. A picture [sameImage] as the current target changes
 * nothing. [submit] with `animate = false`, a picture appearing or disappearing, and [finish] all
 * set the target state at once.
 *
 * Every call and state change happens on [scope]'s thread, whose context needs a frame clock.
 */
@Stable
internal class PlayerArtTransition<T : Any>(
    initialImage: T?,
    initialColors: PlayerContentColors,
    private val scope: CoroutineScope,
    private val freeze: suspend (from: T, to: T, alpha: Float) -> T,
    private val sameImage: (T, T) -> Boolean,
) {
    /** The outgoing picture while a fade runs, drawn under [to]; null at rest. */
    var from: T? by mutableStateOf(null)
        private set

    /** The picture shown at rest, and the incoming one during a fade; null for no art. */
    var to: T? by mutableStateOf(initialImage)
        private set

    /** 0 to 1 across a fade; meaningful only while [from] is set. */
    val progress: Animatable<Float, AnimationVector1D> = Animatable(1f)

    var displayedColors: PlayerContentColors by mutableStateOf(initialColors)
        private set

    /** The alpha [to] is drawn at over [from]. */
    val alpha: Float
        get() = if (from == null) 1f else playerArtFadeAlpha(progress.value)

    private var targetImage: T? = initialImage
    private var targetColors: PlayerContentColors = initialColors
    private var fadeJob: Job? = null

    /** Heads for [image] with [colors]; fades only when [animate] and both pictures exist. */
    fun submit(image: T?, colors: PlayerContentColors, animate: Boolean) {
        val heading = targetImage
        // The same picture as the target: a running fade keeps going; at rest nothing changes.
        if (image != null && heading != null && sameImage(image, heading)) return
        targetImage = image
        targetColors = colors
        if (!animate || image == null || to == null) {
            snapToTarget()
            return
        }
        fadeJob?.cancel()
        fadeJob = scope.launch { fadeTo(image) }
    }

    /** Ends a running or pending fade at its target state. */
    fun finish() {
        if (fadeJob?.isActive == true || from != null) snapToTarget()
    }

    private fun snapToTarget() {
        fadeJob?.cancel()
        fadeJob = null
        from = null
        to = targetImage
        displayedColors = targetColors
    }

    private suspend fun fadeTo(image: T) {
        progress.stop()
        // A snap that cleared the art also cancelled this fade.
        val shown = to ?: return
        val outgoing = from
        val start = if (outgoing == null) shown else freeze(outgoing, shown, alpha)
        // No other mutator is left, so this returns at once and the reset lands in the same frame as the new pair.
        progress.snapTo(0f)
        from = start
        to = image
        progress.animateTo(1f, PlayerArtFadeSpec) {
            if (value >= PlayerArtFlipProgress && displayedColors !== targetColors) displayedColors = targetColors
        }
        from = null
        displayedColors = targetColors
    }
}

/**
 * [from] and [to] (opaque ARGB pixels of the same size) blended channel by channel, [to] at
 * [alpha] over [from], as drawing [to] over [from] at that alpha does.
 */
internal fun blendArgbPixels(from: IntArray, to: IntArray, alpha: Float): IntArray {
    require(from.size == to.size) { "Cannot blend ${from.size} pixels with ${to.size}" }
    val t = alpha.coerceIn(0f, 1f)
    return IntArray(from.size) { i ->
        val a = from[i]
        val b = to[i]
        (mixChannel(a ushr 24, b ushr 24, t) shl 24) or
            (mixChannel((a shr 16) and 0xFF, (b shr 16) and 0xFF, t) shl 16) or
            (mixChannel((a shr 8) and 0xFF, (b shr 8) and 0xFF, t) shl 8) or
            mixChannel(a and 0xFF, b and 0xFF, t)
    }
}

private fun mixChannel(from: Int, to: Int, t: Float): Int = (from + (to - from) * t).roundToInt()
