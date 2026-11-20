package com.v1per.synthx.playback

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

// ExoPlayerEngine's next/previous bodies, a controller's shuffle command, the current-slot publisher
// and the repeat-mode mappings.

/**
 * Resolved against the order store rather than delegating to `MediaController.seekToNext`.
 * The loaded window ends well before the queue does, so media3's own traversal cannot
 * answer "what plays next" for anything but the next few dozen songs; [seekToLiveIndex]
 * seeks within the window when it can and rebuilds around the target when it cannot.
 */
internal fun ExoPlayerEngine.nextInternal() {
    val target = nextLiveIndex() ?: return
    seekToLiveIndex(target, play = false)
}

/**
 * Hand-rolled rather than `MediaController.seekToPrevious()` for two reasons, both
 * consequences of the timeline being a window: media3 would wrap off the *window's* first
 * item under Repeat All (jumping forward ~50 songs, not back one), and "is there a previous
 * item" has to be asked of the order store to know whether the real queue start has been
 * reached. Keeps media3's own convention of restarting the current item when more than
 * [MediaController.getMaxSeekToPreviousPosition] into it.
 *
 * At the window's rear edge this restarts the current item instead of walking further back
 * — the accepted, deliberate limit of keeping only [WINDOW_BACK] songs of history loaded.
 * The decision itself is [previousAction], shared with system transport via
 * [systemNavigation].
 */
internal fun ExoPlayerEngine.previousInternal() {
    val ctrl = controller ?: return
    val action = previousAction(
        positionMs = ctrl.currentPosition,
        restartThresholdMs = ctrl.maxSeekToPreviousPosition,
        loadedIndex = ctrl.currentMediaItemIndex,
        repeatMode = _repeatMode.value,
        currentOrderIndex = orderStore.indexOfSlot(_currentSlotId.value),
        orderSize = orderStore.size,
    )
    when (action) {
        // Same idle/ended recovery seekToLiveIndex applies — the two direct-seek branches
        // seek the controller without going through it, so they need their own call.
        PreviousAction.RestartCurrent -> {
            applyIdleRecovery(ctrl)
            ctrl.seekTo(/* positionMs = */ 0)
        }

        is PreviousAction.SeekLoaded -> {
            applyIdleRecovery(ctrl)
            ctrl.seekTo(action.loadedIndex, C.TIME_UNSET)
        }

        is PreviousAction.SeekOrder -> seekToLiveIndex(action.orderIndex, play = false)
    }
}

/**
 * A controller's shuffle command, decided by [externalShuffleAction] against the app's own flag and
 * reorder flag. An applied command is the in-app toggle ([setShuffleEnabledInternal]), started
 * undispatched: on the main thread it has no suspension point, so it has finished when this returns
 * and the caller publishes the result.
 */
internal fun ExoPlayerEngine.setShuffleFromSystemInternal(enabled: Boolean) {
    when (externalShuffleAction(enabled, _shuffleEnabled.value, orderStore.shuffleReordered)) {
        ExternalShuffleAction.APPLY ->
            engineScope.launch(start = CoroutineStart.UNDISPATCHED) { setShuffleEnabledInternal(enabled) }

        ExternalShuffleAction.REFUSE ->
            Log.i(ExoPlayerEngine.TAG, "Shuffle off from a controller refused: the shuffled queue was reordered")

        ExternalShuffleAction.NONE -> Unit
    }
}

internal fun Int.toRepeatMode(): RepeatMode = when (this) {
    Player.REPEAT_MODE_ONE -> RepeatMode.One
    Player.REPEAT_MODE_ALL -> RepeatMode.All
    else -> RepeatMode.Off
}

internal fun RepeatMode.toPlayerRepeatMode(): Int = when (this) {
    RepeatMode.Off -> Player.REPEAT_MODE_OFF
    RepeatMode.All -> Player.REPEAT_MODE_ALL
    RepeatMode.One -> Player.REPEAT_MODE_ONE
}

/** Publishes every representation of the current slot from the engine-owned song cache. */
internal fun ExoPlayerEngine.publishCurrentSlot(slotId: String?) {
    val song = slotId?.let(songsBySlotId::get)
    _currentSlotId.value = slotId
    _currentSongId.value = song?.id
    _currentSong.value = song
}
