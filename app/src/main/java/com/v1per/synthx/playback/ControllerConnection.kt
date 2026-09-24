package com.v1per.synthx.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/** Where [ExoPlayerEngine]'s session connection stands — see [ControllerConnection]. */
internal enum class ConnectionPhase {
    /** An attempt is in flight or a retry is waiting out its backoff. Requests wait. */
    Connecting,

    /** A controller is connected to the session. Requests proceed. */
    Connected,

    /** Every attempt of the current round failed, or the engine was released. Requests fail. */
    GaveUp,
}

/**
 * The connection state machine behind [ExoPlayerEngine]'s session (re)connection (issue #175).
 * Split out, like [ReconnectPolicy], so the rules themselves are unit-testable without a real
 * `MediaController`; the engine keeps only the future/coroutine plumbing around it.
 *
 * Replaces the one-shot `controllerReady` deferred, which resolved on the FIRST attempt only: a
 * request arriving after a failed first attempt saw "resolved, no controller" and was silently
 * dropped even though a retry was about to succeed, and a request arriving after a mid-session
 * disconnect saw "resolved, stale controller" and was installed into a controller that ignores
 * every command. [awaitSettled] instead waits out the whole retry round.
 *
 * Main-thread only, like the rest of the engine's connection state.
 */
internal class ControllerConnection {
    private val _phase = MutableStateFlow(ConnectionPhase.Connecting)
    val phase: StateFlow<ConnectionPhase> = _phase.asStateFlow()

    val isConnected: Boolean
        get() = _phase.value == ConnectionPhase.Connected

    /** Terminal: set by [release], after which nothing moves the phase off [ConnectionPhase.GaveUp]. */
    var isReleased = false
        private set

    // Retries already scheduled in the current round, i.e. since the last successful connection
    // or re-arm — what ReconnectPolicy's backoff is indexed by.
    private var retriesScheduled = 0

    /** An attempt resolved with a live controller. Ends the round and resets its backoff. */
    fun onConnected() {
        if (isReleased) return
        retriesScheduled = 0
        _phase.value = ConnectionPhase.Connected
    }

    /**
     * An attempt failed, or a connected controller was dropped by the session. Returns the backoff
     * before the next retry — the phase is [ConnectionPhase.Connecting] again, so requests wait for
     * it — or null once [ReconnectPolicy.MAX_ATTEMPTS] retries have been used, with the phase now
     * [ConnectionPhase.GaveUp] so waiting requests fail instead of hanging.
     */
    fun onConnectionLost(): Long? {
        if (isReleased) return null
        val delayMs = ReconnectPolicy.nextDelayMsOrNull(retriesScheduled)
        if (delayMs == null) {
            _phase.value = ConnectionPhase.GaveUp
            return null
        }
        retriesScheduled++
        _phase.value = ConnectionPhase.Connecting
        return delayMs
    }

    /**
     * A playback request found the engine given up: starts a fresh round (the backoff starts over
     * from its first delay). Returns true when the caller must now start a connection attempt
     * itself; false when there is nothing to re-arm (still connecting, connected, or released).
     * Giving up is otherwise permanent for the engine's lifetime — and the engine lives as long as
     * the Activity, so without this only a full relaunch would ever bring playback back.
     */
    fun rearmIfGaveUp(): Boolean {
        if (isReleased || _phase.value != ConnectionPhase.GaveUp) return false
        retriesScheduled = 0
        _phase.value = ConnectionPhase.Connecting
        return true
    }

    /** The engine is releasing: fail every current and future wait. */
    fun release() {
        isReleased = true
        _phase.value = ConnectionPhase.GaveUp
    }

    /** Suspends while [ConnectionPhase.Connecting]; returns the phase the round settled on. */
    suspend fun awaitSettled(): ConnectionPhase = phase.first { it != ConnectionPhase.Connecting }
}
