package com.v1per.synthx.ui.components

import com.v1per.synthx.ui.testing.TestTimeouts
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals

/** The detail-header stats line, with the English resource text standing in for the resources. */
class SongStatsFormatTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.jvm()

    private val english = object : SongStatsStrings {
        override fun songCount(count: Int) = if (count == 1) "1 song" else "$count songs"
        override fun hours(hours: Long) = "$hours hr"
        override fun minutes(minutes: Long) = "$minutes min"
        override fun hoursAndMinutes(hours: Long, minutes: Long) = "$hours hr $minutes min"
        override fun countAndDuration(count: String, duration: String) = "$count · $duration"
    }

    private fun format(count: Int, ms: Long) = formatSongStats(count, ms, english)

    private fun ms(hours: Long = 0, minutes: Long = 0, seconds: Long = 0) =
        ((hours * 60 + minutes) * 60 + seconds) * 1_000

    @Test fun noSongsShowsTheCountAlone() {
        assertEquals("0 songs", format(0, 0))
    }

    @Test fun oneSongIsSingular() {
        assertEquals("1 song · 3 min", format(1, ms(minutes = 3)))
    }

    @Test fun hoursAndMinutes() {
        assertEquals("42 songs · 2 hr 41 min", format(42, ms(hours = 2, minutes = 41)))
    }

    @Test fun underAnHourShowsMinutesOnly() {
        assertEquals("12 songs · 47 min", format(12, ms(minutes = 47)))
    }

    @Test fun fiftyNineMinutesTwentyNineSecondsRoundsDown() {
        assertEquals("15 songs · 59 min", format(15, ms(minutes = 59, seconds = 29)))
    }

    @Test fun fiftyNineMinutesThirtySecondsRoundsUpToAnHour() {
        assertEquals("15 songs · 1 hr", format(15, ms(minutes = 59, seconds = 30)))
    }

    @Test fun exactlySixtyMinutesShowsHoursOnly() {
        assertEquals("15 songs · 1 hr", format(15, ms(minutes = 60)))
    }

    @Test fun anHourAndThirtySecondsRoundsUpAMinute() {
        assertEquals("15 songs · 1 hr 1 min", format(15, ms(hours = 1, seconds = 30)))
    }

    @Test fun aWholeNumberOfHoursDropsTheMinutes() {
        assertEquals("30 songs · 2 hr", format(30, ms(hours = 2)))
    }

    @Test fun anyNonZeroTotalShowsAtLeastOneMinute() {
        assertEquals("1 song · 1 min", format(1, 1))
        assertEquals("1 song · 1 min", format(1, ms(seconds = 29)))
    }

    @Test fun zeroDurationSongsTotalZeroMinutes() {
        assertEquals("3 songs · 0 min", format(3, 0))
    }
}
