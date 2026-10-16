package com.v1per.synthx.macrobenchmark

import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.macrobenchmark.LibraryListDriver.Category
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Library list scrolls with the player collapsed: from the Library tab, open Songs, Starred or
 * Playlists once, then per iteration two swipes toward the end of the list and two back. The
 * gesture and iteration count match queueScroll, so the numbers are comparable.
 *
 * Likely flaky only with a short list: one unmeasured rehearsal of the gesture checks that both
 * swipes move the list and that it returns to its start.
 */
@RunWith(AndroidJUnit4::class)
class LibraryListScrollBenchmark {
    private companion object {
        const val SwipesEachWay = 2
    }

    @get:Rule
    val rule = MacrobenchmarkRule()

    @Before
    fun checkPreconditions() = Scenario.checkPreconditions()

    @Test
    fun songsScroll() = scroll(Category.Songs)

    @Test
    fun starredScroll() = scroll(Category.Starred)

    @Test
    fun playlistsScroll() = scroll(Category.Playlists)

    private fun scroll(category: Category) {
        lateinit var lane: ScrollLane
        rule.runScenario(
            defaultIterations = 8,
            prepare = {
                val lists = LibraryListDriver(it)
                lists.open(category)
                lane = lists.requireScrollLane(category)
                lists.rehearseScroll(category, lane, SwipesEachWay)
            },
            beforeEach = { LibraryListDriver(it).requireListShowing(category) },
        ) {
            repeat(SwipesEachWay) { ListSwipe.towardEnd(lane) }
            repeat(SwipesEachWay) { ListSwipe.towardStart(lane) }
        }
    }
}
