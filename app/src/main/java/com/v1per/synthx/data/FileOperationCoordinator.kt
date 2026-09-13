package com.v1per.synthx.data

import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex

/**
 * Mutual exclusion for the operations that physically move, delete or re-point a song's audio
 * file — graduation, delete, replace — against each other, against library scans, and against a
 * folder change revoking a SAF grant one of them is still using (P2-T1 / R2-F14).
 *
 * Deliberately separate from [com.v1per.synthx.data.backup.RestoreCoordinator]. That object is a
 * *global* barrier that only knows how many operations are live, so restore can drain them; this
 * one knows *which* song or root each operation is touching. The two compose rather than overlap:
 *
 * **Acquisition order** — always, on every path: backup creation (which awaits the restore
 * coordinator's finite drain) → song lock → restore-coordinator permit → root usage. Never await
 * a backup or a restore drain while holding a song lock: graduation holds a finite permit for its
 * whole duration, so a drain awaited under a song lock that graduation is queued on would never
 * finish.
 *
 * Three primitives:
 *
 *  - **Per-song lock** ([withSongLock] / [tryAcquireSong]). A [Mutex] per song id, kept in a map
 *    and **reference-counted**: an entry exists only while some caller holds or is waiting for
 *    it, and is removed the moment its count reaches zero. So the map is bounded by the number of
 *    songs being operated on *right now* (a handful at most), not by library size or history —
 *    no sweep, and no slow leak over a long process lifetime. Mutating operations suspend on it;
 *    scans only ever try it and skip the row on contention, so a long scan can never block a UI
 *    operation and no lock-ordering cycle between the two can form.
 *
 *  - **Mutation observation** ([beginObservation]). A lock alone can't protect a scan: a scan
 *    reads a file long before it commits the row, and a graduation or delete can start *and
 *    finish* inside that gap, leaving the lock free at commit time while the scan's data is stale.
 *    While at least one observation is open, every release of a mutating song lock stamps that
 *    song with a monotonic epoch; a scan's commit skips any song stamped after its observation
 *    began. Stamps are only kept while an observation is open and are cleared when the last one
 *    closes, so this map is bounded by what gets mutated during one scan.
 *
 *  - **Root usage** ([acquireRoot] / [retireRootWhenIdle]). A refcount per SAF tree, held by
 *    graduation against both trees it writes to. Releasing a folder's grant first marks the tree
 *    retiring (new users are refused), then waits for existing users to drain, and only then
 *    revokes. Keyed by [rootKey] so a document URI and the tree URI it lives under agree.
 */
internal object FileOperationCoordinator {
    private val lock = Any()

    internal class SongEntry {
        val mutex = Mutex()
        /** Holders plus waiters. The entry is removed from [songs] exactly when this hits 0. */
        var refs = 0
    }

    private val songs = HashMap<String, SongEntry>()

    private var observers = 0
    private var epoch = 0L
    private val mutatedAt = HashMap<String, Long>()

    private val rootUsers = HashMap<String, Int>()
    private val retiringRoots = HashSet<String>()
    private var rootsChanged = CompletableDeferred<Unit>()

    // ------------------------------------------------------------------------------ songs

    /**
     * Runs [block] holding [songId]'s lock, suspending until any other holder releases it. For
     * operations that mutate the song's file or row: graduation, delete, replace (old song).
     *
     * The mutation stamp is written *before* the mutex is unlocked, so there is no instant at
     * which a scan could take the lock yet still see the song as untouched.
     */
    suspend fun <T> withSongLock(songId: String, block: suspend () -> T): T {
        val entry = synchronized(lock) {
            songs.getOrPut(songId, ::SongEntry).also { it.refs += 1 }
        }
        try {
            entry.mutex.lock()
            try {
                return block()
            } finally {
                synchronized(lock) {
                    recordMutationLocked(songId)
                    entry.mutex.unlock()
                }
            }
        } finally {
            synchronized(lock) { dropRefLocked(songId, entry) }
        }
    }

    /** Non-suspending acquire for scans. Null means another operation holds the song right now. */
    fun tryAcquireSong(songId: String): SongLease? = synchronized(lock) {
        val entry = songs.getOrPut(songId, ::SongEntry)
        if (!entry.mutex.tryLock()) {
            // Locked implies a holder with refs > 0, so the entry stays; guarded anyway.
            if (entry.refs == 0) songs.remove(songId)
            return null
        }
        entry.refs += 1
        SongLease(songId, entry)
    }

    /** A scan's hold on one song. Releasing it records no mutation — scans are not mutators here. */
    class SongLease internal constructor(
        private val songId: String,
        private val entry: SongEntry,
    ) {
        private var released = false
        fun release() {
            synchronized(lock) {
                check(!released) { "Song lease for $songId released twice" }
                released = true
                entry.mutex.unlock()
                dropRefLocked(songId, entry)
            }
        }
    }

    /** Caller holds [lock]. */
    private fun dropRefLocked(songId: String, entry: SongEntry) {
        entry.refs -= 1
        check(entry.refs >= 0) { "Song lock refcount underflow for $songId" }
        if (entry.refs == 0 && songs[songId] === entry) songs.remove(songId)
    }

    /** Caller holds [lock]. */
    private fun recordMutationLocked(songId: String) {
        if (observers > 0) {
            epoch += 1
            mutatedAt[songId] = epoch
        }
    }

    // ------------------------------------------------------------------------ observation

    fun beginObservation(): Observation = synchronized(lock) {
        observers += 1
        Observation(epoch)
    }

    class Observation internal constructor(private val startEpoch: Long) {
        private var closed = false

        /** Whether a mutating operation released [songId] after this observation began. */
        fun touchedSince(songId: String): Boolean = synchronized(lock) {
            val stamp = mutatedAt[songId] ?: return false
            stamp > startEpoch
        }

        fun close() {
            synchronized(lock) {
                if (closed) return
                closed = true
                observers -= 1
                check(observers >= 0) { "File operation observer count underflow" }
                if (observers == 0) mutatedAt.clear()
            }
        }
    }

    // ------------------------------------------------------------------------------ roots

    /**
     * Registers a user of the tree [rootKey]. Null when that tree is being retired (its grant is
     * about to be released) — the caller must not start file I/O against it.
     */
    fun acquireRoot(rootKey: String): RootUsage? = synchronized(lock) {
        if (rootKey in retiringRoots) return null
        rootUsers[rootKey] = (rootUsers[rootKey] ?: 0) + 1
        RootUsage(rootKey)
    }

    class RootUsage internal constructor(private val rootKey: String) {
        private var released = false
        fun release() {
            synchronized(lock) {
                check(!released) { "Root usage for $rootKey released twice" }
                released = true
                val remaining = checkNotNull(rootUsers[rootKey]) - 1
                check(remaining >= 0) { "Root usage underflow for $rootKey" }
                if (remaining == 0) rootUsers.remove(rootKey) else rootUsers[rootKey] = remaining
                signalRootsChangedLocked()
            }
        }
    }

    /**
     * Closes [rootKey] to new users, waits until every current user has released it, then runs
     * [block] (the grant release) and reopens the key. A second retire of the same key waits for
     * the first to finish rather than interleaving with it.
     */
    suspend fun <T> retireRootWhenIdle(rootKey: String, block: () -> T): T {
        // Claim the retirement, queueing behind any retirement of the same key already running.
        while (true) {
            val waitFor = synchronized(lock) {
                if (rootKey in retiringRoots) {
                    rootsChanged
                } else {
                    retiringRoots += rootKey
                    null
                }
            } ?: break
            waitFor.await()
        }
        try {
            // New users are refused from here on; wait out the ones already registered.
            while (true) {
                val waitFor = synchronized(lock) {
                    if ((rootUsers[rootKey] ?: 0) == 0) null else rootsChanged
                } ?: break
                waitFor.await()
            }
            return block()
        } finally {
            synchronized(lock) {
                retiringRoots -= rootKey
                signalRootsChangedLocked()
            }
        }
    }

    /** Caller holds [lock]. */
    private fun signalRootsChangedLocked() {
        rootsChanged.complete(Unit)
        rootsChanged = CompletableDeferred()
    }

    /**
     * The key both a tree URI and any document URI inside that tree map to: provider authority
     * plus tree document id. Falls back to the URI string when [uri] is not tree-based (or when
     * running without an Android framework, where `DocumentsContract` is stubbed).
     */
    fun rootKey(uri: Uri): String {
        val authority = runCatching { uri.authority }.getOrNull()
        val treeId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
        return if (authority != null && treeId != null) "$authority|$treeId" else uri.toString()
    }

    // ------------------------------------------------------------------------------ tests

    internal fun songLockRefCountForTest(songId: String): Int =
        synchronized(lock) { songs[songId]?.refs ?: 0 }

    internal fun songLockEntryCountForTest(): Int = synchronized(lock) { songs.size }

    internal fun rootUserCountForTest(rootKey: String): Int =
        synchronized(lock) { rootUsers[rootKey] ?: 0 }

    /** JVM/instrumentation isolation only. */
    internal fun resetForTest() {
        synchronized(lock) {
            check(songs.isEmpty() && rootUsers.isEmpty() && retiringRoots.isEmpty()) {
                "Cannot reset file operation coordinator with live locks or root users"
            }
            observers = 0
            epoch = 0
            mutatedAt.clear()
            signalRootsChangedLocked()
        }
    }
}

/**
 * One scan's claims on the songs its commit touches. A claim is granted only when the song's lock
 * is free *and* no mutating operation has released it since the scan's observation began — the
 * second half is what catches a graduation or delete that started and finished between the scan
 * reading a file and committing its row. Granted leases are held until [close], i.e. across the
 * whole commit transaction, so nothing can mutate a claimed song before the scan's write lands.
 *
 * Not thread-safe by itself: used from the scan's single sequential write loop.
 */
internal class ScanRowClaims {
    private val observation = FileOperationCoordinator.beginObservation()
    private val held = HashMap<String, FileOperationCoordinator.SongLease>()
    private var closed = false

    /** Rows this scan skipped because a claim was refused — left for the next scan. */
    var deferred = 0
        private set

    /** True when the scan may write [songId]'s row this pass. */
    fun claim(songId: String): Boolean {
        check(!closed) { "Scan row claims used after close" }
        if (songId in held) return true
        // Lease first, then the stamp check: once the lease is held nothing can stamp the song,
        // so the check can't be invalidated between the two.
        val lease = FileOperationCoordinator.tryAcquireSong(songId)
        if (lease == null) {
            deferred += 1
            return false
        }
        if (observation.touchedSince(songId)) {
            lease.release()
            deferred += 1
            return false
        }
        held[songId] = lease
        return true
    }

    fun close() {
        if (closed) return
        closed = true
        held.values.forEach { it.release() }
        held.clear()
        observation.close()
    }
}

/** Opens [ScanRowClaims] for the duration of [block]. Inline so callers keep labeled returns. */
internal inline fun <T> withScanRowClaims(block: (ScanRowClaims) -> T): T {
    val claims = ScanRowClaims()
    try {
        return block(claims)
    } finally {
        claims.close()
    }
}
