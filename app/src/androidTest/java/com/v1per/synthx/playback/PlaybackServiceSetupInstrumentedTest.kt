package com.v1per.synthx.playback

import android.app.NotificationManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.R
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What [SynthXPlaybackService] sets up for the system: music audio attributes, pausing when another
 * player takes audio focus, the branded notification channel, and the release every engine gets
 * before a restore. (Pausing on "becoming noisy" and the wake lock are not observable from a test;
 * see the manual smoke checklist in slice1-implementation.md.)
 */
@RunWith(AndroidJUnit4::class)
class PlaybackServiceSetupInstrumentedTest : PlaybackServiceTestBase() {

    private val recorder = RecordingQueueRepository()

    override fun createPersistence(): QueuePersistence = QueuePersistence(recorder)

    @Test fun theSessionPlaysMusicWithMediaUsage() {
        val attributes = onMain { extraController.audioAttributes }
        assertEquals(C.USAGE_MEDIA, attributes.usage)
        assertEquals(C.AUDIO_CONTENT_TYPE_MUSIC, attributes.contentType)
    }

    /** Another player taking audio focus pauses playback, and the pause saves the resume point. */
    @Test fun losingAudioFocusPausesPlaybackAndSavesTheResumePoint() {
        startPlaying()
        val slot = onMain { engine.currentSlot()!!.slotId }
        val audio = context.getSystemService(AudioManager::class.java)
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
            .setOnAudioFocusChangeListener { }
            .build()
        recorder.writes.clear()
        assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, audio.requestAudioFocus(request))
        try {
            await("paused by the focus loss") { onMain { !extraController.isPlaying && !extraController.playWhenReady } }
            await("cursor saved") { recorder.writesOf<QueueWrite.Cursor>().any { it.currentSlotId == slot } }
        } finally {
            audio.abandonAudioFocusRequest(request)
        }
    }

    /** Restore stops playback and cuts every engine off from the session before it swaps the database. */
    @Test fun releaseBeforeARestoreStopsPlaybackAndDisconnectsTheEngine() {
        startPlaying()

        runBlocking { ExoPlayerEngine.releaseAllForRestore() }

        await("session stopped") { onMain { !extraController.isPlaying && extraController.playbackState == Player.STATE_IDLE } }
        assertNull(onMain { engine.currentSongId.now() })
        onMain { engine.resume() }
        settle()
        assertFalse("a released engine can no longer start playback", onMain { extraController.isPlaying })
    }

    /**
     * The notification channel is created under its pinned id with the app's name. The channel is
     * deleted first (only possible while the service is not in the foreground) so a renamed id
     * cannot pass on a channel left over from an earlier run.
     */
    @Test fun playbackCreatesTheBrandedNotificationChannel() {
        releaseEngine()
        releaseExtraController()
        PlaybackServiceLifecycle.stopAndAwaitDestroyed(context)
        val notifications = context.getSystemService(NotificationManager::class.java)
        notifications.deleteNotificationChannel(CHANNEL_ID)
        assertNull("precondition: channel deleted", notifications.getNotificationChannel(CHANNEL_ID))

        engine = newEngine()
        // Not the base's controller any more (that one is released), so this test releases it.
        extraController = connectExtraController()
        try {
            startPlaying()

            await("channel created") { notifications.getNotificationChannel(CHANNEL_ID) != null }
            assertEquals(context.getString(R.string.playback_channel_name), notifications.getNotificationChannel(CHANNEL_ID).name)
        } finally {
            onMain { extraController.release() }
        }
    }

    private companion object {
        const val CHANNEL_ID = "synthx_playback"
    }
}
