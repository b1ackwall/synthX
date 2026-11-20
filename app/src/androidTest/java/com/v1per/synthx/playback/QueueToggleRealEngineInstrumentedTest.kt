package com.v1per.synthx.playback

import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [PlaybackViewModel.toggleShuffle] and [PlaybackViewModel.cycleRepeat] against the real engine:
 * back-to-back calls each compute their target from the engine when they run. Repeat reads the
 * controller's own repeat mode, which a set updates at once; the engine's repeat flow follows only
 * when media3 calls back.
 */
@RunWith(AndroidJUnit4::class)
class QueueToggleRealEngineInstrumentedTest : PlaybackServiceTestBase() {

    private fun viewModel(): PlaybackViewModel = onMain { PlaybackViewModel(PlaybackController(engine)) }

    @Test fun twoBackToBackRepeatCyclesGoOffAllOne() {
        startQueue(3, 0, RepeatMode.Off)
        val vm = viewModel()

        onMain {
            vm.cycleRepeat()
            vm.cycleRepeat()
        }

        await("repeat one") { onMain { engine._repeatMode.value == RepeatMode.One } }
        settle()
        assertEquals(RepeatMode.One, onMain { engine._repeatMode.value })
        assertEquals(Player.REPEAT_MODE_ONE, onMain { extraController.repeatMode })
    }

    @Test fun theControllersRepeatModeIsCurrentRightAfterASet() {
        startQueue(3, 0, RepeatMode.Off)

        val read = onMain {
            engine.setRepeatMode(RepeatMode.All)
            engine.currentRepeatMode()
        }

        assertEquals(RepeatMode.All, read)
    }

    @Test fun twoBackToBackShuffleTogglesTurnShuffleOnAndOffAgain() {
        startQueue(5, 0, RepeatMode.Off)
        val before = onMain { engine.orderStore.live.map { it.slotId } }
        val vm = viewModel()

        onMain {
            vm.toggleShuffle()
            vm.toggleShuffle()
        }
        settle()

        assertFalse(onMain { engine._shuffleEnabled.value })
        assertEquals("shuffle off restores the order", before, onMain { engine.orderStore.live.map { it.slotId } })
    }
}
