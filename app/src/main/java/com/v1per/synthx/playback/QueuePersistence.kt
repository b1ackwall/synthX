package com.v1per.synthx.playback

import android.content.Context
import android.util.Log
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.data.repository.SongRepository
import com.v1per.synthx.data.repository.SongRepositoryImpl
import com.v1per.synthx.data.repository.AdmittedQueuePersistenceRepository
import com.v1per.synthx.data.backup.RestoreCoordinator
import com.v1per.synthx.preferences.PreferencesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap

/**
 * Everything the playback engine needs to survive a process death: the queue structure in
 * `queue_items`, and shuffle / repeat / index / position in SharedPreferences.
 *
 * Kept out of [ExoPlayerEngine] so the engine stays a media3 adapter rather than growing a
 * second job. Writes are fire-and-forget from the caller's perspective: the engine mutates
 * the timeline on the main thread and must not block on a DB round trip to do it. But
 * fire-and-forget must still land in the order it was fired — two overlapping mutations
 * (e.g. a reorder immediately followed by a remove) each independently launched on
 * [Dispatchers.IO] have no guaranteed completion order, since IO is a shared thread-pool
 * dispatcher, not one confined thread. Out-of-order completion is normally invisible (the
 * next mutation re-persists the correct state anyway) but is wrong if the process dies in
 * the exact window between two writes landing out of order. [writeQueue] plus the single
 * consumer in [init] forces every write to execute strictly in call order instead.
 */
class QueuePersistence(
    private val repository: SongRepository,
    private val preferences: PreferencesManager?,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val writeQueue = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    /** Serializes writer admission with channel closure. */
    private val admissionLock = Any()
    private var acceptingWrites = true
    private val writer: Job

    init {
        instances += this
        writer = scope.launch {
            for (write in writeQueue) {
                write()
            }
        }
        writer.invokeOnCompletion { instances.remove(this) }
    }

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
        val permit = runCatching { RestoreCoordinator.acquireOperation() }.getOrNull() ?: return
        synchronized(admissionLock) {
            if (!acceptingWrites) { permit.release(); return }
            val accepted = writeQueue.trySend {
                try {
                    require(repository is AdmittedQueuePersistenceRepository) {
                        "Queue persistence requires an already-admitted repository"
                    }
                    repository.setQueueSongsAlreadyAdmitted(ids)
                } catch (e: Exception) {
                    runCatching { Log.w(TAG, "Queue save failed: ${e.message}") }
                } finally {
                    permit.release()
                }
            }
            if (accepted.isFailure) permit.release()
        }
    }

    /**
     * Persists the baseline order — see [BaselineQueueItemEntity][com.v1per.synthx.data.db.BaselineQueueItemEntity]'s
     * kdoc for what this represents. Same fire-and-forget shape as [saveQueue]; callers
     * decide when a baseline mutation is worth persisting, this just writes it.
     */
    fun saveBaselineQueue(songs: List<Song>) {
        val ids = songs.map { it.id }
        val permit = runCatching { RestoreCoordinator.acquireOperation() }.getOrNull() ?: return
        synchronized(admissionLock) {
            if (!acceptingWrites) { permit.release(); return }
            val accepted = writeQueue.trySend {
                try {
                    require(repository is AdmittedQueuePersistenceRepository) {
                        "Queue persistence requires an already-admitted repository"
                    }
                    repository.setBaselineQueueSongsAlreadyAdmitted(ids)
                } catch (e: Exception) {
                    runCatching { Log.w(TAG, "Baseline queue save failed: ${e.message}") }
                } finally {
                    permit.release()
                }
            }
            if (accepted.isFailure) permit.release()
        }
    }

    fun savePlaybackState(index: Int, positionMs: Long) {
        requirePreferences().currentQueueIndex = index
        requirePreferences().currentPositionMs = positionMs
    }

    var shuffleEnabled: Boolean
        get() = requirePreferences().shuffleEnabled
        set(value) { requirePreferences().shuffleEnabled = value }

    /** media3's Player.REPEAT_MODE_* int, stored raw so no mapping is needed at either end. */
    var repeatMode: Int
        get() = requirePreferences().repeatMode
        set(value) { requirePreferences().repeatMode = value }

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

        val savedIndex = requirePreferences().currentQueueIndex
        val savedId = savedIds.getOrNull(savedIndex)
        val resolvedIndex = songs.indexOfFirst { it.id == savedId }

        return RestoredQueue(
            songs = songs,
            // The song that was playing is gone: resume from the top of what is left
            // rather than from an index that now means a different song.
            startIndex = if (resolvedIndex >= 0) resolvedIndex else 0,
            positionMs = if (resolvedIndex >= 0) requirePreferences().currentPositionMs else 0L,
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

    /** Stops admission, drains accepted writes in FIFO order, then permanently closes Room use. */
    suspend fun quiesceForRestore() {
        synchronized(admissionLock) {
            if (acceptingWrites) {
                acceptingWrites = false
                writeQueue.close()
            }
        }
        writer.join()
        instances.remove(this)
    }

    fun release() {
        synchronized(admissionLock) {
            acceptingWrites = false
            writeQueue.close()
        }
        // Do not cancel the single consumer: accepted commands own coordinator permits and
        // must finish their FIFO drain before this instance leaves the registry.
    }

    companion object {
        private const val TAG = "SynthXQueue"
        private val instances = ConcurrentHashMap.newKeySet<QueuePersistence>()

        /** Called by restore after admission is closed and before Room's close boundary. */
        suspend fun quiesceAllForRestore() {
            instances.toList().forEach { it.quiesceForRestore() }
        }

        // Manual DI entry point, matching SongRepositoryImpl.create
        fun create(context: Context): QueuePersistence {
            val appContext = context.applicationContext
            return QueuePersistence(
                repository = SongRepositoryImpl.create(appContext),
                preferences = PreferencesManager(appContext),
            )
        }

        internal fun activeInstanceCountForTest(): Int = instances.size
    }

    private fun requirePreferences(): PreferencesManager =
        requireNotNull(preferences) { "Playback preferences are unavailable" }
}
