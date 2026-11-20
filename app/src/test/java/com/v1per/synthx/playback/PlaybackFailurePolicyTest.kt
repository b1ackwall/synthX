package com.v1per.synthx.playback

import androidx.media3.common.PlaybackException
import com.v1per.synthx.ui.testing.TestTimeouts
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The auto-skip decision for a failed song: which errors skip, and when a burst of failures stops. */
class PlaybackFailurePolicyTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.jvm()

    private val sourceCodes = listOf(
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
        PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
    )

    // Every other code media3 defines: they can be temporary, or are not about a local file.
    private val otherCodes = listOf(
        PlaybackException.ERROR_CODE_UNSPECIFIED,
        PlaybackException.ERROR_CODE_REMOTE_ERROR,
        PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW,
        PlaybackException.ERROR_CODE_TIMEOUT,
        PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
        PlaybackException.ERROR_CODE_DRM_UNSPECIFIED,
    )

    private fun action(
        code: Int,
        repeat: RepeatMode = RepeatMode.Off,
        hasNext: Boolean = true,
        failures: Int = 1,
        queueLength: Int = 20,
        playerIdle: Boolean = true,
        cap: Int = PLAYBACK_FAILURE_CAP,
    ) = playbackFailureActionFor(code, repeat, hasNext, failures, queueLength, playerIdle, cap)

    @Test fun theCapIsFive() = assertEquals(5, PLAYBACK_FAILURE_CAP)

    @Test fun onlyFileSourceErrorsCountAsSourceFailures() {
        sourceCodes.forEach { assertTrue(isSourceFailure(it), "code $it") }
        otherCodes.forEach { assertFalse(isSourceFailure(it), "code $it") }
    }

    @Test fun aSourceErrorWithANextSlotSkipsInEveryRepeatMode() {
        for (code in sourceCodes) {
            for (repeat in RepeatMode.entries) {
                assertEquals(PlaybackFailureAction.SKIP, action(code, repeat), "code $code under $repeat")
            }
        }
    }

    @Test fun anyOtherErrorStaysEvenWithANextSlot() {
        for (code in otherCodes) {
            for (repeat in RepeatMode.entries) {
                assertEquals(PlaybackFailureAction.STAY, action(code, repeat), "code $code under $repeat")
            }
        }
    }

    @Test fun noNextSlotStaysInEveryRepeatMode() {
        for (repeat in RepeatMode.entries) {
            assertEquals(
                PlaybackFailureAction.STAY,
                action(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND, repeat, hasNext = false),
                "under $repeat",
            )
        }
    }

    @Test fun aBurstStopsAtTheCap() {
        val code = PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND
        for (repeat in RepeatMode.entries) {
            assertEquals(PlaybackFailureAction.SKIP, action(code, repeat, failures = 4), "4 failures under $repeat")
            assertEquals(PlaybackFailureAction.STAY, action(code, repeat, failures = 5), "5 failures under $repeat")
            assertEquals(PlaybackFailureAction.STAY, action(code, repeat, failures = 9), "9 failures under $repeat")
        }
    }

    @Test fun underTheWrappingModesABurstStopsOnceEverySlotWasTried() {
        val code = PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
        for (repeat in listOf(RepeatMode.All, RepeatMode.One)) {
            assertEquals(PlaybackFailureAction.SKIP, action(code, repeat, failures = 2, queueLength = 3), "$repeat")
            assertEquals(PlaybackFailureAction.STAY, action(code, repeat, failures = 3, queueLength = 3), "$repeat")
            assertEquals(PlaybackFailureAction.STAY, action(code, repeat, failures = 1, queueLength = 1), "$repeat")
        }
    }

    @Test fun underRepeatOffOnlyTheCapLimitsABurst() {
        // Without a wrap no slot is tried twice, so the queue length does not limit the burst.
        val code = PlaybackException.ERROR_CODE_IO_NO_PERMISSION
        assertEquals(PlaybackFailureAction.SKIP, action(code, RepeatMode.Off, failures = 3, queueLength = 3))
    }

    @Test fun aFailureReportedAfterTheUserMovedPlaybackOnStays() {
        // The player is no longer idle: a Next tap already revived it on another song.
        for (repeat in RepeatMode.entries) {
            assertEquals(
                PlaybackFailureAction.STAY,
                action(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND, repeat, playerIdle = false),
                "under $repeat",
            )
        }
    }

    @Test fun aSmallerCapIsHonoured() {
        val code = PlaybackException.ERROR_CODE_IO_UNSPECIFIED
        assertEquals(PlaybackFailureAction.STAY, action(code, RepeatMode.All, failures = 2, cap = 2))
        assertEquals(PlaybackFailureAction.SKIP, action(code, RepeatMode.All, failures = 1, cap = 2))
    }
}
