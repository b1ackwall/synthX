package com.v1per.synthx.playback

import com.v1per.synthx.data.db.SongEntity
import com.v1per.synthx.data.repository.QueueSlotRecord
import com.v1per.synthx.data.repository.RepositoryTestFixture
import com.v1per.synthx.data.repository.Song
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The saved queue through the real tables, an engine restart and the view model: a hand-built
 * queue is still protected after a cold start (a song tap asks Keep or Clear), and a restore whose
 * saved song is gone saves its new start slot together with that slot's queue position.
 */
@RunWith(AndroidJUnit4::class)
class QueuePersistenceEndToEndInstrumentedTest : PlaybackServiceTestBase() {

    private val fx by lazy { RepositoryTestFixture(context) }

    override fun createPersistence(): QueuePersistence = QueuePersistence(fx.repository)

    @After fun closeDatabase() {
        settle()
        fx.close()
    }

    private fun stored(list: List<Song>) = runBlocking {
        list.forEach {
            fx.songDao.upsertSong(
                SongEntity(
                    id = it.id,
                    title = it.title,
                    albumId = null,
                    duration = it.duration,
                    filePath = it.filePath,
                    albumArtUri = null,
                    year = null,
                    dateAdded = 1L,
                ),
            )
        }
        list
    }

    /** Releases [engine], clears the session and returns a new engine on the same tables. */
    private fun coldStart(): ExoPlayerEngine {
        settle()
        releaseEngine(engine)
        onMain {
            extraController.stop()
            extraController.clearMediaItems()
        }
        await("session cleared") { onMain { extraController.mediaItemCount == 0 } }
        return newEngine().also { engine = it }
    }

    private fun buildACustomisedQueue(): Int {
        val list = stored(songs(5, "e2e"))
        val x = stored(listOf(song("e2e-x"))).single()
        runBlocking { engine.playQueue(list, 1, false) }
        await("playing") { onMain { engine.currentSlot()?.song?.id == list[1].id } }
        onMain { engine.playNext(x) }
        await("customised") { onMain { engine.isQueueCustomized.now() } }
        return list.size + 1
    }

    @Test fun aCustomisedQueueIsStillCustomisedAfterAColdStart() {
        val size = buildACustomisedQueue()

        val restored = coldStart()
        runBlocking { restored.restoreSavedQueue() }
        await("restored") { onMain { restored.queue.now().size } == size }

        assertTrue(onMain { restored.isQueueCustomized.now() })
    }

    @Test fun aSongTapAfterAColdStartAsksKeepOrClearForACustomisedQueue() {
        val size = buildACustomisedQueue()
        val restored = coldStart()
        val viewModel = onMain { PlaybackViewModel(PlaybackController(restored)) }
        val prompts = CopyOnWriteArrayList<QueuePromptEvent>()
        val collector = CoroutineScope(Dispatchers.Main).launch { viewModel.queuePrompts.collect { prompts += it } }
        try {
            onMain { viewModel.restoreSavedQueue() }
            await("restored") { onMain { restored.queue.now().size } == size }
            await("the view model sees the stored flag") { onMain { viewModel.isQueueCustomized.value } }
            settle()

            val tapped = stored(songs(2, "e2e-tap"))
            onMain { viewModel.requestPlayQueue(tapped, 0) }
            await("the Keep or Clear question") { prompts.isNotEmpty() }

            assertEquals("e2e-tap-0", prompts.single().tappedSong.id)
            assertEquals("the hand-built queue is still there", size, onMain { restored.queue.now().size })
        } finally {
            collector.cancel()
        }
    }

    @Test fun aGoneSavedSongSavesTheNewStartAndItsQueuePosition() {
        val list = stored(songs(5, "e2e-gone"))
        runBlocking {
            fx.repository.setQueueState(
                list.mapIndexed { i, song -> QueueSlotRecord("gone-slot-$i", song.id) },
                currentSlotId = "gone-slot-2",
                positionMs = 9_000L,
            )
            fx.songDao.markMissing(list[2].id, 1L)
        }

        runBlocking { engine.restoreSavedQueue() }
        await("session on the next song") { onMain { extraController.currentMediaItem?.mediaId == "gone-slot-3" } }
        await("the cursor names the new start") {
            runBlocking { fx.repository.getQueueSnapshot().cursor?.currentSlotId } == "gone-slot-3"
        }
        settle()

        val cursor = runBlocking { fx.repository.getQueueSnapshot().cursor!! }
        assertEquals(0L, cursor.positionMs)
        assertEquals(
            "rows a, b, d, e",
            listOf(0, 1, 2, 3),
            runBlocking {
                fx.queueDao.getQueueItems()
            }.map { it.position },
        )
        assertEquals(2, singleInt("SELECT current_queue_position FROM playback_cursor WHERE id = 0"))
        assertEquals(2, singleInt("SELECT position FROM queue_items WHERE slot_id = 'gone-slot-3'"))
    }

    @Test fun aTrackChangeAndASeekKeepTheFlagAndFollowTheQueuePosition() {
        buildACustomisedQueue()
        settle()

        onMain { engine.next() }
        await("the next song is current") { onMain { extraController.currentMediaItem?.mediaId } == current() }
        val slot = current()
        await("the track change is saved") {
            runBlocking { fx.repository.getQueueSnapshot().cursor?.currentSlotId } == slot
        }
        onMain { extraController.seekTo(10_000L) }
        await("the seek is saved") {
            runBlocking { fx.repository.getQueueSnapshot().cursor?.positionMs ?: 0L } >= 9_000L
        }
        settle()

        assertEquals(
            "the flag survives cursor-only writes",
            1,
            singleInt("SELECT queue_customized FROM playback_cursor"),
        )
        assertEquals(
            singleInt("SELECT position FROM queue_items WHERE slot_id = '$slot'"),
            singleInt("SELECT current_queue_position FROM playback_cursor"),
        )
    }

    private fun current(): String? = onMain { engine._currentSlotId.value }

    private fun singleInt(sql: String): Int = fx.database.openHelper.readableDatabase.query(sql).use {
        check(it.moveToFirst()) { "no row for $sql" }
        it.getInt(0)
    }
}
