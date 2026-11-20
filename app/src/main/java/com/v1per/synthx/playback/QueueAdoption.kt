package com.v1per.synthx.playback

import com.v1per.synthx.data.repository.QueueSnapshot

/**
 * The saved read an engine adopts a running session from, with the slot the session is playing put
 * back when the read left it out because its song is flagged missing: the queue row and the song row
 * are both still there ([QueueSnapshot.missingSlots]), so the slot comes back from them, among the
 * surviving slots at its stored position, or first when no position is stored. Only the playing slot
 * comes back; other missing slots stay dropped, as a cold restore drops them.
 *
 * Unchanged (the same instance) when [playingSlotId] is null, already survives, or has no saved row
 * at all (its song row is gone, and its queue rows with it): the caller then starts from the slot the
 * cold restore would start on. Pure, so the placement is unit-testable.
 */
internal fun QueueSnapshot.withPlayingSlotAdopted(playingSlotId: String?): QueueSnapshot {
    if (playingSlotId == null || slots.any { it.slotId == playingSlotId }) return this
    val playing = missingSlots.firstOrNull { it.slotId == playingSlotId } ?: return this
    val position = storedPositions[playingSlotId]
    val index = if (position == null) {
        0
    } else {
        slots.count { (storedPositions[it.slotId] ?: Int.MIN_VALUE) < position }
    }
    return copy(
        slots = slots.toMutableList().apply { add(index, playing) },
        missingSlots = missingSlots.filterNot { it.slotId == playingSlotId },
    )
}
