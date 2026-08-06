package com.v1per.synthx

import android.Manifest
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
import com.v1per.synthx.ui.MainScaffold
import com.v1per.synthx.ui.theme.SynthXTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        verifyLibraryOnLaunch()
        enableEdgeToEdge()
        setContent {
            SynthXTheme {
                NotificationPermissionEffect()
                MainScaffold()
            }
        }
    }

    // Launch does the cheap pass only: flag songs whose files have gone. The full scan
    // stays user-triggered from Library Management. Tied to lifecycleScope deliberately —
    // this is short, silent, and not worth surviving the Activity.
    private fun verifyLibraryOnLaunch() {
        val repository = SongRepositoryImpl.create(applicationContext)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val newlyMissing = repository.verifyLibrary()
                Log.d(TAG, "Launch verify complete: $newlyMissing newly missing")
            } catch (e: Exception) {
                Log.e(TAG, "Launch verify failed", e)
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
