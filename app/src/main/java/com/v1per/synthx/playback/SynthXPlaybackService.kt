package com.v1per.synthx.playback

import android.content.Intent
import android.util.Log
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
private const val TAG = "SynthXPlaybackService"

class SynthXPlaybackService : MediaLibraryService() {

    private lateinit var player: ExoPlayer

    // What the session actually drives: [player] wrapped so notification / headset / lock-screen
    // Next and Previous follow the app's full-queue rules instead of media3's window-only ones
    // (P9-T3). Everything in this service that inspects or stops playback still uses [player].
    private lateinit var sessionPlayer: SystemNavigationPlayer
    private lateinit var mediaLibrarySession: MediaLibrarySession

    // ExoPlayer.release() has no internal double-release guard (unlike MediaSession, which
    // does) — calling it a second time is not safe. Both onTaskRemoved() and onDestroy() can
    // release the player, so this flag makes that idempotent regardless of which runs first.
    private var released = false

    // Tracks how many of THIS APP's own controllers are currently bound — never system UI /
    // notification / Bluetooth-AVRCP controllers, which connect under a different package name
    // and legitimately want the session to keep running (showing transport controls) even while
    // no ExoPlayerEngine owns it. The one ExoPlayerEngine per app process is the only real
    // "owner" this needs to track (P4-T3 / R3-F08): when it drops to zero after having been
    // positive at least once, the only component that maintained the queue/window/event
    // subscriptions is gone, and playback should stop rather than run unmaintained — matching
    // the existing onTaskRemoved precedent below of stopping rather than continuing ownerless
    // playback. The counting itself lives in OwnerControllerTracker so it's unit-testable
    // without a real MediaSession.ControllerInfo.
    private val ownerControllers = OwnerControllerTracker()

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")

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

        sessionPlayer = SystemNavigationPlayer(player)

        mediaLibrarySession = MediaLibrarySession.Builder(
            this,
            sessionPlayer,
            object : MediaLibrarySession.Callback {
                override fun onPostConnect(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                ) {
                    if (controller.packageName == packageName) {
                        ownerControllers.connected()
                    }
                }

                override fun onDisconnected(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                ) {
                    if (controller.packageName != packageName) return
                    if (ownerControllers.disconnected()) {
                        // No ExoPlayerEngine remains bound to this session — the only owner of
                        // queue maintenance and the remap/deletion event subscriptions is gone
                        // (P4-T3 / R3-F08). Stop rather than let a long queue keep running
                        // unmaintained until it hits the loaded window's edge.
                        Log.d(TAG, "Last owning controller disconnected; stopping playback")
                        player.stop()
                    }
                }
            },
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
        Log.d(TAG, "onTaskRemoved")
        val index = player.currentMediaItemIndex
        if (index != C.INDEX_UNSET && player.mediaItemCount > 0) {
            // Cursor only — deliberately NOT the queue structure. This used to iterate the
            // live player's timeline and persist it as the saved queue, which was correct
            // while the timeline held the whole queue. It no longer does: the timeline is a
            // bounded window (see ExoPlayerEngineWindow.kt), so writing it here would replace
            // a 2000-song saved queue with the ~51 songs that happened to be loaded — silent,
            // permanent data loss on every swipe-from-Recents. The full order is already
            // persisted by the engine on every structural change (see persistQueue); the only
            // thing that can still be stale by now is where playback got to.
            //
            // QueuePersistence.create() is an app-process singleton (see its own kdoc), so
            // this is the SAME instance/writer any live ExoPlayerEngine already holds — not a
            // second, independently-ordered writer racing it. Without that, a still-draining
            // write from the engine's instance (e.g. a recent updateCursor) could land after
            // this fresher save and silently overwrite it (see general 6.7).
            QueuePersistence.create(this).updateCursor(
                currentSlotId = player.currentMediaItem?.mediaId?.takeIf { it.isNotEmpty() },
                positionMs = player.currentPosition,
            )
        }
        releasePlayback()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    /**
     * P4-T7 / R3-F13: a system-initiated destroy with no preceding [onTaskRemoved] (e.g. the
     * service being stopped directly, without the task ever being swiped from Recents) was
     * previously never checkpointed at all. Guarded on [released] rather than unconditional:
     * when [onTaskRemoved] already ran first, it already checkpointed via this same
     * cursor-only path, and the player has already been stopped/released — reading position off
     * it again here would be reading post-stop state, not a meaningful "where playback actually
     * got to" snapshot, so this deliberately skips rather than risks a second, less trustworthy
     * write. When onDestroy fires independently (the actual gap this closes), [released] is
     * still false and the checkpoint below runs against the still-live player, exactly like
     * [onTaskRemoved]'s own.
     */
    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        if (shouldCheckpointOnDestroy(released, player.currentMediaItemIndex, player.mediaItemCount)) {
            QueuePersistence.create(this).updateCursor(
                currentSlotId = player.currentMediaItem?.mediaId?.takeIf { it.isNotEmpty() },
                positionMs = player.currentPosition,
            )
        }
        releasePlayback()
        super.onDestroy()
    }

    private fun releasePlayback() {
        if (released) return
        released = true
        sessionPlayer.detach()
        player.stop()
        player.release()
        mediaLibrarySession.release()
    }

}

/**
 * The pure decision behind [SynthXPlaybackService.onDestroy]'s checkpoint (P4-T7 / R3-F13),
 * split out so it is unit-testable without a real `Service`/`ExoPlayer` — matching the
 * `IdleRecoveryTest`/`playerErrorLogMessage` precedent for exactly this kind of extraction.
 * `released` covers the "did onTaskRemoved already checkpoint and tear the player down" case;
 * `mediaItemIndex`/`mediaItemCount` mirror onTaskRemoved's own "is there actually something to
 * checkpoint" guard.
 */
internal fun shouldCheckpointOnDestroy(released: Boolean, mediaItemIndex: Int, mediaItemCount: Int): Boolean =
    !released && mediaItemIndex != C.INDEX_UNSET && mediaItemCount > 0
