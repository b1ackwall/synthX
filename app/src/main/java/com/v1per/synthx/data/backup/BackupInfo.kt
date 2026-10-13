package com.v1per.synthx.data.backup

import android.net.Uri

/** Backup tiers, each retained independently in its own subdirectory. */
enum class BackupTier(val folderName: String, val retention: Int) {
    Daily("Daily", 20),
    Auto("Auto", 30),
    Manual("Manual", 20),
}

/** Reason strings recorded in the sidecar for [BackupTier.Auto] snapshots. */
object BackupReason {
    const val MIGRATION = "migration"
    const val FOLDER_CHANGE = "folder_change"
    const val DELETE_SONG = "delete_song"
    const val DELETE_PLAYLIST = "delete_playlist"
    const val REPLACE_SONG = "replace_song"
    const val RESCAN = "rescan"
    const val PRE_RESTORE_SAFETY = "pre_restore_safety"
}

/** Read-only content counts for a candidate backup (P4.3 restore preview), never a live query. */
data class BackupPreviewCounts(
    val playlistCount: Int,
    val songCount: Int,
    val playlistEntryCount: Int,
)

data class BackupInfo(
    val databaseUri: Uri,
    val metadataUri: Uri?,
    val fileName: String,
    val tier: BackupTier,
    val timestamp: Long,
    val reason: String?,
    val sizeBytes: Long,
    val restoreEligible: Boolean = false,
    val restoreIneligibleReason: BackupIneligibilityReason? = null,
)

class BackupNotConfiguredException : IllegalStateException("No backup folder selected")
