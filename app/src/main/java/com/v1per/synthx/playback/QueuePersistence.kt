package com.v1per.synthx.playback

import android.content.Context
import android.util.Log
import com.v1per.synthx.data.repository.SongRepository
import com.v1per.synthx.data.repository.SongRepositoryImpl
import com.v1per.synthx.data.repository.AdmittedQueuePersistenceRepository
import com.v1per.synthx.data.repository.QueueSlot
import com.v1per.synthx.data.repository.QueueSlotRecord
import com.v1per.synthx.data.backup.RestoreCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap

/**
 * Everything the playback engine needs to survive a process death: the queue structure,
 * resume cursor (index/position), and shuffle/repeat mode, all together in Room — see
 * [saveQueueState] — so a restore's whole-database-file swap carries every one of them
 * atomically (see data 2.4; shuffle/repeat previously lived in SharedPreferences, which a
 * restore never touches).
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
 *
 * That ordering guarantee only holds within one instance, so [create] hands out a single
 * app-process singleton rather than letting each caller build its own — otherwise a
 * ViewModel-owned [ExoPlayerEngine] and, say, the playback service reacting to a lifecycle
 * event could each get their own [writeQueue]/writer pair with no ordering between the two,
 * and a stale write from one could silently overwrite a fresher write from the other (see
 * general 6.7). The plain constructor stays public for tests that deliberately want isolated,
 * independently-draining instances.
 */
class QueuePersistence(
    private val repository: SongRepository,
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
        val slots: List<QueueSlot>,
        val startIndex: Int,
        val positionMs: Long,
        val droppedCount: Int,
        val shuffleEnabled: Boolean,
        val repeatMode: Int,
    )

    /**
     * Replaces the saved queue AND the resume cursor in one atomic commit — this is the only
     * write path for either, precisely so the two can never land independently. [slots] is
     * the full queue structure at the moment of the call, [currentSlotId]/[positionMs] the cursor
     * within it; every caller (a queue mutation, a pause, an app-backgrounded event, or a
     * restore rewrite) already has both in hand, so there is no case where only one is known.
     */
    fun saveQueueState(slots: List<QueueSlot>, currentSlotId: String?, positionMs: Long) {
        saveQueueRecords(
            slots.map { QueueSlotRecord(slotId = it.slotId, songId = it.song.id) },
            currentSlotId,
            positionMs,
        )
    }

    /** Service-side equivalent when the live MediaItems already carry both persisted ids. */
    internal fun saveQueueRecords(
        slots: List<QueueSlotRecord>,
        currentSlotId: String?,
        positionMs: Long,
    ) {
        val permit = runCatching { RestoreCoordinator.acquireOperation() }.getOrNull() ?: return
        synchronized(admissionLock) {
            if (!acceptingWrites) { permit.release(); return }
            val accepted = writeQueue.trySend {
                try {
                    require(repository is AdmittedQueuePersistenceRepository) {
                        "Queue persistence requires an already-admitted repository"
                    }
                    repository.setQueueState(slots, currentSlotId, positionMs)
                } catch (e: Exception) {
                    runCatching { Log.w(TAG, "Queue state save failed: ${e.message}") }
                } finally {
                    permit.release()
                }
            }
            if (accepted.isFailure) permit.release()
        }
    }

    /**
     * Cursor-only write — leaves the persisted queue structure untouched. Meant for call sites
     * where only the resume position moved and the queue itself is known not to have changed
     * (a track transition during ordinary playback), so as not to re-persist the whole queue
     * every few minutes for no reason. Goes through the same serialized [writeQueue] consumer
     * as [saveQueueState], so a cursor-only write and a full write can never race each other or
     * land out of order.
     */
    fun updateCursor(currentSlotId: String?, positionMs: Long) {
        val permit = runCatching { RestoreCoordinator.acquireOperation() }.getOrNull() ?: return
        synchronized(admissionLock) {
            if (!acceptingWrites) { permit.release(); return }
            val accepted = writeQueue.trySend {
                try {
                    require(repository is AdmittedQueuePersistenceRepository) {
                        "Queue persistence requires an already-admitted repository"
                    }
                    repository.setCursorAlreadyAdmitted(currentSlotId, positionMs)
                } catch (e: Exception) {
                    runCatching { Log.w(TAG, "Cursor update failed: ${e.message}") }
                } finally {
                    permit.release()
                }
            }
            if (accepted.isFailure) permit.release()
        }
    }

    /**
     * Resolves slot ids from a live player timeline back into [QueueSlot]s — used when reconnecting to
     * a still-running playback service, where no in-memory cache already holds them. Returns
     * an empty list on failure rather than throwing, matching [loadQueue]'s failure shape:
     * the caller treats "nothing resolved" the same as "nothing to resolve."
     */
    suspend fun resolveSlots(slotIds: List<String>): List<QueueSlot> {
        if (slotIds.isEmpty()) return emptyList()
        return try {
            val requested = slotIds.toHashSet()
            repository.getQueueSlots().filter { it.slotId in requested }
        } catch (e: Exception) {
            Log.w(TAG, "Timeline slot resolution failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * Persists the baseline order — see [BaselineQueueItemEntity][com.v1per.synthx.data.db.BaselineQueueItemEntity]'s
     * kdoc for what this represents. Same fire-and-forget shape as [saveQueueState]; callers
     * decide when a baseline mutation is worth persisting, this just writes it.
     */
    fun saveBaselineQueue(slots: List<QueueSlot>) {
        val records = slots.map { QueueSlotRecord(slotId = it.slotId, songId = it.song.id) }
        val permit = runCatching { RestoreCoordinator.acquireOperation() }.getOrNull() ?: return
        synchronized(admissionLock) {
            if (!acceptingWrites) { permit.release(); return }
            val accepted = writeQueue.trySend {
                try {
                    require(repository is AdmittedQueuePersistenceRepository) {
                        "Queue persistence requires an already-admitted repository"
                    }
                    repository.setBaselineQueueSlots(records)
                } catch (e: Exception) {
                    runCatching { Log.w(TAG, "Baseline queue save failed: ${e.message}") }
                } finally {
                    permit.release()
                }
            }
            if (accepted.isFailure) permit.release()
        }
    }

    /**
     * Shuffle-only write — leaves the persisted queue/cursor untouched. A shuffle toggle
     * doesn't coincide with either changing, so this is its own narrow write rather than
     * folded into [saveQueueState] or [updateCursor]; going through the same [writeQueue]
     * consumer as those two still keeps every write serialized in call order.
     */
    fun updateShuffleEnabled(enabled: Boolean) {
        val permit = runCatching { RestoreCoordinator.acquireOperation() }.getOrNull() ?: return
        synchronized(admissionLock) {
            if (!acceptingWrites) { permit.release(); return }
            val accepted = writeQueue.trySend {
                try {
                    require(repository is AdmittedQueuePersistenceRepository) {
                        "Queue persistence requires an already-admitted repository"
                    }
                    repository.setShuffleEnabledAlreadyAdmitted(enabled)
                } catch (e: Exception) {
                    runCatching { Log.w(TAG, "Shuffle mode update failed: ${e.message}") }
                } finally {
                    permit.release()
                }
            }
            if (accepted.isFailure) permit.release()
        }
    }

    /** Repeat-mode counterpart of [updateShuffleEnabled] — same narrow-write shape. */
    fun updateRepeatMode(mode: Int) {
        val permit = runCatching { RestoreCoordinator.acquireOperation() }.getOrNull() ?: return
        synchronized(admissionLock) {
            if (!acceptingWrites) { permit.release(); return }
            val accepted = writeQueue.trySend {
                try {
                    require(repository is AdmittedQueuePersistenceRepository) {
                        "Queue persistence requires an already-admitted repository"
                    }
                    repository.setRepeatModeAlreadyAdmitted(mode)
                } catch (e: Exception) {
                    runCatching { Log.w(TAG, "Repeat mode update failed: ${e.message}") }
                } finally {
                    permit.release()
                }
            }
            if (accepted.isFailure) permit.release()
        }
    }

    /**
     * Null when there is nothing to restore: a fresh install, or a saved queue whose songs
     * have all gone. The exact saved slot id is re-found after unavailable songs are filtered,
     * so dropped entries shift the queue without changing which occurrence resumes.
     */
    suspend fun loadQueue(): RestoredQueue? {
        val snapshot = try {
            repository.getQueueSnapshot()
        } catch (e: Exception) {
            Log.w(TAG, "Queue load failed: ${e.message}")
            return null
        }
        if (snapshot.storedSlotCount == 0) return null

        val slots = snapshot.slots
        if (slots.isEmpty()) {
            Log.i(TAG, "Saved queue of ${snapshot.storedSlotCount} no longer resolves to any song")
            return null
        }

        val cursor = snapshot.cursor
        val currentSlotId = cursor?.currentSlotId
        val resolvedIndex = slots.indexOfFirst { it.slotId == currentSlotId }
        return RestoredQueue(
            slots = slots,
            startIndex = if (resolvedIndex >= 0) resolvedIndex else 0,
            positionMs = if (resolvedIndex >= 0) (cursor?.positionMs ?: 0L) else 0L,
            droppedCount = snapshot.storedSlotCount - slots.size,
            shuffleEnabled = cursor?.shuffleEnabled ?: false,
            repeatMode = cursor?.repeatMode ?: 0,
        )
    }

    /**
     * The saved baseline, resolved to songs and filtered the same way [loadQueue] filters
     * the active queue — a song deleted since the last session simply drops out. No
     * index/position bookkeeping here: the baseline is a plain ordered list, nothing reads
     * a "current position" out of it directly.
     */
    suspend fun loadBaselineQueue(): List<QueueSlot> {
        return try {
            repository.getBaselineQueueSlots()
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

    /**
     * Permanently stops admission on THIS instance. Not called on ordinary engine/ViewModel
     * teardown any more — [create] hands out a shared app-process singleton now, so no single
     * owner tearing down should close it out from under whichever other component still holds
     * a reference. Kept for tests that construct a [QueuePersistence] directly and want to
     * exercise the closed-admission path in isolation.
     */
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

        @Volatile private var INSTANCE: QueuePersistence? = null

        /** Called by restore after admission is closed and before Room's close boundary. */
        suspend fun quiesceAllForRestore() {
            instances.toList().forEach { it.quiesceForRestore() }
        }

        // Manual DI entry point, matching SongRepositoryImpl.create's singleton pattern — a
        // true app-process singleton so there is exactly one writeQueue/writer for the whole
        // process, regardless of which component (a ViewModel-owned engine, or the playback
        // service reacting to onTaskRemoved) asks for it. Without this, two independent
        // instances could each accept writes with no ordering guarantee between them (see
        // general 6.7): the service used to build its own ad-hoc instance in onTaskRemoved,
        // which could race a still-draining write from the engine's own instance and silently
        // lose the fresher state. Like SongRepositoryImpl's own singleton, this is not
        // invalidated on restore — restore's terminal mode already requires a process restart,
        // which is what rebuilds it.
        fun create(context: Context): QueuePersistence {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: run {
                    val appContext = context.applicationContext
                    QueuePersistence(repository = SongRepositoryImpl.create(appContext)).also { INSTANCE = it }
                }
            }
        }

        internal fun activeInstanceCountForTest(): Int = instances.size
    }
}
