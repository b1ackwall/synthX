package com.v1per.synthx.playback

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.data.SongDeletionEvents
import com.v1per.synthx.data.SongRemapEvents
import com.v1per.synthx.data.repository.Song
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every queue edit that saves both orders saves the live order first and the baseline second, as
 * two commits through the one queue writer; an edit that leaves the baseline alone saves the live
 * order only. With the live order first, a process death between the two leaves a database the
 * restore repair turns back into the queue before or after the edit (QueueEditKillReplayInstrumentedTest).
 */
@RunWith(AndroidJUnit4::class)
class QueueEditWriteOrderInstrumentedTest : PlaybackServiceTestBase() {

    private val recorder = RecordingQueueRepository()
    private lateinit var seed: RealDbSongSeed

    override fun createPersistence(): QueuePersistence = QueuePersistence(recorder)

    @Before fun setUpSeed() {
        seed = RealDbSongSeed(context)
    }

    @After fun cleanUpSeed() {
        seed.cleanUp()
    }

    private fun play(list: List<Song>, startIndex: Int, shuffle: Boolean) {
        runBlocking { engine.playQueue(list, startIndex, shuffle) }
        await("${list[startIndex].id} loaded") {
            onMain {
                engine.currentSlot()?.let {
                    it.song.id == list[startIndex].id && extraController.currentMediaItem?.mediaId == it.slotId
                } == true
            }
        }
        await("load written") { recorder.writesOf<QueueWrite.Unified>().isNotEmpty() }
        settle()
        recorder.writes.clear()
    }

    /** The structural writes of one edit, in commit order: "live" for the queue write, "baseline". */
    private fun structuralWritesOf(edit: () -> Unit, expected: Int): List<String> {
        edit()
        await("$expected structural writes") {
            recorder.writes.count { it is QueueWrite.QueueState || it is QueueWrite.Baseline } >= expected
        }
        settle()
        return recorder.writes.mapNotNull {
            when (it) {
                is QueueWrite.QueueState -> "live"
                is QueueWrite.Baseline -> "baseline"
                else -> null
            }
        }
    }

    private fun upcomingSlot(index: Int) = onMain { engine.upcomingQueue.now()[index].slotId }

    private val liveFirst = listOf("live", "baseline")

    @Test fun anUnshuffledPlayNextSavesTheLiveOrderFirst() {
        play(songs(4, "wo-pn"), 0, shuffle = false)
        assertEquals(liveFirst, structuralWritesOf({ onMain { engine.playNext(song("wo-pn-x")) } }, 2))
    }

    @Test fun anUnshuffledAddToQueueSavesTheLiveOrderFirst() {
        play(songs(4, "wo-aq"), 0, shuffle = false)
        assertEquals(liveFirst, structuralWritesOf({ onMain { engine.addToQueue(song("wo-aq-x")) } }, 2))
    }

    @Test fun anUnshuffledMoveSavesTheLiveOrderFirst() {
        play(songs(5, "wo-mv"), 0, shuffle = false)
        val moved = upcomingSlot(3)
        assertEquals(liveFirst, structuralWritesOf({ onMain { engine.moveQueueItem(moved, 0) } }, 2))
    }

    @Test fun anUnshuffledRemoveSavesTheLiveOrderFirst() {
        play(songs(5, "wo-rm"), 0, shuffle = false)
        val removed = upcomingSlot(1)
        assertEquals(liveFirst, structuralWritesOf({ onMain { engine.removeQueueItem(removed) } }, 2))
    }

    @Test fun aShuffledRemoveOfAnAlbumSongSavesTheLiveOrderFirst() {
        play(songs(6, "wo-srm"), 0, shuffle = true)
        val removed = upcomingSlot(2)
        assertEquals(liveFirst, structuralWritesOf({ onMain { engine.removeQueueItem(removed) } }, 2))
    }

    @Test fun aLibraryDeletionSavesTheLiveOrderFirst() {
        play(songsWithIds("wo-del-A", "wo-del-B", "wo-del-C", "wo-del-B"), 0, shuffle = false)
        assertEquals(
            liveFirst,
            structuralWritesOf({ runBlocking { SongDeletionEvents.notifyDeleted("wo-del-B") } }, 2),
        )
    }

    @Test fun aShuffledLibraryDeletionSavesTheLiveOrderFirst() {
        play(songsWithIds("wo-sdel-A", "wo-sdel-B", "wo-sdel-C", "wo-sdel-D"), 0, shuffle = true)
        assertEquals(
            liveFirst,
            structuralWritesOf({ runBlocking { SongDeletionEvents.notifyDeleted("wo-sdel-C") } }, 2),
        )
    }

    @Test fun aRescanRemapSavesTheLiveOrderFirst() = remapSavesTheLiveOrderFirst("wo-rmp", shuffle = false)

    @Test fun aShuffledRescanRemapSavesTheLiveOrderFirst() = remapSavesTheLiveOrderFirst("wo-srmp", shuffle = true)

    private fun remapSavesTheLiveOrderFirst(prefix: String, shuffle: Boolean) {
        val list = songs(4, prefix)
        val renamed = song("$prefix-new")
        (list + renamed).forEach { seed.seed(it) }
        play(list, 0, shuffle)
        assertEquals(
            liveFirst,
            structuralWritesOf({ runBlocking { SongRemapEvents.notifyRemap(list[2].id, renamed) } }, 2),
        )
    }

    @Test fun shuffledEditsThatLeaveTheBaselineAloneSaveOnlyTheLiveOrder() {
        play(songs(6, "wo-one"), 0, shuffle = true)
        assertEquals(listOf("live"), structuralWritesOf({ onMain { engine.playNext(song("wo-one-x")) } }, 1))
        recorder.writes.clear()
        assertEquals(listOf("live"), structuralWritesOf({ onMain { engine.addToQueue(song("wo-one-y")) } }, 1))
        recorder.writes.clear()
        val moved = upcomingSlot(3)
        assertEquals(listOf("live"), structuralWritesOf({ onMain { engine.moveQueueItem(moved, 0) } }, 1))
    }
}
