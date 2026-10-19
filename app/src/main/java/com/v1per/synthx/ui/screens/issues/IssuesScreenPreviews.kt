package com.v1per.synthx.ui.screens.issues

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.v1per.synthx.data.backup.BackupAttemptOutcome
import com.v1per.synthx.data.backup.BackupTier
import com.v1per.synthx.data.repository.MissingSongInfo
import com.v1per.synthx.ui.common.UiState
import com.v1per.synthx.ui.theme.Background
import com.v1per.synthx.ui.theme.SynthXTheme

@Preview(name = "Issues — Success", showBackground = true)
@Composable
private fun IssuesPreview() {
    val now = System.currentTimeMillis()
    SynthXTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Background,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                ScanStatusBar(
                    status = IssuesScanStatus.Idle(
                        lastScanTimestamp = now - 2 * DateUtils.HOUR_IN_MILLIS,
                        issueCount = 7,
                    ),
                    onScan = {},
                )
                IssuesContent(
                uiState = UiState.Success(
                    data = listOf(
                        Issue.BackupFolderGrantLost,
                        Issue.BackupUnhealthy(
                            BackupAttemptOutcome(
                                timestamp = now - 30 * DateUtils.MINUTE_IN_MILLIS,
                                tier = BackupTier.Daily,
                                reason = null,
                                succeeded = false,
                                failureMessage = "No space left on backup folder",
                            ),
                        ),
                        Issue.DestructiveActionsSinceBackup(count = 4),
                        Issue.MissingSong(
                            MissingSongInfo(
                                id = "1",
                                title = "Severed Link",
                                artist = "KNTRL",
                                missingSince = now - 2 * DateUtils.HOUR_IN_MILLIS,
                            ),
                        ),
                        Issue.MissingSong(
                            MissingSongInfo(
                                id = "2",
                                title = "Null Signal",
                                artist = "v1per",
                                missingSince = now - 3 * DateUtils.DAY_IN_MILLIS,
                            ),
                        ),
                        Issue.MissingSong(
                            MissingSongInfo(
                                id = "3",
                                title = "Ghost Partition",
                                artist = "Unknown Artist",
                                missingSince = now - 21 * DateUtils.DAY_IN_MILLIS,
                            ),
                        ),
                    ),
                ),
                deleteEnabled = true,
                deleteInProgress = false,
                onDelete = { _, _ -> },
                onFixMissingArt = {},
                onNavigateToBackups = {},
                modifier = Modifier.weight(1f),
            )
            }
        }
    }
}

/** Confirmed all-clear — note the copy differs from the scanning preview below. */
@Preview(name = "Issues — Empty", showBackground = true)
@Composable
private fun IssuesEmptyPreview() {
    SynthXTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Background,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                ScanStatusBar(
                    status = IssuesScanStatus.Idle(
                        lastScanTimestamp = System.currentTimeMillis() - DateUtils.DAY_IN_MILLIS,
                        issueCount = 0,
                    ),
                    onScan = {},
                )
                IssuesContent(
                    uiState = UiState.Empty,
                    deleteEnabled = true,
                    deleteInProgress = false,
                    onDelete = { _, _ -> },
                    onFixMissingArt = {},
                    onNavigateToBackups = {},
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** Checking, with no claim either way yet. */
@Preview(name = "Issues — Scanning", showBackground = true)
@Composable
private fun IssuesScanningPreview() {
    SynthXTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Background,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                ScanStatusBar(status = IssuesScanStatus.Scanning, onScan = {})
                IssuesContent(
                    uiState = UiState.Loading,
                    deleteEnabled = true,
                    deleteInProgress = false,
                    onDelete = { _, _ -> },
                    onFixMissingArt = {},
                    onNavigateToBackups = {},
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
