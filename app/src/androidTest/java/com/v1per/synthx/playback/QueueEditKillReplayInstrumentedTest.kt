package com.v1per.synthx.playback

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.data.SongDeletionEvents
import com.v1per.synthx.data.SongRemapEvents
import com.v1per.synthx.data.repository.QueueExtraKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A process death at any point of an edit's writes: every prefix of the recorded writes is replayed
 * into a database image and restored on a new engine. Each must come back as the queue before or
 * after the edit, and once the live order is saved, as the queue after it: an added song is never
 * lost and a removed one never comes back, in both shuffle modes. The one accepted exception is a
 * rescan remap to a new id while shuffled, pinned at the end of this class.
 */
@RunWith(AndroidJUnit4::class)
internal class QueueEditKillReplayInstrumentedTest : QueueRestartTestBase() {

    private lateinit var seed: RealDbSongSeed

    @Before fun setUpSeed() {
        seed = RealDbSongSeed(context)
    }

    @After fun cleanUpSeed() {
        seed.cleanUp()
    }

    private data class Restored(val order: OrderState, val current: String?)

    private fun restoredFrom(writes: List<QueueWrite>, missing: Set<String>, deleted: Set<String>): Restored {
        val restored = restartFrom(writes, missing, deleted)
        return Restored(orderState(restored), onMain { restored._currentSlotId.value })
    }

    private class Replay(val pre: Restored, val post: Restored, val byPrefix: List<Pair<List<QueueWrite>, Restored>>)

    /**
     * Runs [edit] after the fresh play already on [engine], then restores the database before the
     * edit, after it, and after every prefix of its writes.
     */
    private fun replayEveryPrefix(
        edit: () -> Unit,
        structuralWrites: Int = 2,
        missing: Set<String> = emptySet(),
        deleted: Set<String> = emptySet(),
    ): Replay {
        val before = writes()
        edit()
        await("$structuralWrites structural writes") {
            writes().drop(before.size).count { it is QueueWrite.QueueState || it is QueueWrite.Baseline } >=
                structuralWrites
        }
        settle()
        val all = writes()
        val pre = restoredFrom(before, missing, deleted)
        val post = restoredFrom(all, missing, deleted)
        val prefixes = (before.size + 1 until all.size).map { all.take(it) }
        return Replay(pre, post, prefixes.map { it to restoredFrom(it, missing, deleted) })
    }

    /** Every prefix comes back as before or after; every prefix that holds the live write, as after. */
    private fun Replay.assertBeforeOrAfter() {
        assertNotEquals("the edit must change the saved queue", pre, post)
        byPrefix.forEach { (prefix, restored) ->
            val liveSaved = prefix.drop(prefix.indexOfLast { it is QueueWrite.Unified } + 1)
                .any { it is QueueWrite.QueueState }
            if (liveSaved) {
                assertEquals("after the live write (${prefix.last()})", post, restored)
            } else {
                assertTrue("before the live write: $restored", restored == pre || restored == post)
            }
        }
    }

    private fun upcomingSlot(index: Int) = onMain { engine.upcomingQueue.now()[index].slotId }

    // --- unshuffled ---------------------------------------------------------------------------------

    @Test fun anUnshuffledPlayNext() {
        play(songs(4, "kr-pn"), 0)
        val x = song("kr-pn-x").also { library[it.id] = it }
        val replay = replayEveryPrefix({ onMain { engine.playNext(x) } })
        replay.assertBeforeOrAfter()
        assertTrue("the added song is kept", replay.post.order.live.size == 5)
    }

    @Test fun anUnshuffledAddToQueue() {
        play(songs(4, "kr-aq"), 0)
        val x = song("kr-aq-x").also { library[it.id] = it }
        replayEveryPrefix({ onMain { engine.addToQueue(x) } }).assertBeforeOrAfter()
    }

    @Test fun anUnshuffledMove() {
        play(songs(5, "kr-mv"), 0)
        val moved = upcomingSlot(3)
        replayEveryPrefix({ onMain { engine.moveQueueItem(moved, 0) } }).assertBeforeOrAfter()
    }

    @Test fun anUnshuffledRemove() {
        play(songs(5, "kr-rm"), 0)
        val removed = upcomingSlot(1)
        val replay = replayEveryPrefix({ onMain { engine.removeQueueItem(removed) } })
        replay.assertBeforeOrAfter()
        replay.byPrefix.drop(1).forEach { (_, restored) -> assertFalse(removed in restored.order.live) }
    }

    @Test fun anUnshuffledLibraryDeletionOfSeveralCopies() {
        play(songsWithIds("kr-del-A", "kr-del-B", "kr-del-C", "kr-del-B"), 0)
        val replay = replayEveryPrefix(
            { runBlocking { SongDeletionEvents.notifyDeleted("kr-del-B") } },
            deleted = setOf("kr-del-B"),
        )
        // The cascade already took the rows, so before, after and between restore the same queue.
        assertEquals(replay.pre, replay.post)
        replay.byPrefix.forEach { (_, restored) -> assertEquals(replay.post, restored) }
        assertEquals(2, replay.post.order.live.size)
    }

    @Test fun anUnshuffledRescanRemap() {
        val list = songs(4, "kr-rmp")
        val renamed = song("kr-rmp-new").also { library[it.id] = it }
        (list + renamed).forEach { seed.seed(it) }
        play(list, 0)
        replayEveryPrefix(
            { runBlocking { SongRemapEvents.notifyRemap(list[2].id, renamed) } },
            missing = setOf(list[2].id),
        ).assertBeforeOrAfter()
    }

    // --- shuffled -----------------------------------------------------------------------------------

    @Test fun aShuffledRemoveNeverBringsTheSongBack() {
        play(songs(6, "kr-srm"), 0, shuffle = true)
        val removed = upcomingSlot(2)
        val replay = replayEveryPrefix({ onMain { engine.removeQueueItem(removed) } })
        replay.assertBeforeOrAfter()
        replay.byPrefix.drop(1).forEach { (_, restored) ->
            assertFalse("removed from live", removed in restored.order.live)
            assertFalse("not an extra", removed in restored.order.extras)
        }
    }

    @Test fun aShuffledLibraryDeletion() {
        play(songsWithIds("kr-sdel-A", "kr-sdel-B", "kr-sdel-C", "kr-sdel-D"), 0, shuffle = true)
        val replay = replayEveryPrefix(
            { runBlocking { SongDeletionEvents.notifyDeleted("kr-sdel-C") } },
            deleted = setOf("kr-sdel-C"),
        )
        assertEquals(replay.pre, replay.post)
        replay.byPrefix.forEach { (_, restored) -> assertEquals(replay.post, restored) }
        assertEquals(3, replay.post.order.live.size)
        assertTrue("no extra appears", replay.post.order.extras.isEmpty())
    }

    @Test fun shuffledPlayNextAndAddToQueueAreOneCommitAndKeepTheirSongs() {
        play(songs(5, "kr-spn"), 0, shuffle = true)
        val x = song("kr-spn-x").also { library[it.id] = it }
        val playNext = replayEveryPrefix({ onMain { engine.playNext(x) } }, structuralWrites = 1)
        playNext.assertBeforeOrAfter()
        assertEquals(QueueExtraKind.PlayNext, playNext.post.order.extras.values.single().kind)

        val y = song("kr-spn-y").also { library[it.id] = it }
        val addToQueue = replayEveryPrefix({ onMain { engine.addToQueue(y) } }, structuralWrites = 1)
        addToQueue.assertBeforeOrAfter()
        assertEquals(2, addToQueue.post.order.extras.size)
    }

    // --- the accepted exception: a shuffled rescan remap to a new id ---------------------------------

    /**
     * The old row is flagged missing, so after a death between the live and the baseline commit the
     * baseline read loses the slot: it comes back as an Add to Queue extra (docs/queue-accepted-behaviours.md).
     */
    @Test fun aShuffledRemapOfASongThatIsNotPlayingComesBackAsAnExtra() {
        val list = songs(5, "kr-xr")
        val renamed = song("kr-xr-new").also { library[it.id] = it }
        (list + renamed).forEach { seed.seed(it) }
        play(list, 0, shuffle = true)
        val remappedSlot = onMain { engine.queue.now().single { it.song.id == list[2].id }.slotId }

        val replay = replayEveryPrefix(
            { runBlocking { SongRemapEvents.notifyRemap(list[2].id, renamed) } },
            missing = setOf(list[2].id),
        )

        val (_, killed) = replay.byPrefix.first { (prefix, _) -> prefix.last() is QueueWrite.QueueState }
        assertEquals("every song is kept, in the post-edit live order", replay.post.order.live, killed.order.live)
        assertEquals(
            "the slot loses its album place",
            replay.post.order.baseline - remappedSlot,
            killed.order.baseline,
        )
        assertEquals(QueueExtraKind.AddToQueue, killed.order.extras.getValue(remappedSlot).kind)
        assertFalse("before the edit the old id is missing, so the song is gone", remappedSlot in replay.pre.order.live)
    }

    @Test fun aShuffledRemapOfThePlayingSongRebuildsTheBaselineFromLive() {
        val list = songs(5, "kr-xc")
        val renamed = song("kr-xc-new").also { library[it.id] = it }
        (list + renamed).forEach { seed.seed(it) }
        play(list, 0, shuffle = true)

        val replay = replayEveryPrefix(
            { runBlocking { SongRemapEvents.notifyRemap(list[0].id, renamed) } },
            missing = setOf(list[0].id),
        )

        val (_, killed) = replay.byPrefix.first { (prefix, _) -> prefix.last() is QueueWrite.QueueState }
        assertEquals(replay.post.order.live, killed.order.live)
        assertEquals("the baseline is the shuffled live order", killed.order.live, killed.order.baseline)
        assertNotEquals("the album order is lost", replay.post.order.baseline, killed.order.baseline)
        assertEquals(replay.post.current, killed.current)
    }
}
