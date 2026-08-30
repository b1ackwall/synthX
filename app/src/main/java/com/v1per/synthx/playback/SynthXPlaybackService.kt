package com.v1per.synthx.playback

import android.content.Intent
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

    // ExoPlayer.release() has no internal double-release guard (unlike MediaSession, which
    // does) — calling it a second time is not safe. Both onTaskRemoved() and onDestroy() can
    // release the player, so this flag makes that idempotent regardless of which runs first.
    private var released = false

    override fun onCreate() {
        super.onCreate()

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            // Local-file player only (no network audio sources anywhere in the app) — CPU
            // wake lock is enough to keep decoding through screen-off/Doze, no wifi lock needed.
            .setWakeMode(C.WAKE_MODE_LOCAL)
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

    /**
     * Default media3 MediaSessionService behavior leaves an actively-playing session running
     * after the task is swiped from Recents. Product decision: synthX should stop playback and
     * tear the service down there instead, matching Spotify/YouTube Music free-tier behavior,
     * rather than continuing to play from a task the user just dismissed.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val index = player.currentMediaItemIndex
        if (index != C.INDEX_UNSET && player.mediaItemCount > 0) {
            // Read directly off the live player rather than any engine-side cache — this is
            // the actual live timeline, so it's the most correct snapshot to commit alongside
            // the cursor (see QueuePersistence.saveQueueState).
            //
            // QueuePersistence.create() is an app-process singleton (see its own kdoc), so
            // this is the SAME instance/writer any live ExoPlayerEngine already holds — not a
            // second, independently-ordered writer racing it. Without that, a still-draining
            // write from the engine's instance (e.g. a recent updateCursor) could land after
            // this fresher save and silently overwrite it (see general 6.7).
            val songIds = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
            QueuePersistence.create(this).saveQueueState(songIds, index, player.currentPosition)
        }
        releasePlayback()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        releasePlayback()
        super.onDestroy()
    }

    private fun releasePlayback() {
        if (released) return
        released = true
        player.stop()
        player.release()
        mediaLibrarySession.release()
    }
}
