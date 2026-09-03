package com.v1per.synthx.ui.screens.issues

import com.v1per.synthx.data.backup.BackupAttemptOutcome
import com.v1per.synthx.data.backup.ExportAttemptOutcome
import com.v1per.synthx.data.backup.VerificationAttemptOutcome
import com.v1per.synthx.data.repository.MissingSongInfo

/**
 * One entry on the broadened Issues screen. The five safety-net variants (everything but
 * [MissingSong]) are surfaced from BackupOutcomeStore/DestructiveActionTracker/
 * BackupFolderLaunchCheck — see IssuesViewModel for the filtering that turns a "healthy" source
 * emission into the absence of an Issue rather than into a variant here.
 */
sealed class Issue {
    data class MissingSong(val info: MissingSongInfo) : Issue()
    data class BackupUnhealthy(val outcome: BackupAttemptOutcome) : Issue()
    data class ExportUnhealthy(val outcome: ExportAttemptOutcome) : Issue()
    data class VerificationFailed(val outcome: VerificationAttemptOutcome) : Issue()
    data class DestructiveActionsSinceBackup(val count: Int) : Issue()
    data object BackupFolderGrantLost : Issue()

    /**
     * One or more songs whose art file(s) are gone from disk despite their row still pointing
     * at them — e.g. after an uninstall/reinstall/restore cycle, since album art is deliberately
     * excluded from backups. See [com.v1per.synthx.data.scanner.ArtRecovery].
     */
    data class MissingArt(val songIds: List<String>) : Issue()
}
