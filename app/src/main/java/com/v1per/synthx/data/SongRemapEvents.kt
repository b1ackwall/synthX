package com.v1per.synthx.data

import com.v1per.synthx.data.repository.Song
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** A song row was replaced under a new id; [newSong] is what now lives at [oldId]'s old spot. */
data class SongRemap(val oldId: String, val newSong: Song)

/**
 * Process-wide, fire-and-forget notice that a scan replaced a song row under a new id while
 * the old id may still be held in memory elsewhere — currently just
 * [com.v1per.synthx.playback.ExoPlayerEngine.songsById], which has no other way to learn its
 * cached [Song] for the currently-playing (or queued) item just went stale.
 *
 * A singleton bus rather than a constructor-injected dependency because the emitting side
 * ([com.v1per.synthx.data.scanner.FolderScanner]) runs from a WorkManager Worker with no path
 * back to whatever playback engine instance — if any — is currently alive. No subscriber means
 * the event is simply dropped, same as any other scan result nothing is currently observing.
 */
object SongRemapEvents {
    private val _events = MutableSharedFlow<SongRemap>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<SongRemap> = _events.asSharedFlow()

    fun notifyRemap(oldId: String, newSong: Song) {
        _events.tryEmit(SongRemap(oldId, newSong))
    }
}
