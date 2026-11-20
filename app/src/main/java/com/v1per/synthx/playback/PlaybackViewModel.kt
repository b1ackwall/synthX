package com.v1per.synthx.playback

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.data.repository.QueueSlot
import com.v1per.synthx.ui.components.next
import com.v1per.synthx.ui.player.PlayerAltStageStyle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.random.Random

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
    // How long a saved-queue restore counts as pending at most.
    restoreWaitTimeoutMs: Long = QUEUE_RESTORE_WAIT_TIMEOUT_MS,
    // How long a held Play Next, Add to Queue or Keep waits at most for the restore and a queue load.
    queueActionWaitTimeoutMs: Long = QUEUE_ACTION_WAIT_TIMEOUT_MS,
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
    // screens that never collect currentSongId: there the lazy one is stale.
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

    // Screen-scoped for the player's Up Next list. The duplicate checks read upcomingNow instead,
    // since nothing guarantees a collector of this one when they run.
    val upcomingQueue: StateFlow<List<QueueSlot>> = controller.upcomingQueue
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Eager twin of upcomingQueue for the duplicate checks, which read .value synchronously. The
    // engine publishes this list on every change anyway, so following it costs no extra work.
    private val upcomingNow: StateFlow<List<QueueSlot>> = controller.upcomingQueue
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val shuffleEnabled: StateFlow<Boolean> = controller.shuffleEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    // Eager because setShuffleEnabled reads .value on every tap, collected or not.
    val shuffleReorderedByUser: StateFlow<Boolean> = controller.shuffleReorderedByUser
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val restoreGate = QueueRestoreGate(viewModelScope, restoreWaitTimeoutMs)

    // The view model's queue loads, one at a time; see playQueue.
    private val loads = QueueLoadTracker(viewModelScope)

    // Holds Play Next, Add to Queue and Keep while the restore or a queue load is pending.
    private val actionWait =
        QueueActionWait(viewModelScope, restoreGate.state, loads.inFlight, queueActionWaitTimeoutMs)

    /** Where the saved-queue restore stands; see [restoreSavedQueue]. */
    val queueRestoreState: StateFlow<QueueRestoreState> = restoreGate.state

    // Held here rather than in the composition, so the question survives a configuration change.
    private val _shuffleOffConfirmation = MutableStateFlow(false)

    /** True while "Restore the original order?" waits for an answer; see [setShuffleEnabled]. */
    val shuffleOffConfirmation: StateFlow<Boolean> = _shuffleOffConfirmation.asStateFlow()

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

    // The engine's StateFlow as it is, never a stateIn copy: a copy starts at its default, and Up
    // Next would read the catch-up to the real value as a new generation and scroll to the top.
    val queueGeneration: StateFlow<Long> = controller.queueGeneration

    val currentPosition: StateFlow<Long> = controller.currentPosition
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    val duration: StateFlow<Long> = controller.duration
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    // Passed through as a plain Flow, unlike the state above: these are one-off events,
    // and stateIn/shareIn would replay the last failure to every new collector — showing
    // a stale snackbar on every navigation back to the scaffold.
    val errors: Flow<PlaybackError> = controller.errors

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

    // Stays a plain fun so every UI call site keeps working — the engine's suspend
    // artwork work is launched into viewModelScope (Main), which is also the
    // dispatcher MediaController requires when the engine resumes.
    // A non-null shuffle is applied by the engine as part of the same queue replacement.
    // Every fresh play closes a pending Keep/Clear or Replace prompt, and the newest load cancels
    // any earlier one still building its artwork or waiting for the session: only the most recent
    // tap may reach controller.playQueue's setMediaItems.
    fun playQueue(songs: List<Song>, startIndex: Int, shuffle: Boolean? = null) {
        prompts.clearPending()
        loads.launch { controller.playQueue(songs, startIndex, shuffle) }
    }

    // The song-tap and Play/Shuffle entry points and their prompts.
    private val prompts = QueuePromptCoordinator(
        controller = controller,
        isQueueCustomized = { isQueueCustomized.value },
        hasActivePlayback = ::hasActivePlayback,
        isNextInQueue = ::isNextInQueue,
        playQueue = ::playQueue,
        holdWhilePending = actionWait::holdWhilePending,
        resume = ::resume,
    )

    /** The pending Keep/Clear prompt of a song tap; see [QueuePromptCoordinator]. */
    val queuePrompt: StateFlow<QueuePromptEvent?> = prompts.queuePrompt

    /** The pending Replace prompt of a Play or Shuffle button; see [QueuePromptCoordinator]. */
    val queueReplacePrompt: StateFlow<QueueReplacePromptEvent?> = prompts.queueReplacePrompt

    /** The pending duplicate confirmations of the song-actions menu. */
    val queueDuplicateConfirmations = QueueDuplicateConfirmations()

    /** Play button: the playable [songs] in list order from the first, shuffle ending up off. */
    fun playAll(songs: List<Song>) = prompts.requestPlayAll(playAllRequest(songs, shuffle = false, random))

    /** Shuffle button: a random first song with the rest shuffled after it, shuffle ending up on. */
    fun shuffleAll(songs: List<Song>) = prompts.requestPlayAll(playAllRequest(songs, shuffle = true, random))

    /** Every song tap; see [QueuePromptCoordinator.requestPlayQueue]. */
    fun requestPlayQueue(songs: List<Song>, startIndex: Int) = prompts.requestPlayQueue(songs, startIndex)

    /** Keep for [prompt]; see [QueuePromptCoordinator.keepQueueAndPlayNext]. */
    fun keepQueueAndPlayNext(prompt: QueuePromptEvent): Boolean? = prompts.keepQueueAndPlayNext(prompt)

    /** Clear: plays [songs] from [startIndex] as a fresh queue. */
    fun clearQueueAndPlay(songs: List<Song>, startIndex: Int) = prompts.clearQueueAndPlay(songs, startIndex)

    /** Keep on the pending [queuePrompt]; true shows "Playing next", null shows nothing. */
    fun answerKeep(): Boolean? = prompts.answerKeep()

    fun answerClear() = prompts.answerClear()

    fun dismissQueuePrompt() = prompts.dismissQueuePrompt()

    fun answerReplace() = prompts.answerReplace()

    fun dismissQueueReplacePrompt() = prompts.dismissQueueReplacePrompt()

    /**
     * Loads last session's queue, paused and ready. Safe to call more than once — the
     * engine ignores it once a timeline exists. While it runs, [queueRestoreState] is Pending.
     */
    fun restoreSavedQueue() = restoreGate.start { controller.restoreSavedQueue() }

    /**
     * Holds an action while the restore (only with no [active] playback) or a queue load is pending;
     * [applied] then runs it against the queue they leave and returns false when there is none, and
     * [song] plays alone. Returns false, holding nothing, when nothing is pending.
     */
    private fun heldUntilSettled(active: Boolean, song: Song, applied: suspend () -> Boolean): Boolean =
        actionWait.holdWhilePending(waitsForRestore = !active) { if (!applied()) playQueue(listOf(song), 0) }

    /** Called when the app goes to the background, so the resume point survives a kill. */
    fun persistPlaybackState() = controller.persistPlaybackState()

    fun next() = controller.next()

    fun previous() = controller.previous()

    /**
     * The in-app shuffle toggle. Turning shuffle off after the user reordered the shuffled queue
     * would put the moved songs back in their original order, so that case only raises
     * [shuffleOffConfirmation]; [confirmShuffleOff] then toggles, [dismissShuffleOffConfirmation]
     * leaves shuffle, the flag and the queue as they are. Every other case toggles directly.
     */
    fun setShuffleEnabled(enabled: Boolean) {
        if (!enabled && shuffleReorderedByUser.value) {
            _shuffleOffConfirmation.value = true
            return
        }
        launchShuffleToggle(enabled)
    }

    /** The confirmed answer to [shuffleOffConfirmation]: shuffle off, original order back. */
    fun confirmShuffleOff() {
        _shuffleOffConfirmation.value = false
        launchShuffleToggle(false)
    }

    /** Keep shuffle on, or Back: closes [shuffleOffConfirmation] and changes nothing else. */
    fun dismissShuffleOffConfirmation() {
        _shuffleOffConfirmation.value = false
    }

    // Shared by setShuffleEnabled and confirmShuffleOff. The engine's toggle has no suspension
    // point and viewModelScope runs on Main.immediate, so a toggle started from the main thread
    // runs to completion before this returns, and a second toggle always sees the first's result.
    private fun launchShuffleToggle(enabled: Boolean) {
        viewModelScope.launch { controller.setShuffleEnabled(enabled) }
    }

    /**
     * The shuffle button: the opposite of the engine's shuffle flag as it stands when this runs, not
     * of a value a screen last drew, so two quick taps are two toggles. Turning shuffle off goes
     * through the same guard as [setShuffleEnabled]; while that question is open, another tap
     * changes nothing and does not ask again. Turning it on never asks.
     */
    fun toggleShuffle() {
        viewModelScope.launch {
            if (!controller.shuffleEnabled.first()) {
                controller.setShuffleEnabled(true)
                return@launch
            }
            if (_shuffleOffConfirmation.value) return@launch
            if (controller.shuffleReorderedByUser.first()) {
                _shuffleOffConfirmation.value = true
                return@launch
            }
            controller.setShuffleEnabled(false)
        }
    }

    fun setRepeatMode(mode: RepeatMode) = controller.setRepeatMode(mode)

    /**
     * The repeat button: Off, All, One, then Off again, stepping from the engine's current repeat
     * mode ([PlaybackController.currentRepeatMode]) when this runs, so two quick taps are two steps.
     */
    fun cycleRepeat() = controller.setRepeatMode(controller.currentRepeatMode().next())

    /**
     * Queues [song] directly after the current item — or just plays it when there is no
     * active playback to queue behind. While the saved queue is restoring or a queue load is
     * starting, it answers [QueueAddResult.Added] at once and decides after them ([heldUntilSettled]).
     * The engine's insert is timeline-only (no prepare, no play), so without this branch "Play Next"
     * against a dead queue would put the song in the timeline and leave the player idle.
     *
     * Duplicates elsewhere in the queue are fine — every
     * insert gets its own slot id (see ExoPlayerEngineQueueOps) — so the only case worth
     * asking about is the one a tap could trigger by accident: the song is already sitting
     * in the exact slot this call would insert it into. Returns [QueueAddResult.NeedsConfirmation]
     * without touching the controller when [song] is already the first Up Next entry and
     * [force] is false; the caller re-invokes with `force = true` once the user confirms.
     */
    fun playNext(song: Song, force: Boolean = false): QueueAddResult {
        val active = hasActivePlayback()
        if (heldUntilSettled(active, song) { controller.playNextAfterWait(song, force) }) return QueueAddResult.Added
        if (!active) {
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
        val active = hasActivePlayback()
        if (heldUntilSettled(active, song) { controller.addToQueueAfterWait(song, force) }) return QueueAddResult.Added
        if (!active) {
            playQueue(listOf(song), 0)
            return QueueAddResult.Added
        }
        if (upcomingNow.value.isNotEmpty() && !force && isLastInQueue(song.id)) {
            return QueueAddResult.NeedsConfirmation
        }
        return if (controller.addToQueue(song)) QueueAddResult.Added else QueueAddResult.Unavailable
    }

    /** True when [songId] is the first Up Next entry — the slot [playNext] targets. */
    private fun isNextInQueue(songId: String): Boolean = upcomingNow.value.firstOrNull()?.song?.id == songId

    /** True when [songId] is the last Up Next entry — the slot [addToQueue] targets. */
    private fun isLastInQueue(songId: String): Boolean = upcomingNow.value.lastOrNull()?.song?.id == songId

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

    fun seekTo(positionMs: Long) = controller.seekTo(positionMs)

    fun togglePlayPause() {
        if (_isPlaying.value) pause() else resume()
    }

    override fun onCleared() {
        controller.release()
    }

    /**
     * The re-sync each time the Activity reaches STARTED — see
     * [PlaybackEngine.reconcileWithDatabase]'s kdoc. [MainActivity] is the only caller, since this
     * single-Activity app treats reaching STARTED as its one foregrounding signal (see its own kdoc
     * for why it does not use `ProcessLifecycleOwner`).
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
