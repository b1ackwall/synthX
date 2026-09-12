package com.v1per.synthx.data.repository

import android.util.Log
import com.v1per.synthx.data.backup.RestoreCoordinator
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.retryWhen

/** The single admission, lifetime-tracking, and restore-quiescence boundary for live Room Flows. */
internal object RepositoryDbFlow {
    fun <T> observe(upstream: () -> Flow<T>): Flow<T> = flow {
        // The DAO Flow is deliberately created only after admission. A terminal/preparing process
        // therefore cannot even construct, subscribe, or reconnect a live Room observer.
        val permit = RestoreCoordinator.acquireReactiveOperation(
            checkNotNull(coroutineContext[Job]),
        )
        try {
            emitAll(upstream())
        } finally {
            permit.release()
        }
    }

    suspend fun quiesceForRestore() = RestoreCoordinator.quiesceReactiveOperations()

    private const val INITIAL_BACKOFF_MS = 500L
    private const val MAX_BACKOFF_MS = 30_000L

    private fun backoffDelayFor(attempt: Long): Long {
        // attempt is 0-indexed; shift left capped well before overflow (attempt is never allowed
        // to grow unbounded in practice since the multiplier saturates at MAX_BACKOFF_MS anyway).
        val shift = attempt.coerceAtMost(6L).toInt()
        return (INITIAL_BACKOFF_MS shl shift).coerceAtMost(MAX_BACKOFF_MS)
    }

    /**
     * Retries a failing upstream indefinitely with exponential backoff (500ms, 1s, 2s, ... capped
     * at 30s), so a transient DAO read failure resubscribes to the upstream flow instead of
     * permanently ending it. Never retries [CancellationException] — including one thrown by
     * [RestoreCoordinator.acquireReactiveOperation] rejecting admission during restore — so a
     * cancelled [repositoryJob][SongRepositoryImpl] can still interrupt an in-progress backoff
     * delay cleanly rather than this operator fighting the cancellation.
     */
    fun <T> Flow<T>.retryOnFailure(tag: String, flowName: String): Flow<T> = retryWhen { cause, attempt ->
        if (cause is CancellationException) return@retryWhen false
        Log.w(tag, "$flowName upstream failure (attempt $attempt) — retrying", cause)
        delay(backoffDelayFor(attempt))
        true
    }
}
