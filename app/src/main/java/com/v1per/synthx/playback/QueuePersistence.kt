package com.v1per.synthx.playback

import android.content.Context
import android.util.Log
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.data.repository.SongRepository
import com.v1per.synthx.data.repository.SongRepositoryImpl
import com.v1per.synthx.preferences.PreferencesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Everything the playback engine needs to survive a process death: the queue structure in
 * `queue_items`, and shuffle / repeat / index / position in SharedPreferences.
 *
 * Kept out of [ExoPlayerEngine] so the engine stays a media3 adapter rather than growing a
 * second job. Writes are fire-and-forget on an IO scope: the engine mutates the timeline on
 * the main thread and must not block on a DB round trip to do it.
 */
class QueuePersistence(
    private val repository: SongRepository,
    private val preferences: PreferencesManager,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * A queue read back from storage. [songs] can be shorter than what was saved — songs
     * deleted since the last session take their queue rows with them via FK CASCADE, and
     * songs flagged missing are filtered out by the query.
     */
    data class RestoredQueue(
        val songs: List<Song>,
        val startIndex: Int,
        val positionMs: Long,
        val droppedCount: Int,
    )

    fun saveQueue(songs: List<Song>) {
        val ids = songs.map { it.id }
        scope.launch {
            try {
                repository.setQueueSongs(ids)
            } catch (e: Exception) {
                // A queue that fails to persist costs the user a restore, not a session.
                Log.w(TAG, "Queue save failed: ${e.message}")
            }
        }
    }

    /**
     * Persists the baseline order — see [BaselineQueueItemEntity][com.v1per.synthx.data.db.BaselineQueueItemEntity]'s
     * kdoc for what this represents. Same fire-and-forget shape as [saveQueue]; callers
     * decide when a baseline mutation is worth persisting, this just writes it.
     */
    fun saveBaselineQueue(songs: List<Song>) {
        val ids = songs.map { it.id }
        scope.launch {
            try {
                repository.setBaselineQueueSongs(ids)
            } catch (e: Exception) {
                Log.w(TAG, "Baseline queue save failed: ${e.message}")
            }
        }
    }

    fun savePlaybackState(index: Int, positionMs: Long) {
        preferences.currentQueueIndex = index
        preferences.currentPositionMs = positionMs
    }

    var shuffleEnabled: Boolean
        get() = preferences.shuffleEnabled
        set(value) { preferences.shuffleEnabled = value }

    /** media3's Player.REPEAT_MODE_* int, stored raw so no mapping is needed at either end. */
    var repeatMode: Int
        get() = preferences.repeatMode
        set(value) { preferences.repeatMode = value }

    /**
     * Null when there is nothing to restore: a fresh install, or a saved queue whose songs
     * have all gone. The saved index is re-found by song id rather than reused as a number,
     * so dropped entries shift the queue without shifting what resumes.
     */
    suspend fun loadQueue(): RestoredQueue? {
        val savedIds = try {
            repository.getQueueSongIds()
        } catch (e: Exception) {
            Log.w(TAG, "Queue load failed: ${e.message}")
            return null
        }
        if (savedIds.isEmpty()) return null

        val songs = repository.getQueueSongs()
        if (songs.isEmpty()) {
            Log.i(TAG, "Saved queue of ${savedIds.size} no longer resolves to any song")
            return null
        }

        val savedIndex = preferences.currentQueueIndex
        val savedId = savedIds.getOrNull(savedIndex)
        val resolvedIndex = songs.indexOfFirst { it.id == savedId }

        return RestoredQueue(
            songs = songs,
            // The song that was playing is gone: resume from the top of what is left
            // rather than from an index that now means a different song.
            startIndex = if (resolvedIndex >= 0) resolvedIndex else 0,
            positionMs = if (resolvedIndex >= 0) preferences.currentPositionMs else 0L,
            droppedCount = savedIds.size - songs.size,
        )
    }

    /**
     * The saved baseline, resolved to songs and filtered the same way [loadQueue] filters
     * the active queue — a song deleted since the last session simply drops out. No
     * index/position bookkeeping here: the baseline is a plain ordered list, nothing reads
     * a "current position" out of it directly.
     */
    suspend fun loadBaselineQueue(): List<Song> {
        return try {
            repository.getBaselineQueueSongs()
        } catch (e: Exception) {
            Log.w(TAG, "Baseline queue load failed: ${e.message}")
            emptyList()
        }
    }

    fun release() {
        scope.cancel()
    }

    companion object {
        private const val TAG = "SynthXQueue"

        // Manual DI entry point, matching SongRepositoryImpl.create
        fun create(context: Context): QueuePersistence {
            val appContext = context.applicationContext
            return QueuePersistence(
                repository = SongRepositoryImpl.create(appContext),
                preferences = PreferencesManager(appContext),
            )
        }
    }
}
