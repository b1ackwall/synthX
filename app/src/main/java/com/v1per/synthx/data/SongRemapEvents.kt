package com.v1per.synthx.data

import com.v1per.synthx.data.repository.Song
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** A song row was replaced under a new id; [newSong] is what now lives at [oldId]'s old spot. */
data class SongRemap(val oldId: String, val newSong: Song)

/**
 * Process-wide notice that a scan replaced a song row under a new id while the old id may
 * still be held in memory elsewhere — currently just
 * [com.v1per.synthx.playback.ExoPlayerEngine.songsBySlotId], which has no other way to learn its
 * cached [Song] for the currently-playing (or queued) item just went stale.
 *
 * A singleton bus rather than a constructor-injected dependency because the emitting side
 * ([com.v1per.synthx.data.scanner.FolderScanner]) runs from a WorkManager Worker with no path
 * back to whatever playback engine instance — if any — is currently alive. No subscriber means
 * events just sit buffered until one shows up (or are trimmed per [BUFFER_CAPACITY] below),
 * same as any other scan result nothing is currently observing.
 *
 * [BUFFER_CAPACITY] is sized for a burst of retags within a single scan pass — plenty for the
 * realistic case (a handful of ID3-edited files in one pass) without being unbounded. Overflow
 * uses [BufferOverflow.SUSPEND] rather than DROP_OLDEST: once every remap in a pass is legitimate
 * (nothing here is speculative or superseded the way, say, a StateFlow's latest-value-wins model
 * assumes), dropping one silently reintroduces the exact staleness bug this event exists to
 * prevent. [notifyRemap] is called from the scanner's own coroutine after its transaction
 * commits, so suspending there briefly (only if 64 remaps are already unconsumed) costs nothing
 * a caller depends on.
 */
object SongRemapEvents {
    private const val BUFFER_CAPACITY = 64

    private val _events = MutableSharedFlow<SongRemap>(
        extraBufferCapacity = BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    val events: SharedFlow<SongRemap> = _events.asSharedFlow()

    suspend fun notifyRemap(oldId: String, newSong: Song) {
        _events.emit(SongRemap(oldId, newSong))
    }
}
