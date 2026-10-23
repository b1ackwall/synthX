package com.v1per.synthx.playback

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.data.repository.QueueSlot
import com.v1per.synthx.ui.player.PlayerAltStageStyle
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Asks the user what to do with a hand-modified queue before it gets replaced.
 *
 * Carries both readings of the tap, because the two answers need different ones: "Keep"
 * only ever acts on [tappedSong], while "Clear" rebuilds the queue from [contextSongs] at
 * [startIndex]. Keeping [tappedSong] as its own field rather than making the UI index back
 * into the list is what stops the two from drifting apart.
 */
data class QueuePromptEvent(val tappedSong: Song, val contextSongs: List<Song>, val startIndex: Int)

/** Outcome of [PlaybackViewModel.playNext]/[PlaybackViewModel.addToQueue]. */
sealed class QueueAddResult {
    /** The controller call went through. */
    data object Added : QueueAddResult()

    /** The song is already in the position ([playNext]'s next slot, [addToQueue]'s last
     *  slot) that would make this call a likely-accidental duplicate — the caller should
     *  ask the user to confirm, then retry with `force = true`. */
    data object NeedsConfirmation : QueueAddResult()

    /** The engine refused the insert because it is not connected to the playback session; it
     *  has already reported that on [PlaybackViewModel.errors], so callers show nothing more. */
    data object Unavailable : QueueAddResult()
}

/**
 * How long [PlaybackViewModel.resume]'s optimistic `_isPlaying = true` is given to be confirmed
 * by the engine's real state before it is force-corrected back to `false` — see
 * [PlaybackViewModel.resume]'s kdoc. Matches the shape of the song-deletion handoff barrier: a
 * short bounded wait rather than an unbounded one.
 */
private const val RESUME_RECONCILE_TIMEOUT_MS = 1_500L

class PlaybackViewModel(
    private val controller: PlaybackController,
    // Decodes the player background's art. The default yields no art.
    loadBackgroundArt: suspend (PlayerBackgroundArtRequest) -> Bitmap? = { null },
    // Picks Shuffle's starting song. Injectable so tests can pin it.
    private val random: Random = Random.Default,
) : ViewModel() {

    // Combined optimistic/real play-pause state: a tap flips this synchronously, before the
    // MediaController IPC round-trip confirms anything, so the icon never waits on the
    // engine. Whatever the engine reports next — matching the tap or not, e.g. audio focus
    // denied — overwrites it, since the engine is the only source that ever actually knows.
    // No timeout/reconciliation logic needed: the real flow simply wins every time it emits.
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    val isBuffering: StateFlow<Boolean> = controller.isBuffering
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    // Eager because hasActivePlayback reads it synchronously, like queue below.
    private val isQueueEnded: StateFlow<Boolean> = controller.isQueueEnded
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // Public content identity for library-row highlighting; never a QueueSlot.slotId.
    val currentSongId: StateFlow<String?> = controller.currentSongId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // Eager twin of currentSongId for hasActivePlayback, which reads .value synchronously on
    // screens that never collect currentSongId (playlist detail): there the lazy one stays null.
    private val activeSongId: StateFlow<String?> = controller.currentSongId
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // Resolved by the engine at the same source-of-truth boundary as currentSongId. Keeping
    // this a direct pass-through avoids joining independently emitted id and queue flows here.
    val currentSong: StateFlow<Song?> = controller.currentSong
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // Eager because requestPlayQueue reads queue.value synchronously even when no UI is
    // collecting it. The upstream is a hot StateFlow in the engine.
    val queue: StateFlow<List<QueueSlot>> = controller.queue
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val currentIndex: StateFlow<Int> = controller.currentIndex
        .stateIn(viewModelScope, SharingStarted.Eagerly, -1)

    // Screen-scoped like shuffleEnabled/repeatMode below: only the Queue screen reads this,
    // and nothing here needs a synchronous .value the way requestPlayQueue needs queue.value.
    val upcomingQueue: StateFlow<List<QueueSlot>> = controller.upcomingQueue
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

    // Play/Shuffle's counterpart of queuePrompts, same one-off shape.
    private val _queueReplacePrompts = MutableSharedFlow<QueueReplacePromptEvent>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val queueReplacePrompts: SharedFlow<QueueReplacePromptEvent> = _queueReplacePrompts.asSharedFlow()

    // The player background's decoded art, keyed by song id. The UI supplies each request.
    private val backgroundArt = PlayerBackgroundArtHolder(
        scope = viewModelScope,
        measure = { it.backgroundBrightness(PlayerAltStageStyle.Saturation) },
        load = loadBackgroundArt,
    )
    val playerBackgroundArt: StateFlow<PlayerBackgroundArt<Bitmap>?> = backgroundArt.art

    /** Decodes [request]'s art unless it is already the current request. */
    fun loadPlayerBackgroundArt(request: PlayerBackgroundArtRequest) = backgroundArt.request(request)

    init {
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
    // A non-null shuffle is applied by the engine as part of the same queue replacement.
    fun playQueue(songs: List<Song>, startIndex: Int, shuffle: Boolean? = null) {
        // Cancel any prior tap's still-running queue build first — only the most recent
        // tap should ever be allowed to reach controller.playQueue's setMediaItems.
        playQueueJob?.cancel()
        playQueueJob = viewModelScope.launch { controller.playQueue(songs, startIndex, shuffle) }
    }

    /** Play button: the playable [songs] in list order from the first, shuffle ending up off. */
    fun playAll(songs: List<Song>) = requestPlayAll(playAllRequest(songs, shuffle = false, random))

    /** Shuffle button: a random first song with the rest shuffled after it, shuffle ending up on. */
    fun shuffleAll(songs: List<Song>) = requestPlayAll(playAllRequest(songs, shuffle = true, random))

    // Same customised-queue condition as requestPlayQueue, but the prompt only offers Replace:
    // there is no single tapped song for Keep to act on.
    private fun requestPlayAll(request: PlayAllRequest?) {
        request ?: return
        if (isQueueCustomized.value && hasActivePlayback()) {
            _queueReplacePrompts.tryEmit(QueueReplacePromptEvent(request))
        } else {
            playQueue(request.songs, request.startIndex, request.shuffle)
        }
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
        // The incoming list is whatever a screen displays, which can include missing songs
        // mixed in among playable ones (SongRow disables tapping them, but leaves them in
        // the list so the row still renders dimmed) — strip them here, once, upstream of
        // every screen's tap handler, so no missing song ever reaches the engine as a
        // MediaItem. The tapped song is identified by id *before* filtering and re-located
        // in the filtered list, since filtering can shift its numeric position.
        val tapped = songs.getOrNull(startIndex) ?: return
        if (tapped.isMissing) return
        val filteredSongs = songs.filterNot { it.isMissing }
        val filteredIndex = filteredSongs.indexOfFirst { it.id == tapped.id }
        if (filteredIndex < 0) return
        if (isQueueCustomized.value && hasActivePlayback()) {
            _queuePrompts.tryEmit(
                QueuePromptEvent(
                    tappedSong = tapped,
                    contextSongs = filteredSongs,
                    startIndex = filteredIndex,
                ),
            )
        } else {
            playQueue(filteredSongs, filteredIndex)
        }
    }

    /**
     * Keep: the customised queue survives untouched, the tapped song is spliced in right
     * after the current item and jumped to, so what the user queued up plays after it. The
     * list the song was tapped from is deliberately discarded — only the song matters here.
     *
     * Uses the blanket [isAlreadyQueued] dedup, not the position-specific check
     * [playNext]/[addToQueue] use — this flow answers a queue-replace prompt rather than an
     * ordinary tap, so it keeps the coarser "anywhere in the queue" guard.
     * Returns `false` without touching the controller when [song] is already the current
     * song or already sits in [upcomingQueue]. Dedup only applies once there's an existing
     * queue to be a duplicate of, matching the [hasActivePlayback] gating on the other two
     * methods.
     *
     * Returns `null` when the engine refused the insert for lack of a session connection
     * — it has already reported that on [errors], so the caller shows nothing.
     */
    fun keepQueueAndPlayNext(song: Song): Boolean? {
        // The queue can have ended between the prompt being raised and answered.
        if (!hasActivePlayback()) {
            playQueue(listOf(song), 0)
            return true
        }
        if (isAlreadyQueued(song.id)) return false
        if (!controller.playNext(song)) return null
        controller.next()
        return true
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

    // Mirrors playQueueJob above: a large-queue reshuffle dispatches its MediaItem
    // build off Main (see ExoPlayerEngine.setShuffleEnabled), so a rapid second toggle can
    // land before the first's off-main work finishes. Cancelling the prior job keeps only
    // the most recent tap's result reaching the controller.
    private var setShuffleEnabledJob: Job? = null

    fun setShuffleEnabled(enabled: Boolean) {
        setShuffleEnabledJob?.cancel()
        setShuffleEnabledJob = viewModelScope.launch { controller.setShuffleEnabled(enabled) }
    }

    fun setRepeatMode(mode: RepeatMode) = controller.setRepeatMode(mode)

    /**
     * Queues [song] directly after the current item — or just plays it when there is no
     * active playback to queue behind. The engine's insert is timeline-only (no prepare,
     * no play), so without this branch "Play Next" against a dead queue would put the song
     * in the timeline and leave the player idle.
     *
     * Unlike [keepQueueAndPlayNext], duplicates elsewhere in the queue are fine — every
     * insert gets its own slot id (see ExoPlayerEngineQueueOps) — so the only case worth
     * asking about is the one a tap could trigger by accident: the song is already sitting
     * in the exact slot this call would insert it into. Returns [QueueAddResult.NeedsConfirmation]
     * without touching the controller when [song] is already next in [upcomingQueue] and
     * [force] is false; the caller re-invokes with `force = true` once the user confirms.
     */
    fun playNext(song: Song, force: Boolean = false): QueueAddResult {
        if (!hasActivePlayback()) {
            playQueue(listOf(song), 0)
            return QueueAddResult.Added
        }
        if (!force && isNextInQueue(song.id)) return QueueAddResult.NeedsConfirmation
        return if (controller.playNext(song)) QueueAddResult.Added else QueueAddResult.Unavailable
    }

    /**
     * Appends [song], with the same fall back to starting playback as [playNext]. The
     * confirmation check only applies once there's an end of the queue for [song] to
     * duplicate — an empty [upcomingQueue] (active playback with nothing queued after it)
     * behaves like [playNext] against an empty queue and adds immediately.
     */
    fun addToQueue(song: Song, force: Boolean = false): QueueAddResult {
        if (!hasActivePlayback()) {
            playQueue(listOf(song), 0)
            return QueueAddResult.Added
        }
        if (upcomingQueue.value.isNotEmpty() && !force && isLastInQueue(song.id)) {
            return QueueAddResult.NeedsConfirmation
        }
        return if (controller.addToQueue(song)) QueueAddResult.Added else QueueAddResult.Unavailable
    }

    /** True when [songId] is the current song or already sits in [upcomingQueue]. */
    private fun isAlreadyQueued(songId: String): Boolean =
        songId == currentSongId.value || upcomingQueue.value.any { it.song.id == songId }

    /** True when [songId] matches [upcomingQueue]'s first entry — the slot [playNext] targets. */
    private fun isNextInQueue(songId: String): Boolean = upcomingQueue.value.firstOrNull()?.song?.id == songId

    /** True when [songId] matches [upcomingQueue]'s last entry — the slot [addToQueue] targets. */
    private fun isLastInQueue(songId: String): Boolean = upcomingQueue.value.lastOrNull()?.song?.id == songId

    /**
     * Moves the [upcomingQueue] entry identified by [slotId] to [toIndex] — the Up Next
     * screen's drag-reorder. [slotId], not a captured index, identifies which row moves — see
     * [PlaybackEngine.moveQueueItem]'s doc comment for why (same argument as [removeQueueItem]).
     */
    fun moveQueueItem(slotId: String, toIndex: Int) = controller.moveQueueItem(slotId, toIndex)

    /**
     * Removes the [upcomingQueue] entry for [slotId] — the Up Next screen's rows.
     *
     * Takes the stable slot id rather than the row index. A row index captured when the
     * user starts a gesture can be stale by the time the call lands: any queue mutation in
     * between (the track advancing, a play-next insert, a second removal in quick succession)
     * shifts every row after it, and the stale index then names a different song. The engine
     * resolves the real position fresh from its own live queue instead, so nothing
     * index-shaped ever crosses that gap.
     */
    fun removeQueueItem(slotId: String) = controller.removeQueueItem(slotId)

    /**
     * Jumps to and plays the [upcomingQueue] entry identified by [slotId] — the Up Next
     * screen's row tap. Identified by slot id, not the tapped row's index, for the same reason
     * as [moveQueueItem]/[removeQueueItem] — see [PlaybackEngine.seekToQueueItem]'s doc comment.
     */
    fun playQueueItem(slotId: String) = controller.seekToQueueItem(slotId)

    /**
     * False both when nothing has been queued and when a queue ran to its end. A natural end
     * leaves the current song set (the player stays up, paused on the last track), so
     * [isQueueEnded] is what distinguishes "ended" from "paused mid-queue" — the song id and
     * item count alone would read as active.
     */
    private fun hasActivePlayback(): Boolean =
        activeSongId.value != null && queue.value.isNotEmpty() && !isQueueEnded.value

    // The bounded reconciliation check started by resume() — see its kdoc. Cancelled by any
    // later resume()/pause() so only the most recent tap's timeout can ever force-correct state.
    private var resumeReconcileJob: Job? = null

    fun pause() {
        resumeReconcileJob?.cancel()
        _isPlaying.value = false
        controller.pause()
    }

    /**
     * Optimistic like [pause]: flips the icon before the MediaController IPC round-trip
     * confirms anything. Unlike pause, a genuine failure here (audio focus denied, a
     * player left in a dead state by a prior fatal error) can mean the engine's own
     * `isPlaying` never flips true — and if it was already `false`, the StateFlow collect in
     * the init block dedupes that as no emission at all, so nothing would ever correct the optimistic
     * `true` on its own. [RESUME_RECONCILE_TIMEOUT_MS] after this call, if nothing has already
     * corrected [_isPlaying] (a real emission from the engine, or a later pause()/resume() tap),
     * the engine's actual current state is checked directly and used to force-correct a
     * still-optimistic `true` that playback never backed up. A genuine late-arriving `true` is
     * never fought: the init-block collector applies it the moment it lands, before or after this
     * check runs either way.
     */
    fun resume() {
        resumeReconcileJob?.cancel()
        _isPlaying.value = true
        controller.resume()
        resumeReconcileJob = viewModelScope.launch {
            delay(RESUME_RECONCILE_TIMEOUT_MS)
            // Already corrected (to false by the real flow, or superseded by a later tap) —
            // nothing left to reconcile.
            if (!_isPlaying.value) return@launch
            if (!controller.isPlaying.first()) {
                _isPlaying.value = false
            }
        }
    }

    fun stop() = controller.stop()

    fun seekTo(positionMs: Long) = controller.seekTo(positionMs)

    fun togglePlayPause() {
        if (_isPlaying.value) pause() else resume()
    }

    override fun onCleared() {
        controller.release()
    }

    /**
     * The `onStart`/`onResume` recovery pass — see [PlaybackEngine.reconcileWithDatabase]'s kdoc.
     * [MainActivity] is the only caller, since this single-Activity app treats `onStart` as its
     * one foregrounding signal (see its own kdoc for why it does not use `ProcessLifecycleOwner`).
     */
    fun reconcileWithDatabase() {
        viewModelScope.launch { controller.reconcileWithDatabase() }
    }

    /**
     * Builds the engine inside [create] rather than holding a prebuilt one. An
     * ExoPlayerEngine opens a MediaController binding to SynthXPlaybackService in its
     * constructor, so constructing one eagerly — as a factory constructor argument —
     * orphans that binding whenever ViewModelProvider finds an existing ViewModel and
     * never calls [create]. Only the Context is held here; it is cheap to rebuild.
     */
    class Factory(private val context: Context) : ViewModelProvider.Factory {
        // Class<T> is erased; this factory only ever builds PlaybackViewModel.
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
            val backgroundArtLoader = PlayerBackgroundArtLoader(context.applicationContext.contentResolver)
            return PlaybackViewModel(controller, backgroundArtLoader::load) as T
        }
    }
}
