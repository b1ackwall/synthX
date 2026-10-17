package com.v1per.synthx.data

import com.v1per.synthx.ui.testing.TestTimeouts
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertTrue

class PlaybackCacheRevisionTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER) val testTimeout = TestTimeouts.jvm()

    @Test fun nextIsStrictlyMonotonicAndNeverRepeats() {
        val first = PlaybackCacheRevision.next()
        val second = PlaybackCacheRevision.next()
        val third = PlaybackCacheRevision.next()

        assertTrue(second > first)
        assertTrue(third > second)
    }

    @Test fun currentReflectsTheLastPublishedValueWithoutAdvancingIt() {
        PlaybackCacheRevision.next()
        val snapshot = PlaybackCacheRevision.current()

        assertTrue(PlaybackCacheRevision.current() == snapshot, "current() must not itself advance the counter")
    }
}
