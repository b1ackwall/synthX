package com.v1per.synthx.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Decoded player background art for the song with [songId]. */
data class PlayerBackgroundArt<T : Any>(val songId: String, val image: T)

/**
 * Holds the player background art for the latest request. A new request cancels the in-flight
 * decode; the previous art stays until the new decode finishes, then swaps in one step. A song
 * with no art URI clears it at once; art that fails to decode clears it when the decode ends.
 */
internal class PlayerBackgroundArtHolder<T : Any>(
    private val scope: CoroutineScope,
    private val load: suspend (PlayerBackgroundArtRequest) -> T?,
) {
    private val _art = MutableStateFlow<PlayerBackgroundArt<T>?>(null)
    val art: StateFlow<PlayerBackgroundArt<T>?> = _art.asStateFlow()

    private var current: PlayerBackgroundArtRequest? = null
    private var job: Job? = null

    fun request(request: PlayerBackgroundArtRequest) {
        if (request == current) return
        current = request
        job?.cancel()
        if (request.decodeSources().isEmpty()) {
            _art.value = null
            return
        }
        job = scope.launch {
            val image = load(request)
            _art.value = image?.let { PlayerBackgroundArt(request.songId, it) }
        }
    }
}
