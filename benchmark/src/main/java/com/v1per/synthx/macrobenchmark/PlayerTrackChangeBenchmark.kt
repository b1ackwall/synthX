package com.v1per.synthx.macrobenchmark

import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * One skip to the next song per iteration, measured through the art change. The skip is undone
 * with Previous before the next iteration and after the last, outside the measurement, so the
 * scenario ends on the song it started on. The position inside that song restarts at zero.
 * All three need a song after the current one; a skip moves the queue cursor and edits nothing.
 *
 * nextExpanded and nextQueue: the expanded player's next button, in player mode and in queue
 * mode, with playback paused. Rarely flaky; an undecodable next file would fail the title check.
 *
 * nextMini: the mini player has no next button, so this sends the MEDIA_NEXT key while a song
 * plays. Likely flaky and ROM-dependent: media keys go to whichever app owns the media button
 * session, so the key is only sent after 'dumpsys media_session' names synthX bench as the owner,
 * and that dump's format differs between ROMs (the scenario then fails without sending the key).
 * While playing, Previous restarts a song that is past its first seconds, so the undo may take
 * two presses. Audio plays through the phone.
 */
@RunWith(AndroidJUnit4::class)
class PlayerTrackChangeBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Before
    fun checkPreconditions() = Scenario.checkPreconditions()

    @Test
    fun nextExpanded() = nextWithButton(queueMode = false)

    @Test
    fun nextQueue() = nextWithButton(queueMode = true)

    @Test
    fun nextMini() {
        val track = TrackRestore(Scenario.device())
        rule.runScenario(
            defaultIterations = 8,
            prepare = {
                val player = PlayerDriver(it)
                val playback = PlaybackDriver(it)
                player.ensureExpanded()
                playback.requireNextAvailable()
                player.collapse()
                playback.ensurePlaying()
                playback.requireMediaButtonSession()
                track.rememberOrigin()
            },
            beforeEach = { track.restore(PlaybackDriver(it)::mediaPrevious) },
            cleanup = {
                val playback = PlaybackDriver(it)
                track.restore(playback::mediaPrevious)
                playback.ensurePaused()
            },
        ) {
            PlaybackDriver(it).mediaNext()
            track.noteSkip()
        }
    }

    private fun nextWithButton(queueMode: Boolean) {
        val track = TrackRestore(Scenario.device())
        rule.runScenario(
            defaultIterations = 8,
            prepare = {
                PlayerDriver(it).ensureExpanded()
                QueueDriver(it).ensureQueueMode(on = queueMode)
                PlaybackDriver(it).requireNextAvailable()
                track.rememberOrigin()
            },
            beforeEach = { track.restore(PlaybackDriver(it)::previous) },
            cleanup = {
                track.restore(PlaybackDriver(it)::previous)
                QueueDriver(it).ensureQueueMode(on = false)
            },
        ) {
            PlaybackDriver(it).next()
            track.noteSkip()
        }
    }
}
