package com.v1per.synthx.playback

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.v1per.synthx.R

/** Same id media3's [DefaultMediaNotificationProvider] would otherwise auto-generate — pinned
 *  explicitly so the channel survives a Builder reconfiguration without silently forking into
 *  a second channel the user would have to notice and re-grant permissions for. */
private const val PLAYBACK_CHANNEL_ID = "synthx_playback"

class SynthXPlaybackService : MediaLibraryService() {

    private lateinit var player: ExoPlayer
    private lateinit var mediaLibrarySession: MediaLibrarySession

    override fun onCreate() {
        super.onCreate()

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
            .build()

        mediaLibrarySession = MediaLibrarySession.Builder(
            this,
            player,
            object : MediaLibrarySession.Callback {},
        ).build()

        // Branded channel name/id instead of media3's generated default ("Now playing"), so
        // the channel the user sees in system notification settings actually reads as synthX.
        // The small icon and play/pause/skip actions are left on media3's own default
        // provider — a proper monochrome status-bar icon asset doesn't exist yet in res/
        // (ic_launcher_foreground is full-color, not status-bar-safe), and custom actions
        // aren't something this pass has a concrete design for. Revisit once a real status-bar
        // icon is designed.
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(PLAYBACK_CHANNEL_ID)
                .setChannelName(R.string.playback_channel_name)
                .build(),
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession =
        mediaLibrarySession

    override fun onDestroy() {
        mediaLibrarySession.release()
        player.release()
        super.onDestroy()
    }
}
