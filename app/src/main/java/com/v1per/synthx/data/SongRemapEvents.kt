package com.v1per.synthx.data

import com.v1per.synthx.data.repository.Song
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * The song a consumer holding [oldId] should now be holding instead.
 *
 * `newSong.id` is usually a *different* id from [oldId] (a rescan replaced the row), but it is
 * legitimately the **same** id on graduation's path-only change — see the second emitter in
 * [SongRemapEvents]' KDoc. Consumers must therefore not assume the two differ; matching slots
 * by `it.id == oldId` and overwriting them with [newSong] is correct either way.
 *
 * [revision] is this event's stamp from [com.v1per.synthx.data.PlaybackCacheRevision] — see that
 * object's kdoc for why a global counter, not a tombstone, is what closes the cross-bus ordering
 * gap. [notifyRemap] always stamps a fresh one at the moment of publish, ignoring
 * whatever this constructor was called with — producers that build a `SongRemap` early and queue
 * it for a later, post-commit flush (see [com.v1per.synthx.data.scanner.FolderScanner]'s
 * `pendingRemaps`) are only ever holding [oldId]/[newSong] that way; the default here exists
 * purely so those call sites don't need to invent a meaningless placeholder themselves.
 */
data class SongRemap(val oldId: String, val newSong: Song, val revision: Long = UNPUBLISHED_REVISION) {
    internal companion object {
        const val UNPUBLISHED_REVISION = -1L
    }
}

/**
 * Process-wide notice that the [Song] a consumer cached under [SongRemap.oldId] no longer
 * describes what is actually in the library — currently only
 * [com.v1per.synthx.playback.ExoPlayerEngine.songsBySlotId], which has no other way to learn its
 * cached [Song] for the currently-playing (or queued) item just went stale, and which bakes that
 * cached `filePath` into every `MediaItem` it builds.
 *
 * Two distinct emitters, both legitimate:
 *  - **A rescan replaced the row under a new id** ([com.v1per.synthx.data.scanner.FolderScanner]).
 *    ID3 tags were edited without touching the byte ranges the content hash covers, so the row
 *    is reinserted under a new id with refreshed title/art. `oldId != newSong.id`.
 *  - **Graduation moved the file** ([com.v1per.synthx.data.scanner.graduateSong] — the
 *    "Remove from Starred" action, which physically moves a file out of New Arrivals into the
 *    root library folder). The bytes are copied verbatim and hash-verified, so the content id is
 *    unchanged and only `file_path` moves: **`oldId == newSong.id`**. That is not a degenerate
 *    case to guard against — it is the whole point. The row, and every queue slot, playlist
 *    membership and FK hanging off it, stays alive under the same id; what went stale is the
 *    path, and a consumer still holding the old one is pointing at a file graduation has already
 *    deleted.
 *
 * The two share one bus rather than splitting because the required handling is identical —
 * refresh the cached [Song] for every slot matching [SongRemap.oldId], swap the URI of any
 * `MediaItem` already loaded for it, keep every slot key — which is exactly what distinguishes
 * this from [SongDeletionEvents], where slots are *removed* and the playback cursor can move.
 *
 * A singleton bus rather than a constructor-injected dependency because the emitting side
 * ([com.v1per.synthx.data.scanner.FolderScanner]) runs from a WorkManager Worker with no path
 * back to whatever playback engine instance — if any — is currently alive.
 *
 * A missing subscriber does NOT leave events buffered for one to appear later. `replay = 0` here (the
 * default, never overridden) means a subscriber that starts collecting after an event was
 * emitted sees nothing from before it subscribed — [BUFFER_CAPACITY]/[BufferOverflow.SUSPEND]
 * below only smooth a burst against an *already-subscribed* slow collector, they do not retain
 * history for a future one. A live engine that misses an emission (no subscriber yet, or its
 * collector coroutine was down) has no way to learn about it from this bus alone; recovering from
 * that is exactly why every live-engine cache also gets independently re-verified against the
 * database (see [com.v1per.synthx.data.PlaybackCacheRevision] and
 * [com.v1per.synthx.playback.ExoPlayerEngine]'s `onStart`/`onResume`-driven reconciliation pass)
 * rather than relying solely on this bus ever delivering.
 *
 * [BUFFER_CAPACITY] is sized for a burst of retags within a single scan pass — plenty for the
 * realistic case (a handful of ID3-edited files in one pass) without being unbounded. Overflow
 * uses [BufferOverflow.SUSPEND] rather than DROP_OLDEST: once every remap in a pass is legitimate
 * (nothing here is speculative or superseded the way, say, a StateFlow's latest-value-wins model
 * assumes), dropping one silently reintroduces the exact staleness bug this event exists to
 * prevent. [notifyRemap] is called from the scanner's own coroutine after its transaction
 * commits — and from graduation's own coroutine after its DB write lands, outside its
 * `RepositoryDbOperation` permit — so suspending there briefly (only if 64 remaps are already
 * unconsumed) costs nothing a caller depends on, and never suspends while holding a DB permit.
 */
object SongRemapEvents {
    private const val BUFFER_CAPACITY = 64

    private val _events = MutableSharedFlow<SongRemap>(
        extraBufferCapacity = BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    val events: SharedFlow<SongRemap> = _events.asSharedFlow()

    suspend fun notifyRemap(oldId: String, newSong: Song) {
        _events.emit(SongRemap(oldId, newSong, PlaybackCacheRevision.next()))
    }
}
