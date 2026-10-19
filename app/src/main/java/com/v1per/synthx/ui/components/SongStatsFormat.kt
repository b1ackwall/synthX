package com.v1per.synthx.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalResources
import com.v1per.synthx.R

/** The text pieces a stats line is built from: string resources in the app, plain text in tests. */
interface SongStatsStrings {
    fun songCount(count: Int): String
    fun hours(hours: Long): String
    fun minutes(minutes: Long): String
    fun hoursAndMinutes(hours: Long, minutes: Long): String
    fun countAndDuration(count: String, duration: String): String
}

/**
 * "42 songs · 2 hr 41 min". The total rounds to the nearest whole minute, and any total above
 * 0 ms shows as at least 1 min. With no songs the line is the count alone.
 */
fun formatSongStats(songCount: Int, totalDurationMs: Long, strings: SongStatsStrings): String {
    val count = strings.songCount(songCount)
    if (songCount == 0) return count
    return strings.countAndDuration(count, formatTotalDuration(totalDurationMs, strings))
}

internal fun formatTotalDuration(totalDurationMs: Long, strings: SongStatsStrings): String {
    val totalMinutes = if (totalDurationMs <= 0L) {
        0L
    } else {
        ((totalDurationMs + MS_PER_MINUTE / 2) / MS_PER_MINUTE).coerceAtLeast(1L)
    }
    val hours = totalMinutes / MINUTES_PER_HOUR
    val minutes = totalMinutes % MINUTES_PER_HOUR
    return when {
        hours == 0L -> strings.minutes(minutes)
        minutes == 0L -> strings.hours(hours)
        else -> strings.hoursAndMinutes(hours, minutes)
    }
}

/** [SongStatsStrings] backed by the app's string and plural resources. */
@Composable
fun rememberSongStatsStrings(): SongStatsStrings {
    val resources = LocalResources.current
    return remember(resources) {
        object : SongStatsStrings {
            override fun songCount(count: Int) = resources.getQuantityString(R.plurals.stats_song_count, count, count)
            override fun hours(hours: Long) = resources.getString(R.string.stats_duration_hours, hours)
            override fun minutes(minutes: Long) = resources.getString(R.string.stats_duration_minutes, minutes)
            override fun hoursAndMinutes(hours: Long, minutes: Long) =
                resources.getString(R.string.stats_duration_hours_minutes, hours, minutes)
            override fun countAndDuration(count: String, duration: String) =
                resources.getString(R.string.stats_count_and_duration, count, duration)
        }
    }
}

private const val MS_PER_MINUTE = 60_000L
private const val MINUTES_PER_HOUR = 60L
