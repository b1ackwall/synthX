package com.v1per.synthx.playback

import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.google.common.util.concurrent.ListenableFuture
import com.v1per.synthx.data.PlaybackCacheRevision
import com.v1per.synthx.playback.ExoPlayerEngine.Companion.TAG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ExoPlayerEngine's connection to the playback service's media session.

internal fun ExoPlayerEngine.buildControllerFuture(): ListenableFuture<MediaController> {
    val sessionListener = object : MediaController.Listener {
        // Session-level disconnection (the session/service itself went away or revoked
        // this controller) — distinct from a Player.Listener callback, and previously
        // nothing installed a MediaController.Listener at all (P4-T3 / R3-F09).
        override fun onDisconnected(controller: MediaController) {
            Log.w(TAG, "MediaController disconnected mid-session")
            scheduleReconnect()
        }
    }
    return controllerFutureFactory?.invoke(sessionListener)
        ?: MediaController.Builder(appContext, sessionToken)
            .setListener(sessionListener)
            .buildAsync()
}

internal fun ExoPlayerEngine.scheduleReconnect() {
    // media3 also reports onDisconnected for our own release() — nothing to reconnect then.
    if (connection.isReleased) return
    if (reconnectJob?.isActive == true) return
    // Also flips the phase back to Connecting, which is what makes a request issued during a
    // mid-session reconnect wait for the new controller rather than install itself into the
    // dropped one (which media3 silently ignores every command on).
    val backoffMs = connection.onConnectionLost()
    if (backoffMs == null) {
        Log.w(TAG, "Giving up on reconnecting after ${ReconnectPolicy.MAX_ATTEMPTS} retries")
        return
    }
    reconnectJob = engineScope.launch {
        delay(backoffMs)
        startConnectionAttempt()
    }
}

internal fun ExoPlayerEngine.startConnectionAttempt() {
    // A fresh future every attempt — re-adding a listener to the same already-resolved
    // (failed, or now-stale) future would either fail again for the same reason or,
    // for a mid-session disconnect, do nothing at all (its listener already fired once).
    controllerFuture = buildControllerFuture()
    connectToSession()
}

/**
 * (Re)connects to [SynthXPlaybackService]'s session — the first connection at construction,
 * and any later reconnection [scheduleReconnect] triggers. P4-T3: previously this ran once,
 * inline in an `init` block, un-retryable by construction; splitting it out is what makes
 * retry possible at all.
 */
internal fun ExoPlayerEngine.connectToSession() {
    controllerFuture.addListener(
        {
            // A suspend hop is needed here (see adoptRunningTimeline) when reconnecting
            // to a still-running service, so this is a coroutine launch rather than the
            // plain synchronous body it used to be — engineScope's Main.immediate
            // dispatcher keeps `controller` assignment and the connection-phase update on
            // the main thread either way.
            engineScope.launch {
                try {
                    val ctrl = controllerFuture.get()
                    // A mid-session reconnect replaces a controller this listener is still
                    // attached to; detach it so only the live controller drives the engine.
                    controller?.takeIf { it !== ctrl }?.removeListener(listener)
                    controller = ctrl
                    ctrl.addListener(listener)
                    reconnectJob = null
                    // Adopt whatever repeat state the session already holds (a service
                    // that outlived the previous MediaController keeps its timeline).
                    // Shuffle has no media3-side state to adopt any more — restoreSavedQueue
                    // (or a fresh playQueue) is what seeds _shuffleEnabled now.
                    _repeatMode.value = ctrl.repeatMode.toRepeatMode()
                    // Seeded here rather than left to the next Player.Listener callback
                    // (P4-T3 / R3-F09): adding a listener does not replay the controller's
                    // current values, so without this an already-playing session (a
                    // reconnect, or adopting a still-running service) shows paused/idle
                    // until a later real transition happens to fire.
                    _isPlaying.value = ctrl.isPlaying
                    _isBuffering.value = ctrl.playbackState == Player.STATE_BUFFERING
                    // Captured before adoptRunningTimeline's suspending DB read below — see
                    // reconcileIfRevisionChanged's kdoc. This connect path is one of the
                    // three suspension points P4-T2 covers: a remap/deletion landing while
                    // this suspends is checked against a still-empty cache and discarded.
                    val revisionBeforeAdopt = PlaybackCacheRevision.current()
                    val connectionPath = if (ctrl.mediaItemCount > 0 && orderStore.isEmpty) {
                        // Reconnecting to a still-running service: the order store and
                        // songsBySlotId are otherwise only populated by
                        // playQueueInternal/restoreSavedQueueInternal, neither of which
                        // runs here — must happen before refreshQueue() below or the queue
                        // flows end up empty against a player that is actually playing.
                        adoptRunningTimeline(ctrl)
                        "adopting existing running timeline"
                    } else if (ctrl.mediaItemCount > 0) {
                        "using existing engine queue"
                    } else {
                        "starting fresh; persisted restore pending"
                    }
                    val currentSlotId = ctrl.currentMediaItem?.mediaId?.takeIf { it.isNotEmpty() }
                    refreshQueue()
                    publishCurrentSlot(currentSlotId)
                    refreshCurrentIndex()
                    refreshUpcomingQueue()
                    refreshNavigationState()
                    // The service's surviving timeline is whatever window this engine
                    // instance (or an activity-finish-while-playing predecessor, per P4-T3)
                    // last left loaded; reconcile it against the order just adopted.
                    maintainWindow(force = true)
                    prefetchArtwork()
                    Log.d(
                        TAG,
                        "Session connected: $connectionPath; song=${_currentSong.value?.id}, " +
                            "slot=${_currentSlotId.value}, queue=${orderStore.size}",
                    )
                    // Last, after the adopt/refresh above: a request waiting in
                    // awaitController must not interleave with the adoption. Skipped when the
                    // session already dropped this controller during adoptRunningTimeline's
                    // suspension — onDisconnected has scheduled the next attempt, and
                    // reporting Connected now would release waiters into a dead controller.
                    if (ctrl.isConnected) connection.onConnected()
                    reconcileIfRevisionChanged(revisionBeforeAdopt)
                } catch (e: Exception) {
                    Log.d(TAG, "Session connection failed", e)
                    // Keeps waiting requests waiting through the backoff, or fails them once
                    // the round is used up (see ControllerConnection.onConnectionLost).
                    scheduleReconnect()
                }
            }
        },
        ContextCompat.getMainExecutor(appContext),
    )
}

/**
 * The controller a queue load should use, waiting out a connection that is still being made
 * or retried (issue #175) — previously this answered "no controller" as soon as the first
 * attempt failed, silently dropping the request while a retry was seconds away. Returns null
 * only when the whole round of attempts fails (or the engine is released).
 *
 * A request that finds the engine already given up re-arms one fresh round rather than
 * failing instantly: the service may well be reachable again by now, and a user tap is the
 * natural moment to find out.
 */
internal suspend fun ExoPlayerEngine.awaitController(): MediaController? = withContext(Dispatchers.Main.immediate) {
    if (connection.rearmIfGaveUp()) {
        Log.i(TAG, "Playback requested after giving up; starting a fresh round of connection attempts")
        reconnectJob?.cancel()
        startConnectionAttempt()
    }
    if (connection.awaitSettled() == ConnectionPhase.Connected) controller else null
}

/** Surfaces a request refused for lack of a session connection; returns false for the caller. */
internal fun ExoPlayerEngine.reportUnavailable(request: String): Boolean {
    Log.w(TAG, "$request refused: not connected to the playback session (${connection.phase.value})")
    // Not after release (e.g. restore's releaseAllForRestore cutting a waiting load short):
    // the session isn't unreachable then, this engine is simply being torn down on purpose.
    if (!connection.isReleased) _errors.tryEmit(PlaybackError.ServiceUnavailable)
    return false
}
