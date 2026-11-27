package com.v1per.synthx.ui.screens.issues

import com.v1per.synthx.data.backup.BackupAttemptOutcome
import com.v1per.synthx.data.backup.ExportAttemptOutcome
import com.v1per.synthx.data.backup.VerificationAttemptOutcome
import com.v1per.synthx.data.repository.MissingSongInfo

/**
 * Live missing-song/safety signals and one cached missing-art aggregate.
 * See IssuesViewModel for filtering and ordering.
 */
sealed class Issue {
    data class MissingSong(val info: MissingSongInfo) : Issue()
    data class BackupUnhealthy(val outcome: BackupAttemptOutcome) : Issue()
    data class ExportUnhealthy(val outcome: ExportAttemptOutcome) : Issue()
    data class VerificationFailed(val outcome: VerificationAttemptOutcome) : Issue()
    data class DestructiveActionsSinceBackup(val count: Int) : Issue()
    data object BackupFolderGrantLost : Issue()

    /**
     * Startup reconciliation acted on [count] destructive operations (delete/replace/graduate)
     * left mid-flight by a process death, or a replace whose old audio file is still on disk, per
     * [com.v1per.synthx.data.db.PendingCleanupEntity]. Purely informational, with no action: it
     * makes the startup work visible. A delete record kept because its file is still on disk is
     * counted again on every launch until the file is gone.
     */
    data class ReconciledPendingCleanups(val count: Int, val timestamp: Long) : Issue()

    /**
     * One or more songs whose art file(s) are gone from disk despite their row still pointing
     * at them — e.g. after an uninstall/reinstall/restore cycle, since album art is deliberately
     * excluded from backups. See [com.v1per.synthx.data.scanner.ArtRecovery].
     */
    data class MissingArt(val songIds: List<String>) : Issue()
}
