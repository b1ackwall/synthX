package com.v1per.synthx.data

import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.ui.testing.TestTimeouts
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertTrue

private fun testSong(id: String) = Song(
    id = id,
    title = "title-$id",
    artist = "artist-$id",
    album = "album-$id",
    duration = 0L,
    filePath = "content://$id",
)

/**
 * Every SongRemapEvents/SongDeletionEvents emission is stamped with the shared
 * PlaybackCacheRevision at publish time (P4-T1/P4-T2) — this is what queue-loading's
 * revision-before/revision-after check depends on to notice a cache-invalidating event landed
 * during a suspension.
 */
class SongRemapEventsTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER) val testTimeout = TestTimeouts.jvm()

    @Test fun everyPublishedRemapCarriesAFreshIncreasingRevision() = runBlocking {
        val collector = async { SongRemapEvents.events.take(2).toList() }
        yield() // let the collector subscribe before anything is published

        SongRemapEvents.notifyRemap("old-1", testSong("new-1"))
        SongRemapEvents.notifyRemap("old-2", testSong("new-2"))
        val collected = withTimeout(5_000) { collector.await() }

        assertTrue(collected[1].revision > collected[0].revision)
    }

    @Test fun unpublishedRemapUsesAPlaceholderNeverConfusedWithARealRevision() {
        val pending = SongRemap("old", testSong("new"))
        assertTrue(pending.revision == SongRemap.UNPUBLISHED_REVISION)
    }
}
