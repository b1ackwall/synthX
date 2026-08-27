package com.v1per.synthx.data.repository

import com.v1per.synthx.data.backup.RestoreCoordinator

/** Shared production wrapper for finite repository and developer database actions. */
internal object RepositoryDbOperation {
    suspend fun <T> run(block: suspend () -> T): T = RestoreCoordinator.withDbOperation(block)
}
