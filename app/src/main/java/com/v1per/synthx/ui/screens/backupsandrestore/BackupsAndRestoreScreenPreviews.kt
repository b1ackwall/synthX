package com.v1per.synthx.ui.screens.backupsandrestore

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.v1per.synthx.ui.theme.Background
import com.v1per.synthx.ui.theme.SynthXTheme

@Preview(name = "Backups and Restore", showBackground = true)
@Composable
private fun BackupsAndRestorePreview() {
    SynthXTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Background,
        ) {
            BackupsAndRestoreScreenContent(
                backupFolderPath = "Internal Storage/Documents/synthX",
                backupFolderError = null,
                backupStatus = "Backup created",
                onPickBackupFolder = {},
                onBackupNow = {},
                onRestoreClick = {},
            )
        }
    }
}
