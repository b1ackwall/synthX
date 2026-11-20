package com.v1per.synthx.playback

import com.v1per.synthx.data.repository.QueueExtraTag
import com.v1per.synthx.data.repository.Song
import kotlinx.coroutines.runBlocking

/**
 * A real engine and service whose writes are recorded, plus a restart: [restartFrom] replays some
 * of those writes into a [QueueDiskImage], releases the engine, clears the shared session and
 * restores a new engine from that image, as a process death and a cold start would.
 */
internal abstract class QueueRestartTestBase : PlaybackServiceTestBase() {

    protected val recorder = RecordingQueueRepository()

    override fun createPersistence(): QueuePersistence = QueuePersistence(recorder)

    /** Every song a test queued, by id, so a disk image can resolve its rows. */
    protected val library = mutableMapOf<String, Song>()

    /** What a restored engine holds: the live order, the baseline and the extra tags. */
    protected data class OrderState(
        val live: List<String>,
        val baseline: List<String>,
        val extras: Map<String, QueueExtraTag>,
    )

    protected fun orderState(target: ExoPlayerEngine = engine): OrderState = onMain {
        OrderState(
            target.orderStore.live.map { it.slotId },
            target.orderStore.baseline.map { it.slotId },
            target.orderStore.extras,
        )
    }

    /** Plays [list] fresh at [startIndex] and waits until it is loaded and written. */
    protected fun play(list: List<Song>, startIndex: Int, shuffle: Boolean? = false) {
        list.forEach { library[it.id] = it }
        runBlocking { engine.playQueue(list, startIndex, shuffle) }
        await("${list[startIndex].id} loaded in the session") {
            onMain {
                engine.currentSlot()?.let {
                    it.song.id == list[startIndex].id && extraController.currentMediaItem?.mediaId == it.slotId
                } == true
            }
        }
        await("load written") { recorder.writesOf<QueueWrite.Unified>().isNotEmpty() }
        settle()
    }

    /** The recorded writes so far, as one list. */
    protected fun writes(): List<QueueWrite> = recorder.writes.toList()

    /**
     * Restores a new engine from the database [writes] leave behind and returns it once settled.
     * The current engine is released and the session cleared first, so nothing is adopted.
     */
    protected fun restartFrom(
        writes: List<QueueWrite>,
        missingSongIds: Set<String> = emptySet(),
        deletedSongIds: Set<String> = emptySet(),
    ): ExoPlayerEngine {
        releaseEngine(engine)
        onMain {
            extraController.stop()
            extraController.clearMediaItems()
        }
        await("session cleared") { onMain { extraController.mediaItemCount == 0 } }
        val (snapshot, baseline) = QueueDiskImage.after(writes).read(library, missingSongIds, deletedSongIds)
        recorder.snapshot = snapshot
        recorder.baseline = baseline
        recorder.adoptionRead = null
        val restored = newEngine()
        engine = restored
        await("new engine connected") { onMain { restored.controller != null } }
        runBlocking { restored.restoreSavedQueue() }
        if (snapshot.slots.isNotEmpty()) {
            await("restored queue loaded") {
                onMain {
                    val slot = restored._currentSlotId.value
                    slot != null && extraController.currentMediaItem?.mediaId == slot
                }
            }
        }
        settle()
        return restored
    }
}
