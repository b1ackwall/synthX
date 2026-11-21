package com.v1per.synthx.playback

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlaybackException

// ExoPlayerEngine's policy for a song that fails to play.

/** Consecutive failures after which a burst stops skipping and stays on the failing song. */
internal const val PLAYBACK_FAILURE_CAP = 5

/** What a playback failure does — see [playbackFailureActionFor]. */
internal enum class PlaybackFailureAction { SKIP, STAY }

/**
 * Errors whose cause is the song's own file: it is missing, unreadable, cut short, or not a file any
 * extractor understands. Retrying such a file fails again, so playback moves on. Network, timeout,
 * remote and unspecified errors, decoder and audio-output errors are left out: they are not about a
 * local file or can pass, and the player stays on the song.
 */
private val SOURCE_FAILURE_CODES = setOf(
    PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
    PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
    PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
)

/** Whether [errorCode] (a [PlaybackException] code) is a failure of the song's file; see [SOURCE_FAILURE_CODES]. */
internal fun isSourceFailure(errorCode: Int): Boolean = errorCode in SOURCE_FAILURE_CODES

/**
 * Skip or stay after a playback failure. Pure, so the table is unit-testable without a controller.
 *
 * Only a [isSourceFailure] with a next slot ([hasNext], from the order store, so Repeat All and
 * Repeat One wrap from the last slot to the first) skips, and only while the player is still idle
 * on the failure ([playerIdle]): a failure reported after the user already moved playback on (a Next
 * tap that landed first) must not skip past the song the user chose. A burst of [consecutiveFailures] stops at
 * [cap]; under Repeat All and Repeat One, where the skips wrap, it also stops once every slot of the
 * queue ([queueLength]) has been tried, so a queue of bad files is tried once and never spins.
 */
internal fun playbackFailureActionFor(
    errorCode: Int,
    repeatMode: RepeatMode,
    hasNext: Boolean,
    consecutiveFailures: Int,
    queueLength: Int,
    playerIdle: Boolean,
    cap: Int = PLAYBACK_FAILURE_CAP,
): PlaybackFailureAction {
    if (!isSourceFailure(errorCode) || !hasNext || !playerIdle) return PlaybackFailureAction.STAY
    val limit = if (repeatMode == RepeatMode.Off) cap else minOf(cap, queueLength)
    return if (consecutiveFailures >= limit) PlaybackFailureAction.STAY else PlaybackFailureAction.SKIP
}

/**
 * Whether an error belongs to a slot the engine has already left: [failedSlotId] is the slot that raised it
 * and [currentSlotId] the slot the engine is on. The player can report the failure of the song a skip just moved
 * on from a second time, after the engine is on the next slot; that is not a failure of the current song. An
 * error that does not name its slot, or that arrives with no current slot, is treated as the current song's.
 */
internal fun isLateErrorForLeftSlot(failedSlotId: String?, currentSlotId: String?): Boolean =
    failedSlotId != null && currentSlotId != null && failedSlotId != currentSlotId

/**
 * The slot whose period raised [error], read from the period the player stamped on it and looked up in the
 * controller's timeline. Null when the error carries no period, its period is not in the timeline, or the
 * item has no id.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal fun ExoPlayerEngine.slotIdOfErrorSource(error: PlaybackException): String? {
    val periodUid = (error as? ExoPlaybackException)?.mediaPeriodId?.periodUid ?: return null
    val timeline = controller?.currentTimeline ?: return null
    val periodIndex = timeline.getIndexOfPeriod(periodUid)
    if (periodIndex == C.INDEX_UNSET) return null
    val windowIndex = timeline.getPeriod(periodIndex, Timeline.Period()).windowIndex
    return timeline.getWindow(windowIndex, Timeline.Window()).mediaItem.mediaId.takeIf { it.isNotEmpty() }
}

/**
 * Handles an error for [failedSlotId], a slot the engine has left. It is not a failure of the current song, so it is
 * neither counted nor reported. The player stops on any error, which leaves it idle on the slot the skip chose, so
 * the engine lands on that slot again while the player is idle; the play-when-ready choice is kept.
 */
internal fun ExoPlayerEngine.handleLateError(failedSlotId: String?, errorCode: Int) {
    val currentSlotId = _currentSlotId.value
    Log.i(ExoPlayerEngine.TAG, "Ignoring late error code=$errorCode for left slot $failedSlotId; on $currentSlotId")
    if (controller?.playbackState != Player.STATE_IDLE) return
    val index = orderStore.indexOfSlot(currentSlotId)
    if (index >= 0) seekToLiveIndex(index, play = false)
}

/**
 * Handles one failure of the current song. The first failure of a burst shows the playback-failed
 * message (titled after that song); the songs a burst skips do not add more. A skip moves to the
 * next slot in queue order through [seekToLiveIndex], which revives the idle player and keeps its
 * play-when-ready: a playing user hears the next song, a paused one stays paused on it. The failed
 * song keeps its slot. Never gated on the session connection: it is the engine's own move.
 */
internal fun ExoPlayerEngine.handlePlaybackFailure(errorCode: Int, title: String?) {
    consecutiveFailures++
    if (consecutiveFailures == 1 && title != null) {
        _errors.tryEmit(PlaybackError.PlaybackFailed(title))
    }
    val target = nextLiveIndex()
    val action = playbackFailureActionFor(
        errorCode = errorCode,
        repeatMode = _repeatMode.value,
        hasNext = target != null,
        consecutiveFailures = consecutiveFailures,
        queueLength = orderStore.size,
        playerIdle = controller?.playbackState == Player.STATE_IDLE,
    )
    if (action != PlaybackFailureAction.SKIP || target == null) return
    Log.i(
        ExoPlayerEngine.TAG,
        "Skipping unplayable slot ${_currentSlotId.value}: failure $consecutiveFailures of this burst",
    )
    seekToLiveIndex(target, play = false)
}

/** Starts a new burst: a song reached READY or started playing, or the user moved playback. */
internal fun ExoPlayerEngine.resetFailureBurst() {
    consecutiveFailures = 0
}
