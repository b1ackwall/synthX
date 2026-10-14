package com.v1per.synthx.macrobenchmark

import android.os.SystemClock
import android.view.KeyEvent
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2

/**
 * Play, pause and track changes through the player's own buttons, plus media keys for the mini
 * player, which has no next button. Nothing here edits the queue; a skip only moves the cursor.
 */
internal class PlaybackDriver(private val device: UiDevice) {
    private companion object {
        const val StateTimeoutMs = 5_000L
        const val PollMs = 100L

        /** Covers the art crossfade after a track change. */
        const val TrackChangePauseMs = 1_500L
    }

    private val player = PlayerDriver(device)

    /**
     * Read from the description of the icon inside the play/pause button (the tagged button
     * node itself carries none); null while buffering or unreadable.
     */
    fun isPlaying(): Boolean? {
        val tag = if (player.isExpanded()) Tags.PlayPause else Tags.MiniPlayPause
        val button = device.findObject(By.res(tag)) ?: return null
        return when {
            button.hasDescription(UiText.Pause) -> true
            button.hasDescription(UiText.Play) -> false
            else -> null
        }
    }

    private fun UiObject2.hasDescription(text: String): Boolean =
        contentDescription == text || hasObject(By.desc(text))

    fun ensurePlaying() = ensure(playing = true)

    fun ensurePaused() = ensure(playing = false)

    /** Taps the expanded player's next button, then waits out the track change. */
    fun next() = tapTransport(Tags.Next, "Next")

    fun previous() = tapTransport(Tags.Previous, "Previous")

    /** Fails with setup advice when no song follows the current one. */
    fun requireNextAvailable() {
        check(AppReadiness.findOrFail(device, Tags.Next, "Next button").isEnabled) {
            "Next is disabled: the current song is the last of its queue. In synthX bench, play " +
                "the first song of a long list so several songs follow it, then pause."
        }
    }

    fun mediaNext() = mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)

    fun mediaPrevious() = mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)

    /**
     * Media keys go to whichever app owns the system's media button session, so the key is sent
     * only after the system reports that synthX bench owns it. The dump format is ROM-dependent;
     * an unreadable dump fails here instead of risking a key reaching another player.
     */
    fun requireMediaButtonSession() {
        val owner = device.executeShellCommand("dumpsys media_session").lineSequence()
            .firstOrNull { "Media button session is" in it }
        check(owner != null && BenchmarkTarget.PACKAGE in owner) {
            "synthX bench does not own the media button session, so a media key could reach " +
                "another app. 'dumpsys media_session' reported: ${owner?.trim() ?: "no such line"}. " +
                "Stop other media apps; if the line is missing, this ROM formats the dump differently."
        }
    }

    private fun mediaKey(keyCode: Int) {
        requireMediaButtonSession()
        check(device.pressKeyCode(keyCode)) { "Could not inject media key $keyCode." }
        SystemClock.sleep(TrackChangePauseMs)
    }

    private fun tapTransport(tag: String, what: String) {
        val button = AppReadiness.findOrFail(device, tag, "$what button")
        check(button.isEnabled) { "$what is disabled, so the track cannot change." }
        button.click()
        SystemClock.sleep(TrackChangePauseMs)
    }

    private fun ensure(playing: Boolean) {
        if (awaitKnownState() == playing) return
        val tag = if (player.isExpanded()) Tags.PlayPause else Tags.MiniPlayPause
        AppReadiness.findOrFail(device, tag, "Play/pause button").click()
        val deadline = SystemClock.uptimeMillis() + StateTimeoutMs
        while (isPlaying() != playing) {
            check(SystemClock.uptimeMillis() < deadline) {
                "Playback did not ${if (playing) "start" else "pause"} within " +
                    "${StateTimeoutMs / 1000} s after tapping play/pause."
            }
            SystemClock.sleep(PollMs)
        }
    }

    private fun awaitKnownState(): Boolean {
        val deadline = SystemClock.uptimeMillis() + StateTimeoutMs
        while (true) {
            isPlaying()?.let { return it }
            check(SystemClock.uptimeMillis() < deadline) {
                "Could not read the play/pause state for ${StateTimeoutMs / 1000} s (still " +
                    "buffering, or the button's description is not '${UiText.Play}'/'${UiText.Pause}')."
            }
            SystemClock.sleep(PollMs)
        }
    }
}

/** Steps back to the song a scenario started on after its skips. */
internal class TrackRestore(private val device: UiDevice) {
    private companion object {
        /** While playing, the first Previous press may only restart the current song. */
        const val ExtraAttempts = 2
    }

    private val player = PlayerDriver(device)
    private var origin: String? = null
    private var pendingSkips = 0

    fun rememberOrigin() {
        origin = requireTitle()
    }

    fun noteSkip() {
        pendingSkips++
    }

    /** Presses [previous] once per pending skip, then until the starting title is back. */
    fun restore(previous: () -> Unit) {
        // Nothing to undo when the scenario failed before it read its starting song.
        val start = origin ?: return
        if (pendingSkips > 0 && requireTitle() == start) {
            error(
                "The track did not change after a skip (still '$start'). If two neighbouring " +
                    "songs share this title, start from a different song.",
            )
        }
        repeat(pendingSkips) { previous() }
        pendingSkips = 0
        var attempts = 0
        while (requireTitle() != start && attempts++ < ExtraAttempts) previous()
        val current = requireTitle()
        check(current == start) {
            "Could not step back to '$start'; the player is on '$current'. The queue itself " +
                "is unchanged; pick the starting song again in synthX bench."
        }
    }

    /** An unreadable title fails here, so it can never pass for "the track changed". */
    private fun requireTitle(): String =
        checkNotNull(player.currentTitle()) {
            "Could not read the current song title from the player sheet (tag " +
                "'${Tags.PlayerSheet}'), so a track change cannot be confirmed."
        }
}
