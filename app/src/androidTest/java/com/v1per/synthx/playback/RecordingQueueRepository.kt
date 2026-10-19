package com.v1per.synthx.playback

import com.v1per.synthx.data.repository.AdmittedQueuePersistenceRepository
import com.v1per.synthx.data.repository.QueueSlot
import com.v1per.synthx.data.repository.QueueSlotRecord
import com.v1per.synthx.data.repository.QueueSnapshot
import com.v1per.synthx.data.repository.SongRepository
import com.v1per.synthx.ui.testing.stubSongRepository
import java.util.concurrent.CopyOnWriteArrayList

/** One queue write, as the repository received it. */
internal sealed interface QueueWrite {
    data class QueueState(val slots: List<QueueSlotRecord>, val currentSlotId: String?, val positionMs: Long) :
        QueueWrite
    data class Cursor(val currentSlotId: String?, val positionMs: Long) : QueueWrite
    data class Baseline(val slots: List<QueueSlotRecord>) : QueueWrite
    data class Shuffle(val enabled: Boolean) : QueueWrite
    data class Repeat(val mode: Int) : QueueWrite
    data class Unified(
        val activeSlots: List<QueueSlotRecord>,
        val baselineSlots: List<QueueSlotRecord>,
        val currentSlotId: String?,
        val positionMs: Long,
        val shuffleEnabled: Boolean,
        val repeatMode: Int,
    ) : QueueWrite
}

/**
 * A typed fake of the queue half of the database layer: it serves a saved queue to the read paths
 * and records every write in order, writing nothing. Implemented against the real interfaces, so a
 * renamed or re-shaped repository method is a compile error here rather than a silent miss. Every
 * other [SongRepository] call goes to [stubSongRepository], which fails loudly.
 */
internal class RecordingQueueRepository :
    SongRepository by stubSongRepository(),
    AdmittedQueuePersistenceRepository {

    val writes = CopyOnWriteArrayList<QueueWrite>()

    /** What [getQueueSnapshot] serves; the default is "nothing saved". */
    @Volatile var snapshot: QueueSnapshot = QueueSnapshot(emptyList(), 0, null)

    /** What the baseline reads serve. */
    @Volatile var baseline: List<QueueSlot> = emptyList()

    /** What the adoption read serves when it should differ from [snapshot] and [baseline]. */
    @Volatile var adoptionRead: Pair<QueueSnapshot, List<QueueSlot>>? = null

    inline fun <reified T : QueueWrite> writesOf(): List<T> = writes.filterIsInstance<T>()

    override suspend fun getQueueSnapshot(): QueueSnapshot = snapshot

    override suspend fun getBaselineQueueSlots(): List<QueueSlot> = baseline

    override suspend fun getQueueSnapshotWithBaseline(): Pair<QueueSnapshot, List<QueueSlot>> =
        adoptionRead ?: (snapshot to baseline)

    override suspend fun setQueueState(slots: List<QueueSlotRecord>, currentSlotId: String?, positionMs: Long) {
        writes += QueueWrite.QueueState(slots, currentSlotId, positionMs)
    }

    override suspend fun setCursorAlreadyAdmitted(currentSlotId: String?, positionMs: Long) {
        writes += QueueWrite.Cursor(currentSlotId, positionMs)
    }

    override suspend fun setBaselineQueueSlots(slots: List<QueueSlotRecord>) {
        writes += QueueWrite.Baseline(slots)
    }

    override suspend fun setShuffleEnabledAlreadyAdmitted(enabled: Boolean) {
        writes += QueueWrite.Shuffle(enabled)
    }

    override suspend fun setRepeatModeAlreadyAdmitted(mode: Int) {
        writes += QueueWrite.Repeat(mode)
    }

    override suspend fun setUnifiedQueueState(
        activeSlots: List<QueueSlotRecord>,
        baselineSlots: List<QueueSlotRecord>,
        currentSlotId: String?,
        positionMs: Long,
        shuffleEnabled: Boolean,
        repeatMode: Int,
    ) {
        writes += QueueWrite.Unified(activeSlots, baselineSlots, currentSlotId, positionMs, shuffleEnabled, repeatMode)
    }
}
