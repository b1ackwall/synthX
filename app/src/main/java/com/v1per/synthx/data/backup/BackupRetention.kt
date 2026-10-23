package com.v1per.synthx.data.backup

/** Provider-neutral facts used to derive trustworthy final DB + metadata pairs. */
internal data class BackupArtifact<T>(val document: T, val name: String, val isFile: Boolean, val sizeBytes: Long)

internal data class CompletedBackup<T>(
    val database: BackupArtifact<T>,
    val metadataDocument: BackupArtifact<T>,
    val metadata: BackupMetadata,
) {
    val databaseName: String get() = database.name
    val timestamp: Long get() = metadata.timestamp
}

/** One definition of a complete pair for retention accounting and trusted restore listing. */
internal object CompletedBackupCatalog {
    fun <T> enumerate(
        tier: BackupTier,
        artifacts: List<BackupArtifact<T>>,
        readMetadata: (T) -> BackupMetadata?,
    ): List<CompletedBackup<T>> {
        val byName = artifacts.groupBy { it.name }
        return artifacts.mapNotNull { database ->
            if (!database.isFile || !BackupFileNames.isCompletedDatabaseName(database.name)) {
                return@mapNotNull null
            }
            // Duplicate provider display names are ambiguous and therefore not trusted.
            if (byName[database.name]?.size != 1) return@mapNotNull null
            val metadataName = database.name.removeSuffix(".db") + ".json"
            val metadataDocument = byName[metadataName]?.singleOrNull()
                ?.takeIf { it.isFile }
                ?: return@mapNotNull null
            val metadata = readMetadata(metadataDocument.document)
                ?: return@mapNotNull null
            val valid = runCatching {
                metadata.requireCompleteForPublication()
                require(metadata.file == database.name)
                require(metadata.tier == tier.name)
                require(metadata.sizeBytes == database.sizeBytes)
            }.isSuccess
            if (!valid) null else CompletedBackup(database, metadataDocument, metadata)
        }
    }
}

/** Per-victim outcome of attempting to delete both halves of a [CompletedBackup] pair. */
internal data class PairDeletionResult(val databaseDeleted: Boolean, val metadataDeleted: Boolean) {
    val complete: Boolean get() = databaseDeleted && metadataDeleted
    val orphaned: Boolean get() = databaseDeleted != metadataDeleted
}

/**
 * [BackupRetention.prune]'s accounting of what actually happened, so a caller can tell
 * "nothing needed pruning," "pruned cleanly," and "pruning attempted but some pair failed" apart
 * — the same three-way distinction [VerificationAttemptOutcome.skippedNoEligibleBackup] draws for
 * verification, applied here to retention.
 */
internal data class RetentionPruneOutcome(
    val attempted: Int,
    val succeeded: Int,
    /** Exactly one half of the pair was deleted — the survivor is now an untracked orphan. */
    val orphanedDatabaseNames: List<String>,
    /** Neither half of the pair could be deleted. */
    val failedDatabaseNames: List<String>,
) {
    val hadFailures: Boolean get() = orphanedDatabaseNames.isNotEmpty() || failedDatabaseNames.isNotEmpty()
}

internal object BackupRetention {
    /**
     * Deletes deterministic oldest unprotected pairs until [limit] is met. The publication name
     * is explicit authority; timestamps and provider ordering can never select it as a victim.
     *
     * [deletePair] reports what actually happened to each half. A thrown exception from it is
     * contained here (an uncooperative provider can't stop later victims from being attempted)
     * and folded into the returned [RetentionPruneOutcome] as a complete failure for that victim
     * rather than silently vanishing.
     */
    fun <T> prune(
        completed: List<CompletedBackup<T>>,
        limit: Int,
        protectedDatabaseName: String,
        deletePair: (CompletedBackup<T>) -> PairDeletionResult,
    ): RetentionPruneOutcome {
        require(limit > 0)
        val excess = (completed.size - limit).coerceAtLeast(0)
        val victims = completed.asSequence()
            .filterNot { it.databaseName == protectedDatabaseName }
            .sortedWith(compareBy<CompletedBackup<T>>({ it.timestamp }, { it.databaseName }))
            .take(excess)
            .toList()

        var succeeded = 0
        val orphaned = mutableListOf<String>()
        val failed = mutableListOf<String>()
        victims.forEach { pair ->
            val result = runCatching { deletePair(pair) }
                .getOrDefault(PairDeletionResult(databaseDeleted = false, metadataDeleted = false))
            when {
                result.complete -> succeeded++
                result.orphaned -> orphaned += pair.databaseName
                else -> failed += pair.databaseName
            }
        }
        return RetentionPruneOutcome(
            attempted = victims.size,
            succeeded = succeeded,
            orphanedDatabaseNames = orphaned,
            failedDatabaseNames = failed,
        )
    }
}
