package com.v1per.synthx.ui.player

import com.v1per.synthx.R
import com.v1per.synthx.playback.RepeatMode
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The empty Up Next text follows the repeat mode: Repeat Off keeps "Nothing queued after this",
 * Repeat All says playback continues from the start, Repeat One says this song repeats.
 */
class EmptyUpNextTextTest {
    @Test fun repeatOffKeepsTheNothingQueuedText() {
        assertEquals(R.string.empty_queue_no_upcoming, emptyUpNextText(RepeatMode.Off))
    }

    @Test fun repeatAllSaysPlaybackContinuesFromTheStart() {
        assertEquals(R.string.empty_queue_repeat_all, emptyUpNextText(RepeatMode.All))
    }

    @Test fun repeatOneSaysThisSongRepeats() {
        assertEquals(R.string.empty_queue_repeat_one, emptyUpNextText(RepeatMode.One))
    }
}
