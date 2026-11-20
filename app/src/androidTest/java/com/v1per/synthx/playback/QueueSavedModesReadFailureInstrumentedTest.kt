package com.v1per.synthx.playback

import android.database.sqlite.SQLiteException
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.data.repository.QueueCursor
import com.v1per.synthx.data.repository.QueueSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * When the saved cursor cannot be read at all, its modes are unknown: the engine keeps the
 * session's modes (Repeat Off, shuffle off on a fresh service) and writes nothing.
 */
@RunWith(AndroidJUnit4::class)
class QueueSavedModesReadFailureInstrumentedTest : PlaybackServiceTestBase() {

    private val recorder = RecordingQueueRepository()

    override fun createPersistence(): QueuePersistence = QueuePersistence(recorder)

    @Test fun aFailedCursorReadLeavesTheModesUnknown() {
        recorder.snapshot = QueueSnapshot(
            emptyList(),
            0,
            QueueCursor(null, 0L, shuffleEnabled = true, repeatMode = Player.REPEAT_MODE_ALL),
        )
        recorder.snapshotFailure = SQLiteException("injected: the cursor read fails")

        runBlocking { engine.restoreSavedQueue() }
        settle()

        assertEquals(RepeatMode.Off, onMain { engine.repeatMode.now() })
        assertFalse(onMain { engine.shuffleEnabled.now() })
        assertEquals(Player.REPEAT_MODE_OFF, onMain { extraController.repeatMode })
        assertTrue(recorder.writes.toString(), recorder.writes.isEmpty())
    }
}
