package com.v1per.synthx.data.backup

import androidx.documentfile.provider.DocumentFile
import com.v1per.synthx.data.db.SynthXDatabase

// BackupManager's reads and pruning of what is already in the backup folder.

/**
 * Takes the root as a parameter so tests can supply a real, file-backed [DocumentFile]
 * (as [SafBackupPublisherInstrumentedTest] already does via `DocumentFile.fromFile`) instead
 * of needing a genuine SAF tree grant, which `DocumentFile.fromTreeUri` requires.
 */
internal fun BackupManager.classifyBackupList(root: DocumentFile?): BackupListResult {
    // A revoked grant or a provider that has gone away can make even `.isDirectory` throw,
    // not just return false — this is the same distrust-the-provider posture as the
    // `runCatching` around `listFiles()` below.
    val safeRoot = root?.takeIf { runCatching { it.isDirectory }.getOrDefault(false) }
        ?: return BackupListResult.FolderUnreachable

    var anyTierUnreadable = false
    val backups = BackupTier.entries.flatMap { tier ->
        val directory = runCatching { safeRoot.findFile(tier.folderName) }.getOrNull()
            ?.takeIf { runCatching { it.isDirectory }.getOrDefault(false) }
        if (directory == null) {
            anyTierUnreadable = true
            return@flatMap emptyList()
        }
        val children = runCatching { directory.listFiles().toList() }
            .onFailure { anyTierUnreadable = true }
            .getOrDefault(emptyList())
        val nameCounts = children.groupingBy { it.name.orEmpty() }.eachCount()
        val byName = children.associateBy { it.name.orEmpty() }
        val completedByDatabaseName = completedPairs(children, tier)
            .associateBy { it.databaseName }

        children
            .filter { document ->
                document.isFile &&
                    document.name?.let(BackupFileNames::isCompletedDatabaseName) == true
            }
            .map { databaseDocument ->
                val name = databaseDocument.name.orEmpty()
                val stem = name.removeSuffix(".db")
                val completePair = completedByDatabaseName[name]
                val metadataDocument = completePair?.metadataDocument?.document
                    ?: byName["$stem.json"]
                val metadata = completePair?.metadata
                    ?: metadataDocument?.let { readMetadata(it.uri) }
                val schemaSupported = metadata?.let { value ->
                    runCatching {
                        value.dbVersion <= SynthXDatabase.VERSION &&
                            BackupSchemaContracts.forVersion(value.dbVersion)?.hasMigrationPathToCurrent == true
                    }.getOrDefault(false)
                } == true
                val eligible = completePair != null && schemaSupported
                val ineligibleReason = if (eligible) {
                    null
                } else {
                    classifyIneligibility(
                        metadata = metadata,
                        actualSizeBytes = databaseDocument.length(),
                        isDuplicateName = (nameCounts[name] ?: 0) > 1,
                        schemaSupported = schemaSupported,
                    ) ?: BackupIneligibilityReason.Unrecognized
                }
                BackupInfo(
                    databaseUri = databaseDocument.uri,
                    metadataUri = metadataDocument?.uri,
                    fileName = name,
                    tier = tier,
                    timestamp = metadata?.timestamp?.takeIf { it > 0 }
                        ?: BackupFileNames.parseTimestampMillis(name)
                        ?: databaseDocument.lastModified(),
                    reason = metadata?.reason?.takeIf { it.isNotBlank() },
                    sizeBytes = databaseDocument.length(),
                    restoreEligible = eligible,
                    restoreIneligibleReason = ineligibleReason,
                )
            }
    }.sortedByDescending { it.timestamp }

    return when {
        backups.isNotEmpty() -> BackupListResult.Success(backups)
        anyTierUnreadable -> BackupListResult.FolderUnreachable
        else -> BackupListResult.Empty
    }
}

/**
 * `internal`: a test seam so retention/orphan-sweep behavior can be exercised against a real,
 * file-backed [DocumentFile] tier directory (see [classifyBackupList]'s doc for why a genuine
 * SAF tree grant is unavailable in an instrumented test).
 */
internal fun BackupManager.enforceRetention(
    tierDirectory: DocumentFile,
    tier: BackupTier,
    protected: BackupFileIdentity,
) {
    val children = runCatching { tierDirectory.listFiles().toList() }.getOrDefault(emptyList())
    val outcome = BackupRetention.prune(
        completed = completedPairs(children, tier),
        limit = tier.retention,
        protectedDatabaseName = protected.databaseFileName,
    ) { pair ->
        // Attempt both sides independently and report what actually happened — a thrown
        // exception or a refused (Boolean false) SAF delete are both visible to the
        // caller instead of being discarded.
        val databaseDeleted = runCatching { pair.database.document.delete() }.getOrDefault(false)
        val metadataDeleted = runCatching { pair.metadataDocument.document.delete() }.getOrDefault(false)
        PairDeletionResult(databaseDeleted, metadataDeleted)
    }
    BackupOutcomeStore.recordRetentionOutcome(context, tier, outcome)

    // Folds orphan cleanup into this same directory listing rather than a separate
    // scan — reuses the DocumentFile handles already fetched above; deletions above are
    // reflected live since DocumentFile.exists()/isFile query the provider, not a cache.
    sweepOrphanHalves(children)
}

/**
 * Removes a stray database or metadata file that has no matching other half — either
 * left behind by a partial pruning deletion above, or from an earlier run that crashed
 * mid-publish/mid-prune. A stray database half is unrestorable through the supported UI (no
 * metadata pointing at it); a stray metadata half references bytes that no longer exist.
 * Neither can be "repaired" by re-pairing against a DIFFERENT orphan — pairing is purely
 * name-derived (see [CompletedBackupCatalog]), so if two files' stems matched they would
 * already form a complete pair and not be orphans in the first place. Safe removal is the
 * only real option.
 */
private fun sweepOrphanHalves(children: List<DocumentFile>) {
    val surviving = children.filter { it.isFile && it.exists() }
    val namesPresent = surviving.mapNotNull { it.name }.toSet()
    surviving.forEach { document ->
        val name = document.name ?: return@forEach
        when {
            BackupFileNames.isCompletedDatabaseName(name) -> {
                val metadataName = name.removeSuffix(".db") + ".json"
                if (metadataName !in namesPresent) {
                    runCatching { document.delete() }
                }
            }

            name.endsWith(".json") && !name.endsWith(".json.partial") -> {
                val databaseName = name.removeSuffix(".json") + ".db"
                if (databaseName !in namesPresent || !BackupFileNames.isCompletedDatabaseName(databaseName)) {
                    runCatching { document.delete() }
                }
            }
        }
    }
}

internal fun BackupManager.completedPairs(
    children: List<DocumentFile>,
    tier: BackupTier,
): List<CompletedBackup<DocumentFile>> = CompletedBackupCatalog.enumerate(
    tier = tier,
    artifacts = children.map { document ->
        BackupArtifact(
            document = document,
            name = document.name.orEmpty(),
            isFile = document.isFile,
            sizeBytes = document.length(),
        )
    },
    readMetadata = { document -> readMetadata(document.uri) },
)
