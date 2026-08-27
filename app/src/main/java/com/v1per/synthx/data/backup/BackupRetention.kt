package com.v1per.synthx.data.backup

/** Provider-neutral facts used to derive trustworthy final DB + metadata pairs. */
internal data class BackupArtifact<T>(
    val document: T,
    val name: String,
    val isFile: Boolean,
    val sizeBytes: Long,
)

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

internal object BackupRetention {
    /**
     * Deletes deterministic oldest unprotected pairs until [limit] is met. The publication name
     * is explicit authority; timestamps and provider ordering can never select it as a victim.
     */
    fun <T> prune(
        completed: List<CompletedBackup<T>>,
        limit: Int,
        protectedDatabaseName: String,
        deletePair: (CompletedBackup<T>) -> Unit,
    ) {
        require(limit > 0)
        val excess = (completed.size - limit).coerceAtLeast(0)
        completed.asSequence()
            .filterNot { it.databaseName == protectedDatabaseName }
            .sortedWith(compareBy<CompletedBackup<T>>({ it.timestamp }, { it.databaseName }))
            .take(excess)
            .forEach { pair -> runCatching { deletePair(pair) } }
    }
}
