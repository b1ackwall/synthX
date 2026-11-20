package com.v1per.synthx.playback

import com.v1per.synthx.data.repository.QueueCursor
import com.v1per.synthx.data.repository.QueueExtraTag
import com.v1per.synthx.data.repository.QueueSlot
import com.v1per.synthx.data.repository.QueueSlotRecord
import com.v1per.synthx.data.repository.QueueSnapshot
import com.v1per.synthx.data.repository.Song

/**
 * What the three queue tables hold after a sequence of recorded [QueueWrite]s, applied the way the
 * DAO applies them: each write is one commit, a queue write rebuilds the cursor row and keeps the
 * stored reorder and customised flags when it is given none, and every cursor write stores the queue
 * position of the current slot's row (or keeps the stored one while the slot is unchanged and its row
 * is gone). [snapshot] then reads it back the way the repository does, after a cascade of
 * [deletedSongIds] and with [missingSongIds] filtered out, so a prefix of a test's writes is the
 * database a process death at that point would leave.
 */
internal class QueueDiskImage private constructor() {
    private data class Row(val record: QueueSlotRecord, val tag: QueueExtraTag?)

    private data class Cursor(
        val currentSlotId: String?,
        val positionMs: Long,
        val shuffleEnabled: Boolean,
        val repeatMode: Int,
        val shuffleReordered: Boolean,
        val queueCustomized: Boolean,
        val currentQueuePosition: Int?,
    )

    private var live: List<Row> = emptyList()
    private var baseline: List<QueueSlotRecord> = emptyList()
    private var cursor: Cursor? = null

    private fun positionOf(slotId: String?, previous: Cursor?): Int? {
        slotId ?: return null
        val index = live.indexOfFirst { it.record.slotId == slotId }
        if (index >= 0) return index
        return previous?.currentQueuePosition?.takeIf { previous.currentSlotId == slotId }
    }

    private fun apply(write: QueueWrite) {
        val previous = cursor
        when (write) {
            is QueueWrite.Unified -> {
                live = write.activeSlots.map { Row(it, write.extras[it.slotId]) }
                baseline = write.baselineSlots
                cursor = Cursor(
                    write.currentSlotId,
                    write.positionMs,
                    write.shuffleEnabled,
                    write.repeatMode,
                    write.shuffleReordered,
                    write.queueCustomized,
                    positionOf(write.currentSlotId, previous),
                )
            }

            is QueueWrite.QueueState -> {
                live = write.slots.map { Row(it, write.extras[it.slotId]) }
                cursor = Cursor(
                    write.currentSlotId,
                    write.positionMs,
                    previous?.shuffleEnabled ?: false,
                    previous?.repeatMode ?: 0,
                    write.shuffleReordered ?: previous?.shuffleReordered ?: false,
                    write.queueCustomized ?: previous?.queueCustomized ?: false,
                    positionOf(write.currentSlotId, previous),
                )
            }

            is QueueWrite.Baseline -> baseline = write.slots

            is QueueWrite.Cursor -> cursor = Cursor(
                write.currentSlotId,
                write.positionMs,
                previous?.shuffleEnabled ?: false,
                previous?.repeatMode ?: 0,
                previous?.shuffleReordered ?: false,
                previous?.queueCustomized ?: false,
                positionOf(write.currentSlotId, previous),
            )

            is QueueWrite.Shuffle -> cursor = previous?.copy(shuffleEnabled = write.enabled)
                ?: Cursor(null, 0L, write.enabled, 0, false, false, null)

            is QueueWrite.Repeat -> cursor = previous?.copy(repeatMode = write.mode)
                ?: Cursor(null, 0L, false, write.mode, false, false, null)
        }
    }

    /** The saved queue and baseline as the repository reads them; [songs] resolves song ids. */
    fun read(
        songs: Map<String, Song>,
        missingSongIds: Set<String> = emptySet(),
        deletedSongIds: Set<String> = emptySet(),
    ): Pair<QueueSnapshot, List<QueueSlot>> {
        val storedRows = live.withIndex().filter { (_, row) -> row.record.songId !in deletedSongIds }
        fun resolve(record: QueueSlotRecord): QueueSlot? =
            songs[record.songId]?.takeIf { record.songId !in missingSongIds }?.let { QueueSlot(record.slotId, it) }
        val stored = cursor
        val snapshot = QueueSnapshot(
            slots = storedRows.mapNotNull { (_, row) -> resolve(row.record) },
            storedSlotCount = storedRows.size,
            cursor = stored?.let {
                QueueCursor(
                    it.currentSlotId,
                    it.positionMs,
                    it.shuffleEnabled,
                    it.repeatMode,
                    it.shuffleReordered,
                    it.queueCustomized,
                )
            },
            extras = storedRows.mapNotNull { (_, row) -> row.tag?.let { row.record.slotId to it } }.toMap(),
            storedPositions = storedRows.associate { (index, row) -> row.record.slotId to index },
            currentQueuePosition = stored?.currentQueuePosition,
        )
        val baselineSlots = baseline.filter { it.songId !in deletedSongIds }.mapNotNull(::resolve)
        return snapshot to baselineSlots
    }

    companion object {
        /** The tables after [writes], applied in order from empty tables. */
        fun after(writes: List<QueueWrite>): QueueDiskImage = QueueDiskImage().apply { writes.forEach(::apply) }
    }
}
