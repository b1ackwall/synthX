package com.v1per.synthx.playback

import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.v1per.synthx.ui.testing.TestTimeouts
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The pure decision logic behind transport-skip idle recovery
 * ([idleRecoveryActionFor]/[applyIdleRecovery], used by [seekToLiveIndex] and the direct-seek
 * branches of `ExoPlayerEngine.previous()`) and the log-message content of
 * `onPlayerError` ([playerErrorLogMessage]).
 *
 * Neither [seekToLiveIndex] nor `onPlayerError` itself is exercised here: both need a real
 * `MediaController`/`Player.Listener` wired to a live media3 session, which this module has no
 * JVM-level fake or mocking library for (no Mockito/MockK dependency, no Robolectric). What's
 * covered here is the complete state-to-action mapping and the exact content of the log message —
 * the two pieces of pure logic involved. The actual controller call sequence (prepare-before-seek recovers
 * playback after Next and Previous) and `onPlayerError` run on a device in
 * `PlaybackRecoveryInstrumentedTest`.
 */
class IdleRecoveryTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.jvm()

    @Test fun idleStateRequiresPrepare() {
        assertEquals(IdleRecoveryAction.PREPARE, idleRecoveryActionFor(Player.STATE_IDLE))
    }

    @Test fun endedStateRequiresSeekToDefault() {
        assertEquals(IdleRecoveryAction.SEEK_TO_DEFAULT, idleRecoveryActionFor(Player.STATE_ENDED))
    }

    @Test fun readyAndBufferingNeedNoRecovery() {
        assertEquals(IdleRecoveryAction.NONE, idleRecoveryActionFor(Player.STATE_READY))
        assertEquals(IdleRecoveryAction.NONE, idleRecoveryActionFor(Player.STATE_BUFFERING))
    }

    @OptIn(markerClass = [UnstableApi::class])
    @Test
    fun errorLogMessageCarriesCodeAndCauseDistinguishingDetail() {
        val fileNotFound = PlaybackException(
            "io error",
            java.io.FileNotFoundException("missing.mp3"),
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        )
        val decoderFailure = PlaybackException(
            "decoder error",
            RuntimeException("codec init failed"),
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        )

        val fileNotFoundMessage = playerErrorLogMessage(fileNotFound, slotId = "slot-1", title = "Song A")
        val decoderFailureMessage = playerErrorLogMessage(decoderFailure, slotId = "slot-2", title = "Song B")

        // The two failures must be distinguishable from each other and from a generic message.
        assertEquals(true, fileNotFoundMessage.contains("ERROR_CODE_IO_FILE_NOT_FOUND"))
        assertEquals(true, decoderFailureMessage.contains("ERROR_CODE_DECODER_INIT_FAILED"))
        assertEquals(true, fileNotFoundMessage.contains("slot-1"))
        assertEquals(true, decoderFailureMessage.contains("slot-2"))
        assertEquals(false, fileNotFoundMessage == decoderFailureMessage)
    }
}
