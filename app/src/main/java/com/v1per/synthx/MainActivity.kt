package com.v1per.synthx

import android.Manifest
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.lifecycleScope
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.v1per.synthx.data.repository.SongRepositoryImpl
import com.v1per.synthx.preferences.PreferencesManager
import com.v1per.synthx.ui.MainScaffold
import com.v1per.synthx.ui.theme.SynthXTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scanLibraryOnLaunch()
        enableEdgeToEdge()
        setContent {
            SynthXTheme {
                NotificationPermissionEffect()
                MainScaffold()
            }
        }
    }

    // Fire-and-forget: UI reads the DB via Flow immediately, scan results stream in
    private fun scanLibraryOnLaunch() {
        val preferences = PreferencesManager(applicationContext)
        val uriString = preferences.musicFolderUri ?: return
        val uri = Uri.parse(uriString)

        // Validate grant is still active — a stored URI survives a backup restore or a
        // revoked grant, but scanning without it silently flags the library missing.
        val grantValid = contentResolver.persistedUriPermissions.any { permission ->
            permission.uri == uri && permission.isReadPermission
        }

        if (!grantValid) {
            // Clear stale URI — user will need to re-select folder in Settings
            preferences.musicFolderUri = null
            Log.w(TAG, "Music folder grant revoked — cleared stored URI")
            return
        }

        val repository = SongRepositoryImpl.create(applicationContext)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val result = repository.scanLibrary(uri)
                Log.d(TAG, "Launch scan complete: $result")
            } catch (e: Exception) {
                Log.e(TAG, "Launch scan failed", e)
            }
        }
    }

    private companion object {
        const val TAG = "SynthXScan"
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun NotificationPermissionEffect() {
    val permissionState = rememberPermissionState(Manifest.permission.POST_NOTIFICATIONS)
    LaunchedEffect(Unit) {
        if (!permissionState.status.isGranted) {
            permissionState.launchPermissionRequest()
        }
    }
}
