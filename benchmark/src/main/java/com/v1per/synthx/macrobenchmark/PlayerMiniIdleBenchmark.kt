package com.v1per.synthx.macrobenchmark

import android.os.SystemClock
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Mini player idle while a song plays: playback is started before the first iteration, each
 * iteration then measures about ten seconds with no interaction, and playback is paused at the
 * end. Audio plays through the phone for the whole scenario.
 *
 * Likely flaky: if the song ends inside a window, the next track's art load adds a burst of
 * frames to that iteration; another app taking audio focus pauses playback and empties the rest.
 */
@RunWith(AndroidJUnit4::class)
class PlayerMiniIdleBenchmark {
    private companion object {
        const val IdleWindowMs = 10_000L
    }

    @get:Rule
    val rule = MacrobenchmarkRule()

    @Before
    fun checkPreconditions() = Scenario.checkPreconditions()

    @Test
    fun miniIdlePlaying() {
        rule.runScenario(
            defaultIterations = 6,
            beforeEach = { PlaybackDriver(it).ensurePlaying() },
            cleanup = { PlaybackDriver(it).ensurePaused() },
        ) {
            SystemClock.sleep(IdleWindowMs)
        }
    }
}
