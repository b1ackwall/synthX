package com.v1per.synthx.playback

import com.v1per.synthx.data.repository.Song
import kotlin.random.Random

/**
 * A whole-list play from a Play or Shuffle button, decided at tap time: the playable songs, the
 * song to start on and the shuffle flag playback ends up with.
 */
data class PlayAllRequest(val songs: List<Song>, val startIndex: Int, val shuffle: Boolean)

/**
 * Asked before a Play or Shuffle button replaces a hand-built queue. Carries the [request] as
 * decided at tap time, so Replace plays exactly that — the same start song, the same flag.
 */
data class QueueReplacePromptEvent(val request: PlayAllRequest)

/**
 * The [PlayAllRequest] for [songs], or null when none of them is playable. Missing songs are
 * dropped first, as [PlaybackViewModel.requestPlayQueue] does. Unshuffled starts on the first
 * playable song; shuffled starts on one picked by [random], and the engine shuffles the rest.
 */
internal fun playAllRequest(songs: List<Song>, shuffle: Boolean, random: Random): PlayAllRequest? {
    val playable = songs.filterNot { it.isMissing }
    if (playable.isEmpty()) return null
    val startIndex = if (shuffle) random.nextInt(playable.size) else 0
    return PlayAllRequest(playable, startIndex, shuffle)
}
