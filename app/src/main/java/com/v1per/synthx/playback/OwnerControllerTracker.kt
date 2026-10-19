package com.v1per.synthx.playback

/**
 * Pure counting logic behind [SynthXPlaybackService]'s "stop when the last owning controller
 * disconnects" rule. Split out from the `MediaSession.Callback` that drives it so
 * the actual decision — not just the code that pattern-matches Media3 callback signatures — is
 * unit-testable without a real `MediaSession`/`ControllerInfo`.
 *
 * Not thread-safe by itself; [SynthXPlaybackService] only ever calls this from session callbacks,
 * which Media3 dispatches serially on the session's own thread (the main thread here).
 */
internal class OwnerControllerTracker {
    private var count = 0
    private var hadOwner = false

    /** Call when an owning controller (this app's own [ExoPlayerEngine]) connects. */
    fun connected() {
        count += 1
        hadOwner = true
    }

    /**
     * Call when an owning controller disconnects. Returns true exactly once — the moment the
     * count reaches zero after having been positive — which is when playback should stop.
     * Returns false for a disconnect that still leaves an owner connected, and also for a
     * disconnect that arrives with no owner ever having connected (defensive floor: [count]
     * never goes negative, and [hadOwner] guards against a spurious first callback stopping
     * playback that never had an owner in the first place).
     */
    fun disconnected(): Boolean {
        count = (count - 1).coerceAtLeast(0)
        return count == 0 && hadOwner
    }
}
