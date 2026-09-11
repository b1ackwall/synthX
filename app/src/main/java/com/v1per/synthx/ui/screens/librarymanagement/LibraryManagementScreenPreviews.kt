package com.v1per.synthx.ui.screens.librarymanagement

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.v1per.synthx.ui.theme.Background
import com.v1per.synthx.ui.theme.SynthXTheme

@Preview(name = "Library Management — folder selected", showBackground = true)
@Composable
private fun LibraryManagementWithFolderPreview() {
    SynthXTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Background,
        ) {
            LibraryManagementScreenContent(
                folderPath = "content://com.android.externalstorage/tree/primary:Music",
                folderError = null,
                newArrivalsFolderPath = "content://com.android.externalstorage/tree/primary:Download/",
                newArrivalsFolderError = null,
                rescanStatus = "Done — 12 added, 1 updated, 0 missing",
                isScanning = false,
                scanProgress = null,
                missingSongCount = 3,
                deleteSongsEnabled = false,
                onPickFolder = {},
                onPickNewArrivalsFolder = {},
                onRescan = {},
                onCancelScan = {},
                onIssuesClick = {},
                onReplaceSongClick = {},
                onToggleDeleteSongs = {},
            )
        }
    }
}

@Preview(name = "Library Management — no folder", showBackground = true)
@Composable
private fun LibraryManagementEmptyPreview() {
    SynthXTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Background,
        ) {
            LibraryManagementScreenContent(
                folderPath = null,
                folderError = null,
                newArrivalsFolderPath = null,
                newArrivalsFolderError = null,
                rescanStatus = null,
                isScanning = false,
                scanProgress = null,
                missingSongCount = 0,
                deleteSongsEnabled = false,
                onPickFolder = {},
                onPickNewArrivalsFolder = {},
                onRescan = {},
                onCancelScan = {},
                onIssuesClick = {},
                onReplaceSongClick = {},
                onToggleDeleteSongs = {},
            )
        }
    }
}

@Preview(name = "Library Management — scanning", showBackground = true)
@Composable
private fun LibraryManagementScanningPreview() {
    SynthXTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Background,
        ) {
            LibraryManagementScreenContent(
                folderPath = "content://com.android.externalstorage/tree/primary:Music",
                folderError = null,
                newArrivalsFolderPath = null,
                newArrivalsFolderError = null,
                rescanStatus = "Scanning… 240 of 512",
                isScanning = true,
                scanProgress = ScanProgress(current = 240, total = 512),
                missingSongCount = 0,
                deleteSongsEnabled = false,
                onPickFolder = {},
                onPickNewArrivalsFolder = {},
                onRescan = {},
                onCancelScan = {},
                onIssuesClick = {},
                onReplaceSongClick = {},
                onToggleDeleteSongs = {},
            )
        }
    }
}
