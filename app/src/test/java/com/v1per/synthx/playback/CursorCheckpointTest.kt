package com.v1per.synthx.playback

import androidx.media3.common.Player
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * P4-T7 / R3-F13's missing-cursor-checkpoint fix. Each production call site
 * ([ExoPlayerEngineListener.onIsPlayingChanged], `onPositionDiscontinuity`,
 * [SynthXPlaybackService.onDestroy], and the new periodic ticker) needs a real
 * `MediaController`/`Player.Listener`/`Service` to exercise end to end, which — per the
 * `IdleRecoveryTest` precedent this module already established — this codebase has no
 * JVM-level fake or mocking library for (no Mockito/MockK, no Robolectric). What's covered here
 * is the complete pure decision logic each fix introduced ([handleIsPlayingChangedForCheckpoint],
 * [handleSameItemSeekForCheckpoint], [shouldCheckpointOnDestroy]) and, for the periodic ticker,
 * the actual production driver function ([periodicCheckpointDriver]) — that one needs no
 * `MediaController` at all, only an injected `isPlaying` [kotlinx.coroutines.flow.Flow], so it is
 * fully exercised here rather than only its decision logic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CursorCheckpointTest {

    // --- onIsPlayingChanged(false) checkpoint --------------------------------------------

    @Test fun playbackStoppingTriggersACheckpoint() {
        var checkpointed = false
        handleIsPlayingChangedForCheckpoint(isPlaying = false) { checkpointed = true }
        assertTrue(checkpointed, "any stop — user pause, focus loss, becoming-noisy — must checkpoint")
    }

    @Test fun playbackStartingDoesNotCheckpoint() {
        var checkpointed = false
        handleIsPlayingChangedForCheckpoint(isPlaying = true) { checkpointed = true }
        assertFalse(checkpointed)
    }

    // --- resume position at natural end-of-queue ------------------------------------------

    @Test fun anEndedPlayerResumesFromTheStartOfTheTrackNotItsEnd() {
        assertEquals(0L, resumePositionMs(Player.STATE_ENDED, positionMs = 59_800L))
    }

    @Test fun everyOtherStateKeepsTheRealPosition() {
        listOf(Player.STATE_IDLE, Player.STATE_BUFFERING, Player.STATE_READY).forEach { state ->
            assertEquals(12_345L, resumePositionMs(state, positionMs = 12_345L), "state $state")
        }
    }

    // --- onPositionDiscontinuity (same-item seek) checkpoint -----------------------------

    @Test fun sameItemSeekTriggersACheckpoint() {
        var checkpointed = false
        handleSameItemSeekForCheckpoint(
            oldMediaItemIndex = 3,
            newMediaItemIndex = 3,
            reason = Player.DISCONTINUITY_REASON_SEEK,
        ) { checkpointed = true }
        assertTrue(checkpointed, "a seek within the currently playing item must checkpoint")
    }

    @Test fun crossItemSeekDoesNotDoubleCheckpoint() {
        // onMediaItemTransition already checkpoints the fresh item a moment later — this must
        // not race it with the stale pre-seek media item.
        var checkpointed = false
        handleSameItemSeekForCheckpoint(
            oldMediaItemIndex = 3,
            newMediaItemIndex = 4,
            reason = Player.DISCONTINUITY_REASON_SEEK,
        ) { checkpointed = true }
        assertFalse(checkpointed)
    }

    @Test fun nonSeekDiscontinuityDoesNotCheckpoint() {
        var checkpointed = false
        handleSameItemSeekForCheckpoint(
            oldMediaItemIndex = 3,
            newMediaItemIndex = 3,
            reason = Player.DISCONTINUITY_REASON_INTERNAL,
        ) { checkpointed = true }
        assertFalse(checkpointed, "only a genuine SEEK reason should checkpoint here")
    }

    // --- onDestroy checkpoint --------------------------------------------------------------

    @Test fun onDestroyCheckpointsWhenTaskRemovedNeverRan() {
        assertTrue(
            shouldCheckpointOnDestroy(released = false, mediaItemIndex = 2, mediaItemCount = 5),
            "onDestroy is the only teardown callback that ran; it must checkpoint",
        )
    }

    @Test fun onDestroySkipsWhenOnTaskRemovedAlreadyCheckpointedAndReleased() {
        assertFalse(
            shouldCheckpointOnDestroy(released = true, mediaItemIndex = 2, mediaItemCount = 5),
            "onTaskRemoved already checkpointed and released the player; a second read would be post-stop state",
        )
    }

    @Test fun onDestroySkipsWhenNothingWasEverLoaded() {
        assertFalse(shouldCheckpointOnDestroy(released = false, mediaItemIndex = -1, mediaItemCount = 0))
    }

    // --- periodic checkpoint ticker ---------------------------------------------------------

    @Test fun periodicTickerFiresOnlyWhilePlayingAtTheExpectedInterval() = runTest {
        val isPlaying = MutableStateFlow(false)
        val ticks = mutableListOf<Long>()
        launchCollector(this, isPlaying, ticks)

        // Not playing yet — no ticks even after several intervals' worth of virtual time.
        advanceTimeBy(3 * PERIODIC_CHECKPOINT_INTERVAL_MS)
        assertEquals(0, ticks.size, "must not tick while paused/stopped")

        isPlaying.value = true
        advanceTimeBy(PERIODIC_CHECKPOINT_INTERVAL_MS - 1)
        runCurrent()
        assertEquals(0, ticks.size, "must not tick before a full interval has elapsed")

        // advanceTimeBy runs everything scheduled strictly before the new instant; the tick
        // scheduled for exactly this instant still needs an explicit runCurrent() to fire.
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, ticks.size, "must tick exactly at the interval boundary")

        advanceTimeBy(PERIODIC_CHECKPOINT_INTERVAL_MS)
        runCurrent()
        assertEquals(2, ticks.size)

        // Pausing stops the ticker immediately — no further ticks even after a long wait.
        isPlaying.value = false
        advanceTimeBy(5 * PERIODIC_CHECKPOINT_INTERVAL_MS)
        runCurrent()
        assertEquals(2, ticks.size, "must stop ticking the instant playback stops")

        // Resuming restarts the interval from zero rather than resuming a stale countdown.
        isPlaying.value = true
        advanceTimeBy(PERIODIC_CHECKPOINT_INTERVAL_MS - 1)
        runCurrent()
        assertEquals(2, ticks.size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(3, ticks.size)
    }

    @Test fun periodicTickerNeverTicksIfNeverPlaying() = runTest {
        val isPlaying = MutableStateFlow(false)
        val ticks = mutableListOf<Long>()
        launchCollector(this, isPlaying, ticks)

        advanceUntilIdle()
        assertTrue(ticks.isEmpty())
    }

    // backgroundScope.launch, not a plain launch: TestScope automatically cancels every
    // backgroundScope job when runTest's body completes, so the never-ending collector here
    // (periodicCheckpointDriver's while(true) loop) needs no explicit cancel() in each test.
    private fun launchCollector(
        scope: TestScope,
        isPlaying: MutableStateFlow<Boolean>,
        ticks: MutableList<Long>,
    ) = scope.backgroundScope.launch {
        periodicCheckpointDriver(isPlaying).collect { ticks += scope.testScheduler.currentTime }
    }
}
