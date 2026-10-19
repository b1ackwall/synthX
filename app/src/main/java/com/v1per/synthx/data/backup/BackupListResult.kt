package com.v1per.synthx.data.backup

/**
 * Why a listed backup can't be restored, one value per distinct cause found by
 * [BackupManager.listBackups]. Derived from data already read
 * while listing (metadata presence/contents, actual file size, display-name collisions, schema
 * contract lookup) rather than a value discarded elsewhere — nothing upstream already computes
 * this breakdown. [Unrecognized] is the residual bucket: [BackupManager]'s authoritative eligibility
 * check (a fully validated [CompletedBackup] pair with a supported schema) failed for a reason
 * none of the other cases identify, e.g. a structurally invalid metadata field.
 */
sealed interface BackupIneligibilityReason {
    data object MissingMetadata : BackupIneligibilityReason
    data class SizeMismatch(val expectedBytes: Long, val actualBytes: Long) : BackupIneligibilityReason
    data object DuplicateDisplayName : BackupIneligibilityReason
    data class UnsupportedSchema(val dbVersion: Int) : BackupIneligibilityReason
    data object Unrecognized : BackupIneligibilityReason
}

/**
 * Derives [BackupIneligibilityReason] from facts already on hand while listing a candidate —
 * pure and Android-free so it is unit-testable without a real SAF tree. Checked in this order:
 * a duplicate display name makes the entry ambiguous regardless of what its metadata claims;
 * missing metadata is worse than a stale one; a size mismatch is checked before schema support
 * since a corrupt/truncated file's schema claim can't be trusted either. Returns null when none
 * of these checks find fault — callers combine that with their own authoritative eligibility
 * check, since this function alone cannot see every validation [CompletedBackupCatalog] applies.
 */
internal fun classifyIneligibility(
    metadata: BackupMetadata?,
    actualSizeBytes: Long,
    isDuplicateName: Boolean,
    schemaSupported: Boolean,
): BackupIneligibilityReason? = when {
    isDuplicateName -> BackupIneligibilityReason.DuplicateDisplayName

    metadata == null -> BackupIneligibilityReason.MissingMetadata

    metadata.sizeBytes != null && metadata.sizeBytes != actualSizeBytes ->
        BackupIneligibilityReason.SizeMismatch(metadata.sizeBytes, actualSizeBytes)

    !schemaSupported -> BackupIneligibilityReason.UnsupportedSchema(metadata.dbVersion)

    else -> null
}

/**
 * The outcome of listing candidate backups ([BackupManager.listBackups]) — distinguishes
 * situations that would otherwise all look like one identical empty list: no folder chosen
 * yet, a folder that can't currently be read (missing/renamed tier directories or a provider
 * listing failure — merged into one case because both mean the same thing to the user: "we can't
 * see what's in the folder you picked," with the same remedy), a folder that reads fine but
 * genuinely holds nothing, and an actual list.
 */
sealed interface BackupListResult {
    data object NoFolderConfigured : BackupListResult
    data object FolderUnreachable : BackupListResult
    data object Empty : BackupListResult
    data class Success(val backups: List<BackupInfo>) : BackupListResult
}

/** For callers that only want "whatever's there, or nothing" without caring why. */
internal fun BackupListResult.backupsOrEmpty(): List<BackupInfo> =
    (this as? BackupListResult.Success)?.backups.orEmpty()
