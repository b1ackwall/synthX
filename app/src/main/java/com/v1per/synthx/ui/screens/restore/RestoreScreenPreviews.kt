package com.v1per.synthx.ui.screens.restore

import android.text.format.DateUtils
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.net.toUri
import com.v1per.synthx.data.backup.BackupInfo
import com.v1per.synthx.data.backup.BackupListResult
import com.v1per.synthx.data.backup.BackupTier
import com.v1per.synthx.ui.common.UiState
import com.v1per.synthx.ui.theme.Background
import com.v1per.synthx.ui.theme.SynthXTheme
import android.net.Uri

@Preview(name = "Restore — Success", showBackground = true)
@Composable
private fun RestoreListPreview() {
    val now = System.currentTimeMillis()
    SynthXTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = Background) {
            RestoreContent(
                uiState = UiState.Success(
                    BackupListResult.Success(
                        listOf(
                            BackupInfo(
                                databaseUri = Uri.EMPTY,
                                metadataUri = null,
                                fileName = "synthx_backup_2026-08-06_143022.db",
                                tier = BackupTier.Manual,
                                timestamp = now - DateUtils.HOUR_IN_MILLIS,
                                reason = null,
                                sizeBytes = 2_400_000,
                                restoreEligible = true,
                            ),
                            BackupInfo(
                                databaseUri = "content://x/2".toUri(),
                                metadataUri = null,
                                fileName = "synthx_backup_2026-08-05_090114.db",
                                tier = BackupTier.Auto,
                                timestamp = now - DateUtils.DAY_IN_MILLIS,
                                reason = "delete_song",
                                sizeBytes = 2_390_000,
                                restoreEligible = true,
                            ),
                        ),
                    ),
                ),
                onSelect = {},
            )
        }
    }
}

@Preview(name = "Restore — Empty", showBackground = true)
@Composable
private fun RestoreEmptyPreview() {
    SynthXTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = Background) {
            RestoreContent(uiState = UiState.Success(BackupListResult.Empty), onSelect = {})
        }
    }
}
