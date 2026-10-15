package com.v1per.synthx.playback

import androidx.media3.session.MediaController
import com.v1per.synthx.data.repository.QueueSlot
import com.v1per.synthx.data.repository.QueueSlotRecord

/**
 * The single hook every structural change to the queue runs through: re-derives the queue
 * flows, writes the new structure to storage, and records whether the queue is still a pristine
 * context list or has been hand-modified.
 *
 * "Structural" now means the order store changed, not the timeline — callers apply their edit
 * to [ExoPlayerEngine.orderStore] and then call this. [refreshNavigationState] is part of the
 * sequence for the same reason: `hasNext` is derived from the store's true length and position
 * now, so a mutation that adds or removes the last slot has to re-answer it here rather than
 * waiting for a timeline callback that may never come (the mutation may be entirely outside the
 * loaded window).
 */
internal fun ExoPlayerEngine.onQueueStructureChanged(fresh: Boolean) {
    refreshQueue()
    refreshUpcomingQueue()
    refreshNavigationState()
    persistQueue()
    _isQueueCustomized.value = !fresh
    prefetchArtwork()
}

/**
 * Same refresh/persist/prefetch plumbing as [onQueueStructureChanged], for structural changes
 * that are not a user customisation — currently just a shuffle toggle. A system-driven reorder
 * must not touch [ExoPlayerEngine._isQueueCustomized] in either direction: forcing it true
 * wrongly triggers the Keep/Clear prompt on the next song tap for a user who never hand-reordered
 * anything, and forcing it false would just as wrongly erase a customisation that genuinely
 * happened earlier in the session.
 */
internal fun ExoPlayerEngine.onQueueReordered() {
    refreshQueue()
    refreshUpcomingQueue()
    refreshNavigationState()
    persistQueue()
    prefetchArtwork()
}

/**
 * Persists the **full** order, from the order store — never from the live timeline, which only
 * holds a window and would truncate the saved queue to ~[WINDOW_SIZE] rows.
 *
 * The current slot id is verified against the store before being written: the repository
 * asserts the cursor names one of the slots it was handed in the same commit, so a slot the
 * store no longer contains has to become a null cursor rather than a failed write.
 */
internal fun ExoPlayerEngine.persistQueue() {
    val positionMs = controller?.let { resumePositionMs(it.playbackState, it.currentPosition) } ?: 0L
    val currentSlotId = _currentSlotId.value?.takeIf { orderStore.indexOfSlot(it) >= 0 }
    persistence.saveQueueRecords(orderStore.live, currentSlotId, positionMs)
}

/**
 * Persists baseline + active order + shuffle flag + repeat mode + cursor together as one atomic
 * unit (P4-T6 / F11) — see [QueuePersistence.saveUnifiedQueueState]. Used by every call site that
 * changes more than one of these five pieces at once (a fresh play, a shuffle toggle), so an
 * interrupted write can never leave a torn combination. [persistQueue]/[writeBaselineOrder] etc.
 * remain correct for call sites that only ever touch one piece.
 */
internal fun ExoPlayerEngine.persistUnifiedQueueState() {
    val positionMs = controller?.let { resumePositionMs(it.playbackState, it.currentPosition) } ?: 0L
    val currentSlotId = _currentSlotId.value?.takeIf { orderStore.indexOfSlot(it) >= 0 }
    persistence.saveUnifiedQueueState(
        activeSlots = orderStore.live,
        baselineSlots = orderStore.baseline,
        currentSlotId = currentSlotId,
        positionMs = positionMs,
        shuffleEnabled = _shuffleEnabled.value,
        repeatMode = _repeatMode.value.toPlayerRepeatMode(),
    )
}

/**
 * Rebuilds engine state when reconnecting to a service that was already running with no live
 * `ExoPlayerEngine` instance owning it. NOT actual process death — this same-process
 * `MediaLibraryService` cannot survive that (see [SynthXPlaybackService]'s own kdoc, corrected in
 * P4-T3). The real trigger is the Activity finishing while playback was running (the ViewModel
 * torn down, the process and its foreground service both still alive) or a fresh
 * `ExoPlayerEngine` construction reconnecting to that still-running session. Neither
 * [playQueueInternal] nor [restoreSavedQueueInternal] runs in this path (the latter explicitly
 * no-ops once the timeline already has media items), so without this the engine would come up
 * with an empty order store against a player that is actually playing.
 *
 * Reads the persisted order rather than the live timeline, which is the windowing-era
 * correction to this path: the timeline holds at most [WINDOW_SIZE] items, so adopting it would
 * silently shrink a 2000-song queue to the window that happened to be loaded. `queue_items` is
 * the full order and the engine rewrites it on every structural change, so it is the right
 * thing to adopt; the caller reconciles the already-playing window against it afterwards.
 */
internal suspend fun ExoPlayerEngine.adoptRunningTimeline(ctrl: MediaController) {
    if (ctrl.mediaItemCount == 0) return
    // Read together as one consistent snapshot (P4-T6) — two separate suspending reads here
    // used to leave a window where a write landing in between could hand this path an active
    // order and a baseline from two different generations.
    val (restored, restoredBaseline) = persistence.loadQueueWithBaseline()
    restored ?: return
    restored.slots.forEach { songsBySlotId[it.slotId] = it.song }
    restoredBaseline.forEach { songsBySlotId.putIfAbsent(it.slotId, it.song) }
    orderStore.restore(
        live = restored.slots.map { QueueSlotRecord(it.slotId, it.song.id) },
        baseline = restoredBaseline.map { QueueSlotRecord(it.slotId, it.song.id) },
    )
    _shuffleEnabled.value = restored.shuffleEnabled
}

/**
 * Whether [ExoPlayerEngine.next] would move to a genuinely different item, answered from the
 * order store's true length and position rather than `MediaController.hasNextMediaItem()`.
 *
 * That distinction is load-bearing under windowing: the loaded timeline ends [WINDOW_FORWARD]
 * or so ahead of the current song, so the controller would report "no next item" while hundreds
 * of songs are still queued, and the skip-forward button would go dead mid-queue.
 */
internal fun ExoPlayerEngine.refreshNavigationState() {
    _hasNext.value = nextLiveIndex() != null
    // System transport availability is derived from the same answers (P9-T3): the service's
    // player wrapper re-publishes its commands only if they actually changed.
    SystemNavigationBridge.notifyChanged()
}

/**
 * The next position in the full order, honouring repeat mode — the app-side replacement for
 * media3's own next-window traversal, which now only sees the window.
 *
 * Repeat One returns the following slot (wrapping) rather than the current one, matching the
 * product requirement the old `nextWindowIndexForRepeatOne` encoded: media3 treats Repeat One
 * as Repeat Off for navigation and would no-op at the end, but a track the user is repeating is
 * still one they expect to be able to skip off of.
 */
internal fun ExoPlayerEngine.nextLiveIndex(): Int? = nextIndexInOrder(
    count = orderStore.size,
    currentIndex = orderStore.indexOfSlot(_currentSlotId.value),
    repeatMode = _repeatMode.value,
)

/**
 * The pure arithmetic behind [nextLiveIndex], split out so the one rule that three separate
 * windowing traps all reduce to — "next" is a question about the full order, never about the
 * loaded timeline — is unit-testable on its own:
 *
 *  - `hasNext` (skip-forward enablement) must stay true mid-queue even when the window's last
 *    item is playing;
 *  - the `STATE_ENDED` handler must only stop when this returns null, not when the timeline
 *    runs dry;
 *  - Repeat All must wrap at [count] - 1 rather than at the window's end, and Repeat One must
 *    still offer a genuinely different next item to skip onto.
 */
internal fun nextIndexInOrder(count: Int, currentIndex: Int, repeatMode: RepeatMode): Int? {
    if (count <= 0 || currentIndex !in 0 until count) return null
    if (currentIndex + 1 < count) return currentIndex + 1
    return when (repeatMode) {
        RepeatMode.All, RepeatMode.One -> 0
        RepeatMode.Off -> null
    }
}

/**
 * Safety net for `STATE_ENDED` arriving while the real queue still has songs left — i.e. the
 * *window* was exhausted, not the queue. Rebuilds the window at the true next slot and keeps
 * playing; returns false only when the queue is genuinely finished, which is the one case that
 * should still stop playback.
 *
 * Hysteresis in [maintainWindow] should keep this from ever firing during ordinary playback,
 * but "should" is not a guarantee worth betting silent mid-queue stops on.
 */
internal fun ExoPlayerEngine.advancePastWindowEnd(): Boolean {
    val target = nextLiveIndex() ?: return false
    return rebuildWindowAround(target, positionMs = 0L, play = true)
}

/**
 * Rebuilds `queue` — the full live order, hydrated to real songs — from the order store. Only
 * a structural change needs this; a plain track transition moves the cursor, not the order, so
 * it refreshes [refreshCurrentIndex] alone rather than re-projecting the whole list.
 */
internal fun ExoPlayerEngine.refreshQueue() {
    _queue.value = hydrate(orderStore.live)
    refreshCurrentIndex()
}

/** Position of the playing slot within the full order — not within the loaded window. */
internal fun ExoPlayerEngine.refreshCurrentIndex() {
    _currentIndex.value = orderStore.indexOfSlot(_currentSlotId.value)
}

/**
 * Rebuilds `upcomingQueue` — everything after the current slot in the full order, hydrated for
 * the Up Next list. Deliberately the whole remainder, not the loaded window: the store is the
 * source of truth, so the user keeps seeing and scrolling the real queue while the player holds
 * only a slice of it. This is also why the display indices the UI hands back
 * (`moveQueueItem`/`seekToQueueItem`) are order-store offsets now and need no translation into
 * timeline positions.
 */
internal fun ExoPlayerEngine.refreshUpcomingQueue() {
    _upcomingQueue.value = hydrate(orderStore.upcomingFrom(_currentSlotId.value))
}

/**
 * Records to [QueueSlot]s via the engine's song cache. A slot with no cached song is skipped
 * rather than faked — the same tolerance the old timeline walk had, though the cache is
 * populated for every slot in the store, so it should never trigger.
 */
private fun ExoPlayerEngine.hydrate(records: List<QueueSlotRecord>): List<QueueSlot> =
    records.mapNotNull { record ->
        songsBySlotId[record.slotId]?.let { song -> QueueSlot(record.slotId, song) }
    }

/**
 * Stateless — takes the controller explicitly rather than as an [ExoPlayerEngine] extension
 * receiver, so callers with no engine instance in hand can use it too. Returns the item's index
 * within the loaded window, or null when the slot is not currently loaded at all, which under
 * windowing is an ordinary answer rather than an error.
 */
internal fun indexOfMediaId(ctrl: MediaController, mediaId: String): Int? {
    for (index in 0 until ctrl.mediaItemCount) {
        if (ctrl.getMediaItemAt(index).mediaId == mediaId) return index
    }
    return null
}

/**
 * The pure accept/reject decision behind [seekToQueueItemInternal], split out so it is
 * unit-testable without a real `MediaController` (P4-T8 / R3-F04) — matching the
 * `nextIndexInOrder`/`idleRecoveryActionFor` precedent for this kind of extraction. Rejects
 * (returns `null`) whenever there is no current slot, [slotId] resolved to nothing (the tapped
 * row's slot no longer exists in the order at all — deleted, or already played past), or it
 * resolved to the currently-playing slot or anything before it (not a valid Up Next target).
 */
internal fun resolveUpcomingSeekTarget(currentIndex: Int, slotIndex: Int): Int? =
    if (currentIndex < 0 || slotIndex <= currentIndex) null else slotIndex
