package com.v1per.synthx.playback

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.v1per.synthx.data.repository.Song
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Asks the user what to do with a hand-modified queue before it gets replaced.
 *
 * Carries both readings of the tap, because the two answers need different ones: "Keep"
 * only ever acts on [tappedSong], while "Clear" rebuilds the queue from [contextSongs] at
 * [startIndex]. Keeping [tappedSong] as its own field rather than making the UI index back
 * into the list is what stops the two from drifting apart.
 */
data class QueuePromptEvent(
    val tappedSong: Song,
    val contextSongs: List<Song>,
    val startIndex: Int,
)

class PlaybackViewModel(private val controller: PlaybackController) : ViewModel() {

    // Combined optimistic/real play-pause state: a tap flips this synchronously, before the
    // MediaController IPC round-trip confirms anything, so the icon never waits on the
    // engine. Whatever the engine reports next — matching the tap or not, e.g. audio focus
    // denied — overwrites it, since the engine is the only source that ever actually knows.
    // No timeout/reconciliation logic needed: the real flow simply wins every time it emits.
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    val isBuffering: StateFlow<Boolean> = controller.isBuffering
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val currentSongId: StateFlow<String?> = controller.currentSongId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _currentSong = MutableStateFlow<Song?>(null)
    val currentSong: StateFlow<Song?> = _currentSong.asStateFlow()

    // Eagerly, unlike the state below: currentSong is resolved against queue.value in
    // init, which only holds a real list while something is subscribed. The upstream is a
    // hot StateFlow in the engine, so there is nothing to keep warm at a cost.
    val queue: StateFlow<List<Song>> = controller.queue
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val currentIndex: StateFlow<Int> = controller.currentIndex
        .stateIn(viewModelScope, SharingStarted.Eagerly, -1)

    // Screen-scoped like shuffleEnabled/repeatMode below: only the Queue screen reads this,
    // and nothing here needs a synchronous .value the way requestPlayQueue needs queue.value.
    val upcomingQueue: StateFlow<List<Song>> = controller.upcomingQueue
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val shuffleEnabled: StateFlow<Boolean> = controller.shuffleEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val repeatMode: StateFlow<RepeatMode> = controller.repeatMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RepeatMode.Off)

    val hasNext: StateFlow<Boolean> = controller.hasNext
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val hasPrevious: StateFlow<Boolean> = controller.hasPrevious
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    // Eagerly for the same reason as queue above: requestPlayQueue reads .value on every
    // song tap, and under WhileSubscribed that would be a stale false whenever no UI
    // happens to be collecting it.
    val isQueueCustomized: StateFlow<Boolean> = controller.isQueueCustomized
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val currentPosition: StateFlow<Long> = controller.currentPosition
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    val duration: StateFlow<Long> = controller.duration
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    // Passed through as a plain Flow, unlike the state above: these are one-off events,
    // and stateIn/shareIn would replay the last failure to every new collector — showing
    // a stale snackbar on every navigation back to the scaffold.
    val errors: Flow<PlaybackError> = controller.errors

    // Same one-off event shape as errors above and SongActionsViewModel.events: buffered
    // by one, newest wins, never replayed — a prompt the user already answered must not
    // reappear on the next collector.
    private val _queuePrompts = MutableSharedFlow<QueuePromptEvent>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val queuePrompts: SharedFlow<QueuePromptEvent> = _queuePrompts.asSharedFlow()

    init {
        // Mirror currentSongId into currentSong: null (queue end, stop) clears it, and an
        // auto-advance to the next queue item resolves the new id against the queue. The
        // fallback keeps the previous song rather than blanking the player if the id
        // arrives before the queue does.
        viewModelScope.launch {
            currentSongId.collect { id ->
                _currentSong.value = when (id) {
                    null -> null
                    else -> queue.value.firstOrNull { it.id == id } ?: _currentSong.value
                }
            }
        }

        // The engine's own isPlaying always wins once it lands, overwriting whatever
        // optimistic guess togglePlayPause()/pause()/resume() made on tap.
        viewModelScope.launch {
            controller.isPlaying.collect { real -> _isPlaying.value = real }
        }
    }

    // Tracks the in-flight playQueue coroutine so a later tap can supersede an earlier
    // one still doing artwork work. Without this, two rapid taps race controller.playQueue
    // independently and whichever's async work (artwork I/O/decode) finishes last wins,
    // regardless of which tap was actually most recent.
    private var playQueueJob: Job? = null

    // Stays a plain fun so every UI call site keeps working — the engine's suspend
    // artwork work is launched into viewModelScope (Main), which is also the
    // dispatcher MediaController requires when the engine resumes.
    fun playQueue(songs: List<Song>, startIndex: Int) {
        // Set optimistically so the mini player appears on tap rather than after the
        // starting item's artwork has been decoded.
        _currentSong.value = songs.getOrNull(startIndex)
        // Cancel any prior tap's still-running queue build first — only the most recent
        // tap should ever be allowed to reach controller.playQueue's setMediaItems.
        playQueueJob?.cancel()
        playQueueJob = viewModelScope.launch { controller.playQueue(songs, startIndex) }
    }

    /**
     * The entry point every song tap goes through. Replacing a queue the user built by
     * hand is the one case where playing a song is destructive, so that case asks first
     * and everything else plays immediately.
     *
     * Also requires active playback: a customised queue that has since run to its end is
     * nothing to preserve, and "Keep" against a stopped player would insert into a dead
     * timeline.
     */
    fun requestPlayQueue(songs: List<Song>, startIndex: Int) {
        val tapped = songs.getOrNull(startIndex)
        if (tapped != null && isQueueCustomized.value && hasActivePlayback()) {
            _queuePrompts.tryEmit(
                QueuePromptEvent(
                    tappedSong = tapped,
                    contextSongs = songs,
                    startIndex = startIndex,
                )
            )
        } else {
            playQueue(songs, startIndex)
        }
    }

    /**
     * Keep: the customised queue survives untouched, the tapped song is spliced in right
     * after the current item and jumped to, so what the user queued up plays after it. The
     * list the song was tapped from is deliberately discarded — only the song matters here.
     */
    fun keepQueueAndPlayNext(song: Song) {
        // The queue can have ended between the prompt being raised and answered.
        if (!hasActivePlayback()) {
            playQueue(listOf(song), 0)
            return
        }
        controller.playNext(song)
        controller.next()
    }

    /** Clear: the ordinary fresh-queue path, discarding whatever the user had queued. */
    fun clearQueueAndPlay(songs: List<Song>, startIndex: Int) {
        playQueue(songs, startIndex)
    }

    /**
     * Loads last session's queue, paused and ready. Safe to call more than once — the
     * engine ignores it once a timeline exists.
     */
    fun restoreSavedQueue() {
        viewModelScope.launch { controller.restoreSavedQueue() }
    }

    /** Called when the app goes to the background, so the resume point survives a kill. */
    fun persistPlaybackState() = controller.persistPlaybackState()

    fun next() = controller.next()

    fun previous() = controller.previous()

    fun setShuffleEnabled(enabled: Boolean) = controller.setShuffleEnabled(enabled)

    fun setRepeatMode(mode: RepeatMode) = controller.setRepeatMode(mode)

    /**
     * Queues [song] directly after the current item — or just plays it when there is no
     * active playback to queue behind. The engine's insert is timeline-only (no prepare,
     * no play), so without this branch "Play Next" against a dead queue would put the song
     * in the timeline and leave the player idle.
     *
     * Returns `false` without touching the controller when [song] is already the current
     * song or already sits in [upcomingQueue] — dedup only applies to this branch, since
     * there is nothing to be a duplicate of when [hasActivePlayback] is false.
     */
    fun playNext(song: Song): Boolean {
        if (!hasActivePlayback()) {
            playQueue(listOf(song), 0)
            return true
        }
        if (isAlreadyQueued(song.id)) return false
        controller.playNext(song)
        return true
    }

    /** Appends [song], with the same fall back to starting playback and dedup as [playNext]. */
    fun addToQueue(song: Song): Boolean {
        if (!hasActivePlayback()) {
            playQueue(listOf(song), 0)
            return true
        }
        if (isAlreadyQueued(song.id)) return false
        controller.addToQueue(song)
        return true
    }

    /** True when [songId] is the current song or already sits in [upcomingQueue]. */
    private fun isAlreadyQueued(songId: String): Boolean =
        songId == currentSongId.value || upcomingQueue.value.any { it.id == songId }

    /** [fromIndex]/[toIndex] are positions in [upcomingQueue] — the Up Next screen's rows. */
    fun moveQueueItem(fromIndex: Int, toIndex: Int) = controller.moveQueueItem(fromIndex, toIndex)

    /**
     * Removes the [upcomingQueue] entry for [songId] — the Up Next screen's rows.
     *
     * Takes the song id rather than the row index on purpose. A row index captured when the
     * user starts a gesture can be stale by the time the call lands: any queue mutation in
     * between (the track advancing, a play-next insert, a second removal in quick succession)
     * shifts every row after it, and the stale index then names a different song. The engine
     * resolves the real position fresh from its own live queue instead, so nothing
     * index-shaped ever crosses that gap.
     */
    fun removeQueueItem(songId: String) = controller.removeQueueItem(songId)

    /** Jumps to and plays the item at [index] in [upcomingQueue] — the Up Next screen's row tap. */
    fun playQueueItem(index: Int) = controller.seekToQueueItem(index)

    /**
     * False both when nothing was ever queued and when a queue ran to its end — stop()
     * leaves the timeline populated but idle, so the item count alone would be misleading.
     */
    private fun hasActivePlayback(): Boolean =
        currentSongId.value != null && queue.value.isNotEmpty()

    fun pause() {
        _isPlaying.value = false
        controller.pause()
    }

    fun resume() {
        _isPlaying.value = true
        controller.resume()
    }

    fun stop() = controller.stop()

    fun seekTo(positionMs: Long) = controller.seekTo(positionMs)

    fun togglePlayPause() {
        if (_isPlaying.value) pause() else resume()
    }

    override fun onCleared() {
        super.onCleared()
        controller.release()
    }

    /**
     * Builds the engine inside [create] rather than holding a prebuilt one. An
     * ExoPlayerEngine opens a MediaController binding to SynthXPlaybackService in its
     * constructor, so constructing one eagerly — as a factory constructor argument —
     * orphans that binding whenever ViewModelProvider finds an existing ViewModel and
     * never calls [create]. Only the Context is held here; it is cheap to rebuild.
     */
    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (!modelClass.isAssignableFrom(PlaybackViewModel::class.java)) {
                throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
            }
            val engine = ExoPlayerEngine(
                context = context.applicationContext,
                persistence = QueuePersistence.create(context.applicationContext),
            )
            val controller = PlaybackController(engine)
            return PlaybackViewModel(controller) as T
        }
    }
}
