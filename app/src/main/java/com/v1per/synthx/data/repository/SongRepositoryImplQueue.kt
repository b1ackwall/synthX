package com.v1per.synthx.data.repository

import com.v1per.synthx.BuildConfig
import com.v1per.synthx.data.db.queryInBatches
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// SongRepositoryImpl's persisted play-queue reads and writes.

internal suspend fun SongRepositoryImpl.getQueueSlotsInternal(): List<QueueSlot> = dbOperation { withContext(Dispatchers.IO) {
    resolveQueueSlots(queueDao.getQueueItems().map { QueueSlotRecord(it.slotId, it.songId) })
} }

internal suspend fun SongRepositoryImpl.getQueueSnapshotInternal(): QueueSnapshot = dbOperation { withContext(Dispatchers.IO) {
    val (items, cursor) = queueDao.getQueueAndCursor()
    val records = items.map { QueueSlotRecord(it.slotId, it.songId) }
    QueueSnapshot(
        slots = resolveQueueSlots(records),
        storedSlotCount = records.size,
        cursor = cursor?.let {
            QueueCursor(
                currentSlotId = it.currentSlotId,
                positionMs = it.positionMs,
                shuffleEnabled = it.shuffleEnabled,
                repeatMode = it.repeatMode,
            )
        },
    )
} }

internal suspend fun SongRepositoryImpl.setQueueStateInternal(
    slots: List<QueueSlotRecord>,
    currentSlotId: String?,
    positionMs: Long,
) = withContext(Dispatchers.IO) {
    queueDao.replaceQueueAndCursor(slots, currentSlotId, positionMs)
    // P6.3: the one place both sides are now guaranteed to have landed together —
    // a violation here means replaceQueueAndCursor's transaction itself is broken,
    // never a legitimate runtime state, so this must never silently pass in debug.
    if (BuildConfig.DEBUG) {
        check(currentSlotId == null || slots.any { it.slotId == currentSlotId }) {
            "Playback cursor invariant violated: currentSlotId=$currentSlotId is not in " +
                "the ${slots.size} just-persisted queue slots"
        }
    }
}

internal suspend fun SongRepositoryImpl.setCursorAlreadyAdmittedInternal(
    currentSlotId: String?,
    positionMs: Long,
) = withContext(Dispatchers.IO) {
    queueDao.updateCursor(currentSlotId, positionMs)
}

internal suspend fun SongRepositoryImpl.setShuffleEnabledAlreadyAdmittedInternal(enabled: Boolean) = withContext(Dispatchers.IO) {
    queueDao.updateShuffleEnabled(enabled)
}

internal suspend fun SongRepositoryImpl.setRepeatModeAlreadyAdmittedInternal(mode: Int) = withContext(Dispatchers.IO) {
    queueDao.updateRepeatMode(mode)
}

internal suspend fun SongRepositoryImpl.getBaselineQueueSlotsInternal(): List<QueueSlot> = dbOperation { withContext(Dispatchers.IO) {
    resolveQueueSlots(queueDao.getBaselineQueueItems().map { QueueSlotRecord(it.slotId, it.songId) })
} }

internal suspend fun SongRepositoryImpl.setBaselineQueueSlotsInternal(slots: List<QueueSlotRecord>) = withContext(Dispatchers.IO) {
    queueDao.replaceBaselineQueue(slots)
}

internal suspend fun SongRepositoryImpl.getQueueSnapshotWithBaselineInternal(): Pair<QueueSnapshot, List<QueueSlot>> =
    dbOperation { withContext(Dispatchers.IO) {
        val (items, baselineItems, cursor) = queueDao.getFullQueueSnapshot()
        val records = items.map { QueueSlotRecord(it.slotId, it.songId) }
        val baselineRecords = baselineItems.map { QueueSlotRecord(it.slotId, it.songId) }
        val snapshot = QueueSnapshot(
            slots = resolveQueueSlots(records),
            storedSlotCount = records.size,
            cursor = cursor?.let {
                QueueCursor(
                    currentSlotId = it.currentSlotId,
                    positionMs = it.positionMs,
                    shuffleEnabled = it.shuffleEnabled,
                    repeatMode = it.repeatMode,
                )
            },
        )
        snapshot to resolveQueueSlots(baselineRecords)
    } }

internal suspend fun SongRepositoryImpl.setUnifiedQueueStateInternal(
    activeSlots: List<QueueSlotRecord>,
    baselineSlots: List<QueueSlotRecord>,
    currentSlotId: String?,
    positionMs: Long,
    shuffleEnabled: Boolean,
    repeatMode: Int,
) = withContext(Dispatchers.IO) {
    queueDao.replaceQueueBaselineAndCursor(
        activeSlots, baselineSlots, currentSlotId, positionMs, shuffleEnabled, repeatMode,
    )
    if (BuildConfig.DEBUG) {
        check(currentSlotId == null || activeSlots.any { it.slotId == currentSlotId }) {
            "Playback cursor invariant violated: currentSlotId=$currentSlotId is not in " +
                "the ${activeSlots.size} just-persisted active queue slots"
        }
    }
}

private suspend fun SongRepositoryImpl.resolveQueueSlots(records: List<QueueSlotRecord>): List<QueueSlot> {
    if (records.isEmpty()) return emptyList()
    val songIds = records.map { it.songId }.distinct()
    // Room expands each IN-list element to one SQLite bind parameter. Queues can contain
    // well over 999 entries, so hydrate in bounded batches instead of failing restore for
    // a large queue with "too many SQL variables".
    val details = songIds.queryInBatches(songDao::getSongsWithDetailsByIds)
    val refsBySongId = details.map { it.song.id }
        .queryInBatches(songDao::getSongArtistRefsForSongs)
        .groupBy { it.songId }
    val songsById = details
        .asSequence()
        .filterNot { it.song.isMissing }
        .associate { it.song.id to it.toDomain(refsBySongId[it.song.id] ?: emptyList()) }
    return records.mapNotNull { record ->
        songsById[record.songId]?.let { song -> QueueSlot(record.slotId, song) }
    }
}
