package com.v1per.synthx.playback

import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.media3.common.MediaItem
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real [SynthXPlaybackService]'s two exit checkpoints, driven through the real Android
 * lifecycle: removing the app's task from Recents (`finishAndRemoveTask`, which makes the system
 * call `onTaskRemoved`) and the system destroying the service once nothing is bound to it.
 *
 * The service is shared by the whole process, so each test starts from a destroyed service and
 * ends with this test's own instance destroyed; no second instance ever exists. The checkpoints
 * write through [SynthXPlaybackService.exitPersistenceOverride], pointed at a recorder.
 *
 * Items are loaded through a controller flagged as the media-notification controller, so it is not
 * an owner and releasing it does not trigger the owner gate's stop.
 */
@RunWith(AndroidJUnit4::class)
class PlaybackServiceExitInstrumentedTest : PlaybackServiceTestBase() {

    override val createEngineInSetUp = false
    override val connectExtraControllerInSetUp = false

    private val recorder = RecordingQueueRepository()

    override fun createPersistence(): QueuePersistence = QueuePersistence(recorder)

    @Before fun startFromADestroyedService() {
        PlaybackServiceLifecycle.stopAndAwaitDestroyed(context)
        SynthXPlaybackService.exitPersistenceOverride = persistence
    }

    @After fun endWithThisTestsServiceDestroyed() {
        // Throws (caught) when the test never connected a controller.
        if (runCatching { onMain { extraController.isConnected } }.getOrDefault(false)) releaseExtraController()
        PlaybackServiceLifecycle.stopAndAwaitDestroyed(context)
        SynthXPlaybackService.exitPersistenceOverride = null
    }

    /** Loads [size] items into the service's player at [current]/[positionMs], paused. */
    private fun loadItems(size: Int, current: Int, positionMs: Long) {
        extraController = connectExtraController(notificationFlagHints())
        onMain {
            extraController.setMediaItems(
                (0 until size).map {
                    MediaItem.Builder().setMediaId("slot-$it").setUri(Uri.fromFile(wav)).build()
                },
                current,
                positionMs,
            )
        }
        await("$size items loaded at $current") {
            onMain { extraController.mediaItemCount == size && extraController.currentMediaItemIndex == current }
        }
    }

    /** Waits for the service to be destroyed, then for every accepted write to land. */
    private fun releaseAndAwaitDestroyed() {
        releaseExtraController()
        PlaybackServiceLifecycle.awaitDestroyed(context)
        settle()
    }

    /**
     * Swipe from Recents writes the cursor only, never the ~51 loaded items as the queue (that
     * would replace a long saved queue with the loaded window). The destroy that follows writes
     * nothing more.
     */
    @Test fun removingTheTaskSavesOnlyTheCursorAndTheFollowingDestroyAddsNothing() {
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        try {
            context.startService(Intent(context, SynthXPlaybackService::class.java))
            loadItems(size = WINDOW_SIZE, current = 30, positionMs = 5_000L)

            scenario.onActivity { it.finishAndRemoveTask() }
            await("the service released its session after onTaskRemoved") { !onMain { extraController.isConnected } }
            releaseAndAwaitDestroyed()

            assertEquals(listOf(QueueWrite.Cursor("slot-30", 5_000L)), recorder.writes.toList())
        } finally {
            scenario.close()
        }
    }

    /** A destroy with no task removal before it (the service just stopped) saves the cursor. */
    @Test fun destroyWithoutTaskRemovalSavesTheCursor() {
        loadItems(size = 5, current = 2, positionMs = 7_000L)

        releaseAndAwaitDestroyed()

        assertEquals(listOf(QueueWrite.Cursor("slot-2", 7_000L)), recorder.writes.toList())
    }

    /** Nothing loaded: neither exit path writes. */
    @Test fun destroyWithAnEmptyPlayerWritesNothing() {
        extraController = connectExtraController(notificationFlagHints())

        releaseAndAwaitDestroyed()

        assertEquals(emptyList<QueueWrite>(), recorder.writes.toList())
    }
}
