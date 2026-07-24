package com.v1per.synthx

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
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
                MainScaffold()
            }
        }
    }
}
