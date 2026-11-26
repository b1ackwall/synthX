package com.v1per.synthx.ui.player

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.v1per.synthx.playback.PlayerBackgroundArt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The [PlayerArtTransition] for the player background's decoded [art], starting at the art
 * current when it is first remembered, so a recreated sheet does not fade.
 *
 * It snaps instead of fading under the reduced-motion policy (power saver or an animator scale of
 * 0), while the activity is not started, and when the activity stops mid-fade.
 */
@Composable
internal fun rememberPlayerArtTransition(art: PlayerBackgroundArt<Bitmap>?): PlayerArtTransition<ImageBitmap> {
    val scope = rememberCoroutineScope()
    val transition = remember {
        PlayerArtTransition(
            initialImage = art?.image?.asImageBitmap(),
            initialColors = playerContentColorsFor(art?.brightness),
            scope = scope,
            freeze = ::freezePlayerArt,
            sameImage = ::isSamePlayerArt,
        )
    }
    val reducedMotion by rememberUpdatedState(rememberSystemReducedMotionActive())
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(transition, art) {
        val animate = !reducedMotion && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        transition.submit(art?.image?.asImageBitmap(), playerContentColorsFor(art?.brightness), animate)
    }
    LaunchedEffect(transition, reducedMotion) {
        if (reducedMotion) transition.finish()
    }
    DisposableEffect(transition, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) transition.finish()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return transition
}

/** The on-screen mix of [from] and [to] as one picture, made off the main thread. */
private suspend fun freezePlayerArt(from: ImageBitmap, to: ImageBitmap, alpha: Float): ImageBitmap =
    withContext(Dispatchers.Default) {
        blendPlayerArt(from.asAndroidBitmap(), to.asAndroidBitmap(), alpha).asImageBitmap()
    }

/** Pixel equality, since every song keeps its own art files even when an album shares one cover. */
private fun isSamePlayerArt(a: ImageBitmap, b: ImageBitmap): Boolean {
    val first = a.asAndroidBitmap()
    val second = b.asAndroidBitmap()
    return first === second || first.sameAs(second)
}

/**
 * [to] at [alpha] over [from] as a new bitmap. The decoded art is always one square size; for two
 * sizes it keeps whichever picture is mostly on screen.
 */
internal fun blendPlayerArt(from: Bitmap, to: Bitmap, alpha: Float): Bitmap {
    if (from.width != to.width || from.height != to.height) return if (alpha < 0.5f) from else to
    val width = from.width
    val height = from.height
    val fromPixels = IntArray(width * height)
    val toPixels = IntArray(width * height)
    from.getPixels(fromPixels, 0, width, 0, 0, width, height)
    to.getPixels(toPixels, 0, width, 0, 0, width, height)
    return Bitmap.createBitmap(blendArgbPixels(fromPixels, toPixels, alpha), width, height, Bitmap.Config.ARGB_8888)
}
