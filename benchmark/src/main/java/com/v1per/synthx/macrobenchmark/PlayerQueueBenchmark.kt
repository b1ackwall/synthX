package com.v1per.synthx.macrobenchmark

import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Queue mode on the expanded player. Both scenarios need at least one upcoming song, because the
 * queue list is how the benchmark tells queue mode from player mode.
 *
 * queueToggle: with the player expanded, tap the queue button to enter queue mode, then again to
 * leave it. Rarely flaky: two taps with fixed pauses.
 *
 * queueExpandCollapse: queue mode is turned on once, then each iteration taps the mini player to
 * expand and presses back to collapse with queue mode still on (it survives a collapse). Rarely
 * flaky.
 *
 * Neither needs its own cleanup: the baseline check that ends every scenario turns queue mode off.
 */
@RunWith(AndroidJUnit4::class)
class PlayerQueueBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Before
    fun checkPreconditions() = Scenario.checkPreconditions()

    @Test
    fun queueToggle() {
        rule.runScenario(
            defaultIterations = 10,
            prepare = {
                PlayerDriver(it).ensureExpanded()
                // No next song means no queue list, which would leave queue mode unreadable.
                PlaybackDriver(it).requireNextAvailable()
            },
        ) {
            val queue = QueueDriver(it)
            queue.toggle()
            queue.toggle()
        }
    }

    @Test
    fun queueExpandCollapse() {
        rule.runScenario(
            defaultIterations = 10,
            prepare = {
                val player = PlayerDriver(it)
                player.ensureExpanded()
                PlaybackDriver(it).requireNextAvailable()
                QueueDriver(it).ensureQueueMode(on = true)
                player.collapse()
            },
            beforeEach = { PlayerDriver(it).ensureCollapsed() },
        ) {
            val player = PlayerDriver(it)
            player.expand()
            player.collapse()
        }
    }
}
