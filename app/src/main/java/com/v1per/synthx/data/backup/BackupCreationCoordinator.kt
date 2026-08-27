package com.v1per.synthx.data.backup

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One process-wide lock shared by every transient BackupManager instance. */
internal object BackupCreationCoordinator {
    private val mutex = Mutex()

    suspend fun <T> serialize(block: suspend () -> T): T = mutex.withLock { block() }
}
