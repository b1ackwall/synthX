package com.v1per.synthx

import android.Manifest
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.lifecycleScope
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.v1per.synthx.data.repository.SongRepositoryImpl
import com.v1per.synthx.playback.ExoPlayerEngine
import com.v1per.synthx.playback.PlaybackController
import com.v1per.synthx.playback.PlaybackViewModel
import com.v1per.synthx.preferences.PreferencesManager
import com.v1per.synthx.ui.MainScaffold
import com.v1per.synthx.ui.theme.SynthXTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    // Engine choice is made here — swap ExoPlayerEngine for any PlaybackEngine implementation
    private val playbackViewModel: PlaybackViewModel by viewModels {
        PlaybackViewModel.Factory(
            PlaybackController(ExoPlayerEngine(applicationContext))
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Touch playbackViewModel before setContent so it is in the Activity's ViewModelStore
        // before MainScaffold calls viewModel<PlaybackViewModel>() without a factory.
        playbackViewModel
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
        val uriString = PreferencesManager(applicationContext).musicFolderUri ?: return
        val repository = SongRepositoryImpl.create(applicationContext)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val result = repository.scanLibrary(Uri.parse(uriString))
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
