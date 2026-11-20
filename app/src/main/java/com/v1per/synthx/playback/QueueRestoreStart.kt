package com.v1per.synthx.playback

/** Where a restored queue starts: an index into its surviving slots and a playback position. */
internal data class RestoreStart(val index: Int, val positionMs: Long)

/**
 * The start of a restored queue. [slotIds] are the surviving slots in their stored live order,
 * [storedPositions] the stored queue position of every saved live row, [currentSlotId] and
 * [currentQueuePosition] the saved cursor.
 *  - The saved slot survives: it starts at [savedPositionMs].
 *  - It does not survive and a queue position is saved: the first survivor stored at or after that
 *    position starts, or, when none is, the last survivor before it; always at 0 ms.
 *  - No position is saved (a cursor saved without the column, or no current slot): the first
 *    survivor starts at 0 ms.
 * Null when nothing survives. Slot identity only: duplicate songs in other slots never match.
 */
internal fun resolveRestoreStart(
    slotIds: List<String>,
    storedPositions: Map<String, Int>,
    currentSlotId: String?,
    currentQueuePosition: Int?,
    savedPositionMs: Long,
): RestoreStart? {
    if (slotIds.isEmpty()) return null
    val saved = slotIds.indexOf(currentSlotId)
    if (currentSlotId != null && saved >= 0) return RestoreStart(saved, savedPositionMs)
    if (currentQueuePosition == null) return RestoreStart(0, 0L)
    val atOrAfter = slotIds.indexOfFirst { (storedPositions[it] ?: Int.MIN_VALUE) >= currentQueuePosition }
    if (atOrAfter >= 0) return RestoreStart(atOrAfter, 0L)
    val before = slotIds.indexOfLast { (storedPositions[it] ?: Int.MAX_VALUE) < currentQueuePosition }
    return RestoreStart(before.coerceAtLeast(0), 0L)
}
