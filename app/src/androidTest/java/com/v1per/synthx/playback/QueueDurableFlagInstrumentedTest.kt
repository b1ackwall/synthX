package com.v1per.synthx.playback

import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.data.SongDeletionEvents
import com.v1per.synthx.data.SongRemapEvents
import com.v1per.synthx.data.repository.QueueCursor
import com.v1per.synthx.data.repository.QueueSlot
import com.v1per.synthx.data.repository.QueueSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The customised-queue flag is saved with the queue: set by the four edits (and saved with the
 * edit's own write), cleared by a fresh play, kept by everything else, and applied again by a
 * restore and by adopting a running session.
 */
@RunWith(AndroidJUnit4::class)
internal class QueueDurableFlagInstrumentedTest : QueueRestartTestBase() {

    private lateinit var seed: RealDbSongSeed

    @Before fun setUpSeed() {
        seed = RealDbSongSeed(context)
    }

    @After fun cleanUpSeed() {
        seed.cleanUp()
    }

    private fun customised(target: ExoPlayerEngine = engine) = onMain { target.isQueueCustomized.now() }

    /** The queue writes recorded after [mark], waiting for at least [count]. */
    private fun queueWritesAfter(mark: Int, count: Int = 1): List<QueueWrite.QueueState> {
        await("$count queue writes") { recorder.writes.drop(mark).count { it is QueueWrite.QueueState } >= count }
        settle()
        return recorder.writes.drop(mark).filterIsInstance<QueueWrite.QueueState>()
    }

    private fun unifiedAfter(mark: Int): QueueWrite.Unified {
        await("unified write") { recorder.writes.drop(mark).any { it is QueueWrite.Unified } }
        settle()
        return recorder.writes.drop(mark).filterIsInstance<QueueWrite.Unified>().single()
    }

    private fun upcomingSlot(index: Int) = onMain { engine.upcomingQueue.now()[index].slotId }

    @Test fun theFirstEditsOwnWriteAlreadyCarriesTheFlag() {
        play(songs(4, "df-trap"), 0)
        val mark = recorder.writes.size

        onMain { engine.playNext(song("df-trap-x")) }

        assertEquals(true, queueWritesAfter(mark).single().queueCustomized)
    }

    @Test fun everyEditSavesTheQueueAsCustomised() {
        val edits: List<Pair<String, () -> Unit>> = listOf(
            "aq" to { onMain { engine.addToQueue(song("df-every-y")) } },
            "mv" to { upcomingSlot(2).let { slot -> onMain { engine.moveQueueItem(slot, 0) } } },
            "rm" to { upcomingSlot(1).let { slot -> onMain { engine.removeQueueItem(slot) } } },
        )
        edits.forEach { (name, edit) ->
            play(songs(5, "df-every-$name"), 0)
            assertFalse("$name: a fresh play starts untouched", customised())
            val mark = recorder.writes.size
            edit()
            assertEquals(name, true, queueWritesAfter(mark).single().queueCustomized)
            assertTrue(name, customised())
        }
    }

    @Test fun aFreshPlaySavesTheQueueAsUntouched() {
        play(songs(3, "df-fresh"), 0)
        onMain { engine.playNext(song("df-fresh-x")) }
        await("customised") { customised() }
        val mark = recorder.writes.size

        runBlocking { engine.playQueue(songs(3, "df-fresh2"), 0, false) }

        assertFalse(unifiedAfter(mark).queueCustomized)
        assertFalse(customised())
    }

    @Test fun aShuffleToggleKeepsTheFlagEitherWay() {
        play(songs(5, "df-tog"), 0)
        var mark = recorder.writes.size
        runBlocking { engine.setShuffleEnabled(true) }
        assertFalse("an untouched queue stays untouched", unifiedAfter(mark).queueCustomized)

        onMain { engine.playNext(song("df-tog-x")) }
        await("customised") { customised() }
        mark = recorder.writes.size
        runBlocking { engine.setShuffleEnabled(false) }

        assertTrue("a customised queue stays customised", unifiedAfter(mark).queueCustomized)
        assertTrue(customised())
    }

    @Test fun aLibraryDeletionKeepsTheFlagEitherWay() {
        play(songsWithIds("df-del-A", "df-del-B", "df-del-C", "df-del-D"), 0)
        var mark = recorder.writes.size
        runBlocking { SongDeletionEvents.notifyDeleted("df-del-B") }
        assertEquals(false, queueWritesAfter(mark).single().queueCustomized)
        assertFalse(customised())

        onMain { engine.addToQueue(song("df-del-x")) }
        await("customised") { customised() }
        mark = recorder.writes.size
        runBlocking { SongDeletionEvents.notifyDeleted("df-del-C") }

        assertEquals(true, queueWritesAfter(mark).single().queueCustomized)
        assertTrue(customised())
    }

    @Test fun aRescanRemapKeepsTheFlag() {
        val list = songs(4, "df-rmp")
        val renamed = song("df-rmp-new")
        (list + renamed).forEach { seed.seed(it) }
        play(list, 0)
        onMain { engine.playNext(song("df-rmp-x").also { seed.seed(it) }) }
        await("customised") { customised() }
        val mark = recorder.writes.size

        runBlocking { SongRemapEvents.notifyRemap(list[2].id, renamed) }

        assertEquals(true, queueWritesAfter(mark).single().queueCustomized)
        assertTrue(customised())
    }

    @Test fun aReconcileRewriteKeepsTheFlag() {
        val list = songs(3, "df-rec")
        play(list, 0)
        val x = song("df-rec-x")
        onMain { engine.playNext(x) }
        await("customised") { customised() }
        // Mirrored rows read back with different details, so the re-sync rewrites the queue.
        (list + x).forEach { seed.seed(it.copy(title = "${it.title} retagged")) }
        val mark = recorder.writes.size

        runBlocking { engine.reconcileWithDatabase() }

        assertEquals(true, queueWritesAfter(mark).last().queueCustomized)
    }

    @Test fun aPauseAndLeavingTheAppKeepTheFlag() {
        play(songs(3, "df-pause"), 0)
        onMain { engine.addToQueue(song("df-pause-x")) }
        await("customised") { customised() }
        var mark = recorder.writes.size

        onMain { engine.pause() }
        assertEquals(true, queueWritesAfter(mark).last().queueCustomized)

        mark = recorder.writes.size
        onMain { engine.persistPlaybackState() }
        assertEquals(true, queueWritesAfter(mark).last().queueCustomized)
    }

    @Test fun aRestartBringsBackACustomisedQueueCustomised() {
        play(songs(5, "df-rt"), 1)
        onMain { engine.playNext(song("df-rt-x").also { library[it.id] = it }) }
        await("customised") { customised() }
        settle()

        val restored = restartFrom(writes())

        assertTrue("a hand-built queue keeps its protection across a restart", customised(restored))
        assertEquals(6, onMain { restored.queue.now().size })
    }

    @Test fun aRestartBringsBackAnUntouchedQueueUntouched() {
        play(songs(5, "df-rtu"), 1)

        val restored = restartFrom(writes())

        assertFalse(customised(restored))
    }

    @Test fun adoptingARunningSessionKeepsTheFlag() {
        play(songs(5, "df-adopt"), 0)
        onMain { engine.playNext(song("df-adopt-x").also { library[it.id] = it }) }
        await("customised") { customised() }
        settle()
        recorder.adoptionRead = QueueDiskImage.after(writes()).read(library)

        val second = newEngine()

        await("adopted the running queue") { onMain { second.queue.now().size } == 6 }
        assertTrue("the adopted queue is still customised", customised(second))
    }

    @Test fun aRestoreWithNoQueueLeavesTheFlagCleared() {
        recorder.snapshot = QueueSnapshot(
            emptyList(),
            2,
            QueueCursor("gone", 0L, false, Player.REPEAT_MODE_OFF, queueCustomized = true),
        )

        runBlocking { engine.restoreSavedQueue() }
        settle()

        assertFalse(customised())
    }

    @Test fun theRestoreRewriteForDroppedSongsKeepsTheStoredFlag() {
        val slots = songs(3, "df-drop").mapIndexed { i, song -> QueueSlot("df-drop-$i", song) }
        recorder.snapshot = QueueSnapshot(
            slots,
            storedSlotCount = 4,
            cursor = QueueCursor("df-drop-1", 0L, false, Player.REPEAT_MODE_OFF, queueCustomized = true),
        )
        recorder.baseline = slots

        runBlocking { engine.restoreSavedQueue() }
        await("rewrite for the dropped song") { recorder.writesOf<QueueWrite.QueueState>().isNotEmpty() }
        settle()

        assertTrue(customised())
        assertNull(
            "the rewrite keeps the stored flag",
            recorder.writesOf<QueueWrite.QueueState>().single().queueCustomized,
        )
    }
}
