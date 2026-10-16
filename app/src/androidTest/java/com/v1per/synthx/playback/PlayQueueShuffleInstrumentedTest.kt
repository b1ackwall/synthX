package com.v1per.synthx.playback

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.data.repository.AdmittedQueuePersistenceRepository
import com.v1per.synthx.data.repository.QueueSlotRecord
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.data.repository.SongRepository
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * playQueue's explicit shuffle choice: the flag, the live/baseline orders and the persisted state
 * are set in one step, from a cold engine (nothing loaded, no current slot) as well as over an
 * existing queue. Writes go to a recording repository so the number of commits can be counted.
 */
@RunWith(AndroidJUnit4::class)
class PlayQueueShuffleInstrumentedTest : PlaybackServiceTestBase() {

    /** Every queue write the engine made except cursor-only checkpoints, by repository method name. */
    private val structuralWrites = CopyOnWriteArrayList<String>()

    /** The shuffle flag carried by each unified write, in order. */
    private val unifiedShuffleFlags = CopyOnWriteArrayList<Boolean>()

    override fun createPersistence(): QueuePersistence {
        val repository = Proxy.newProxyInstance(
            SongRepository::class.java.classLoader,
            arrayOf(SongRepository::class.java, AdmittedQueuePersistenceRepository::class.java),
        ) { _, method, args ->
            if (method.name != "setCursorAlreadyAdmitted") structuralWrites += method.name
            if (method.name == "setUnifiedQueueState") unifiedShuffleFlags += args[4] as Boolean
            // Every AdmittedQueuePersistenceRepository method returns Unit; nothing else is used.
            Unit
        } as SongRepository
        return QueuePersistence(repository)
    }

    @Test fun aColdShuffledPlayStartsOnTheChosenSongWithTheFlagOnInOneWrite() {
        assertNull("precondition: nothing loaded", onMain { engine._currentSlotId.value })
        assertEquals("precondition: shuffle off", false, onMain { engine._shuffleEnabled.value })
        val list = songs(6, "cold")

        playAndAwait(list, startIndex = 3, shuffle = true)

        onMain {
            assertTrue(engine._shuffleEnabled.value)
            assertEquals("cold-3", engine.orderStore.live.first().songId)
            assertEquals(list.map { it.id }, engine.orderStore.baseline.songIds())
            assertEquals(list.map { it.id }.toSet(), engine.orderStore.live.songIds().toSet())
        }
        assertOneUnifiedWrite(shuffle = true)
    }

    @Test fun anUnshuffledPlayOverAShuffledQueueTurnsTheFlagOffInListOrder() {
        playAndAwait(songs(5, "before"), startIndex = 2, shuffle = true)
        awaitWrites(1)
        settle()
        structuralWrites.clear()
        unifiedShuffleFlags.clear()
        val list = songs(5, "after")

        playAndAwait(list, startIndex = 0, shuffle = false)

        onMain {
            assertEquals(false, engine._shuffleEnabled.value)
            assertEquals(list.map { it.id }, engine.orderStore.live.songIds())
            assertEquals(list.map { it.id }, engine.orderStore.baseline.songIds())
        }
        assertOneUnifiedWrite(shuffle = false)
    }

    @Test fun aNullChoiceKeepsFollowingTheCurrentFlag() {
        playAndAwait(songs(4, "on"), startIndex = 0, shuffle = true)
        val list = songs(5, "follow")

        playAndAwait(list, startIndex = 1, shuffle = null)

        onMain {
            assertTrue(engine._shuffleEnabled.value)
            assertEquals("follow-1", engine.orderStore.live.first().songId)
            assertEquals(list.map { it.id }, engine.orderStore.baseline.songIds())
        }
    }

    @Test fun turningShuffleOffAfterAShuffledPlayRestoresListOrderAfterTheCurrentSong() {
        val list = songs(6, "restore")
        playAndAwait(list, startIndex = 2, shuffle = true)

        runBlocking { engine.setShuffleEnabled(false) }

        onMain {
            assertEquals(false, engine._shuffleEnabled.value)
            assertEquals(
                listOf("restore-2", "restore-3", "restore-4", "restore-5"),
                engine.orderStore.live.songIds(),
            )
        }
    }

    private fun playAndAwait(list: List<Song>, startIndex: Int, shuffle: Boolean?) {
        runBlocking { engine.playQueue(list, startIndex, shuffle) }
        val expected = list[startIndex].id
        await("$expected playing") {
            onMain {
                val slot = engine._currentSlotId.value
                slot != null && engine.songsBySlotId[slot]?.id == expected &&
                    extraController.currentMediaItem?.mediaId == slot
            }
        }
    }

    private fun awaitWrites(count: Int) =
        await("$count unified write(s)") { unifiedShuffleFlags.size >= count }

    /** Exactly one structural write — the unified one — carrying [shuffle]. */
    private fun assertOneUnifiedWrite(shuffle: Boolean) {
        awaitWrites(1)
        settle()
        assertEquals(listOf("setUnifiedQueueState"), structuralWrites.toList())
        assertEquals(listOf(shuffle), unifiedShuffleFlags.toList())
    }

    private fun List<QueueSlotRecord>.songIds() = map { it.songId }
}
