package com.v1per.synthx

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.v1per.synthx.playback.ExoPlayerEngine
import com.v1per.synthx.playback.PlaybackController
import com.v1per.synthx.playback.PlaybackViewModel
import com.v1per.synthx.ui.MainScaffold
import com.v1per.synthx.ui.theme.SynthXTheme

class MainActivity : ComponentActivity() {

    // Engine choice is made here — swap ExoPlayerEngine for any PlaybackEngine implementation
    private val playbackViewModel: PlaybackViewModel by viewModels {
        PlaybackViewModel.Factory(
            PlaybackController(ExoPlayerEngine(applicationContext))
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SynthXTheme {
                NotificationPermissionEffect()
                MainScaffold()
            }
        }
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
