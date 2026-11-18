package com.v1per.synthx.data.repository

/**
 * How a session extra entered the queue: a slot added with Play Next or Add to Queue while shuffle
 * is on. It lives in the live order only, outside the baseline, until shuffle turns off and places
 * it by kind. [storedValue] is the `queue_items.extra_kind` value.
 */
enum class QueueExtraKind(val storedValue: Int) {
    PlayNext(1),
    AddToQueue(2),
    ;

    companion object {
        /** The kind stored as [value], or null for an ordinary slot or an unknown value. */
        fun fromStoredValue(value: Int?): QueueExtraKind? = entries.firstOrNull { it.storedValue == value }
    }
}

/**
 * The tag a session extra carries: its [kind] and a [sequence] number that grows with every extra
 * added, so shuffle OFF can put the newest Play Next first and the oldest Add to Queue first.
 */
data class QueueExtraTag(val kind: QueueExtraKind, val sequence: Long)
