package com.v1per.synthx.data.repository

import com.v1per.synthx.data.backup.RestoreCoordinator
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

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
}
