package com.v1per.synthx.macrobenchmark

import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Queue list scroll in expanded queue mode: two swipes toward the end of the list, then two back,
 * per iteration. The first upward swipe fades the controls out and the first downward one brings
 * them back, so both transitions are inside the measurement.
 *
 * Least safe scenario, because the list it touches can reorder and remove. Each swipe is one
 * straight vertical line on the left third of the list: a remove needs horizontal travel, the
 * reorder handle is at the right edge, and the swipe is too long to be a tap. The first three
 * rows are compared before and after, and the scenario fails loudly if they differ; it cannot
 * undo a change, only report it. Queue edits never touch the music folder.
 *
 * Likely flaky: needs a queue long enough for two swipes, which one unmeasured rehearsal of the
 * gesture checks first; on a ROM whose gesture injection wobbles sideways the row swipe could
 * engage.
 */
@RunWith(AndroidJUnit4::class)
class PlayerQueueScrollBenchmark {
    private companion object {
        const val SwipesEachWay = 2
    }

    @get:Rule
    val rule = MacrobenchmarkRule()

    @Before
    fun checkPreconditions() = Scenario.checkPreconditions()

    @Test
    fun queueScroll() {
        lateinit var lane: QueueDriver.ScrollLane
        lateinit var rowsBefore: List<String>
        rule.runScenario(
            defaultIterations = 8,
            prepare = {
                val queue = QueueDriver(it)
                PlayerDriver(it).ensureExpanded()
                PlaybackDriver(it).requireNextAvailable()
                queue.ensureQueueMode(on = true)
                lane = queue.requireScrollLane()
                rowsBefore = queue.topRowsSignature()
                queue.rehearseScroll(lane, SwipesEachWay)
            },
            beforeEach = { QueueDriver(it).showControls() },
            cleanup = {
                // Runs on a fresh launch, so the list is read back from the saved queue.
                val queue = QueueDriver(it)
                PlayerDriver(it).ensureExpanded()
                queue.ensureQueueMode(on = true)
                val rowsAfter = queue.topRowsSignature()
                check(rowsAfter == rowsBefore) {
                    "The queue's first rows changed during the scroll scenario, so a swipe " +
                        "reordered, removed or played a row. Before: $rowsBefore. After: " +
                        "$rowsAfter. Only synthX bench's queue is affected; re-queue the list."
                }
                queue.ensureQueueMode(on = false)
            },
        ) {
            val queue = QueueDriver(it)
            repeat(SwipesEachWay) { queue.scrollTowardEnd(lane) }
            repeat(SwipesEachWay) { queue.scrollTowardStart(lane) }
        }
    }
}
