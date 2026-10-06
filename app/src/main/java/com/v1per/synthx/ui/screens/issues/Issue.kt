package com.v1per.synthx.ui.screens.issues

import com.v1per.synthx.data.backup.BackupAttemptOutcome
import com.v1per.synthx.data.backup.ExportAttemptOutcome
import com.v1per.synthx.data.backup.VerificationAttemptOutcome
import com.v1per.synthx.data.repository.MissingSongInfo

/**
 * Live missing-song/safety signals and two cached artwork-related aggregates.
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
     * P3-T1: startup reconciliation found and resolved [count] destructive operations
     * (delete/replace/graduate) left mid-flight by a process death, per
     * [com.v1per.synthx.data.db.PendingCleanupEntity]. Purely informational — the cleanup already
     * happened, this just makes it visible rather than silently invisible.
     */
    data class ReconciledPendingCleanups(val count: Int, val timestamp: Long) : Issue()

    /**
     * One or more songs whose art file(s) are gone from disk despite their row still pointing
     * at them — e.g. after an uninstall/reinstall/restore cycle, since album art is deliberately
     * excluded from backups. See [com.v1per.synthx.data.scanner.ArtRecovery].
     */
    data class MissingArt(val songIds: List<String>) : Issue()

    // DEAD CODE: the Issues MissingPalette item is retired. Delete in a cleanup pass. Ask  before deleting.
    // /** Songs with an art URI and available audio, but no stored background swatches. */
    // data class MissingPalette(val songIds: List<String>) : Issue()
}
