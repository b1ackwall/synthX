package com.v1per.synthx.playback

import com.v1per.synthx.data.repository.QueueSlot
import com.v1per.synthx.data.repository.QueueSlotRecord
import com.v1per.synthx.data.repository.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Real bodies for [ExoPlayerEngine]'s [PlaybackEngine] queue-mutation overrides — the class
 * itself keeps only one-line delegators (`override fun playQueue(...) = playQueueInternal(...)`)
 * because Kotlin requires `override` members to be declared inside the class body; these
 * `xxxInternal` extension functions are where the actual logic lives, relocated here purely to
 * keep `ExoPlayerEngine.kt` shorter.
 *
 * Every one of them now follows the same two-step shape: **mutate the order store, then
 * reconcile the window.** The store edit is a cheap pure list operation that always succeeds
 * regardless of queue size or where in the queue it lands; [maintainWindow] afterwards is a
 * no-op when the edit fell outside the loaded window, and a handful of bounded controller calls
 * when it did not. That replaces the old shape, where every mutation had to translate a display
 * position into a real timeline index and could silently no-op if the target was not loaded.
 *
 * Building `MediaItem`s no longer needs its own dispatcher hop either: the most that is ever
 * built at once is [WINDOW_SIZE] of them, where it used to be one per song in the queue.
 */

/**
 * Shuffle ON: the current slot stays first, everything else in baseline (played or not) is
 * reshuffled after it. Shuffle OFF: the current slot stays first, followed by whatever comes
 * after it in baseline, plus anything added via Play Next / Add to Queue during the shuffle-ON
 * session just ending; that result also becomes the new baseline.
 *
 * The reorder itself is now a pure permutation of the order store's records — no `MediaItem`s,
 * no timeline rebuild — followed by [maintainWindow] reconciling the ~[WINDOW_SIZE] items that
 * are actually loaded. The currently-playing item is never removed and re-added by that
 * reconciliation, so unlike the old large-queue `setMediaItems` path this does not tear down
 * and rebuffer the playing track, and unlike the old move-based path it does not scale with
 * queue size. That is what let the size threshold between those two paths be deleted outright.
 */
internal suspend fun ExoPlayerEngine.setShuffleEnabledInternal(enabled: Boolean) {
    withContext(Dispatchers.Main.immediate) {
        if (enabled == _shuffleEnabled.value) return@withContext
        controller ?: return@withContext
        val currentSlotId = _currentSlotId.value ?: return@withContext

        val reordered = if (enabled) {
            orderStore.shuffle(currentSlotId)
        } else {
            // writeBaseline=false: baseline is rewritten again as part of the SAME atomic write
            // below (P4-T6 / F11) — letting this land as its own independent write would reopen
            // exactly the torn-write window unification exists to close.
            orderStore.unshuffle(currentSlotId, writeBaseline = false)
        }
        if (!reordered) return@withContext

        // Shuffle OFF only: unshuffling permanently drops baseline history before the current
        // song, so anything else still cached is now stale. Shuffle ON's result is a subset of
        // what is already tracked, so nothing needs pruning there.
        if (!enabled) {
            songsBySlotId.keys.retainAll(orderStore.live.mapTo(HashSet()) { it.slotId })
        }

        _shuffleEnabled.value = enabled
        maintainWindow(force = true)
        refreshQueue()
        refreshUpcomingQueue()
        refreshNavigationState()
        // Baseline + active order + shuffle + repeat + cursor, all in one atomic write
        // (P4-T6 / F11) — replaces the old shape (a separate updateShuffleEnabled write, plus
        // onQueueReordered's separate persistQueue, plus — while turning shuffle off —
        // unshuffle's own separate baseline write): three independent commits for one logical
        // change, any pair of which a process death between them could leave mismatched.
        persistUnifiedQueueState()
        prefetchArtwork()
    }
}

/**
 * While shuffle is off, [song] is also woven into baseline at the matching relative slot —
 * permanently, per the locked spec (see [QueueOrderStore.insertAfterCurrent]). While shuffle is
 * on, baseline is left untouched; if shuffle later turns off, [song] is folded into the new
 * baseline then (see [setShuffleEnabledInternal]), not here.
 */
internal fun ExoPlayerEngine.playNextInternal(song: Song) {
    controller ?: return
    val slot = QueueSlot(UUID.randomUUID().toString(), song)
    songsBySlotId[slot.slotId] = song
    orderStore.insertAfterCurrent(
        record = QueueSlotRecord(slot.slotId, song.id),
        currentSlotId = _currentSlotId.value,
        mirrorToBaseline = !_shuffleEnabled.value,
    )
    maintainWindow(force = true)
    onQueueStructureChanged(fresh = false)
}

/**
 * Same baseline-weaving rule as [playNextInternal], appended at the end instead of next-slot.
 * On a large queue the new slot lands far outside the loaded window, so [maintainWindow] has
 * nothing to do and this costs no controller work at all.
 */
internal fun ExoPlayerEngine.addToQueueInternal(song: Song) {
    controller ?: return
    val slot = QueueSlot(UUID.randomUUID().toString(), song)
    songsBySlotId[slot.slotId] = song
    orderStore.append(
        record = QueueSlotRecord(slot.slotId, song.id),
        mirrorToBaseline = !_shuffleEnabled.value,
    )
    maintainWindow(force = true)
    onQueueStructureChanged(fresh = false)
}

/**
 * [slotId] identifies the item being moved (P4-T8 / R3-F04) — resolved against the order store's
 * *current* position fresh at execution time via [QueueOrderStore.moveUpcoming], never trusted
 * from a caller-captured index. A UI gesture can be several async hops (a drag, a suspend, a
 * ViewModel call) away from the moment this actually runs, and any queue mutation landing in
 * that window (auto-advance, a deletion, another reorder) shifts every row after it — a stale
 * index would then move whatever now occupies that position instead of the row the user actually
 * dragged. If [slotId] no longer exists in the current order at all, [QueueOrderStore.moveUpcoming]
 * rejects the move outright (returns `false`) rather than falling back to some other occurrence.
 * A move to the slot's own position is rejected the same way, so it never marks the queue
 * customised, refreshes the window or persists.
 *
 * [toIndex] is still a plain position in `upcomingQueue` — see [QueueOrderStore.moveUpcoming]'s
 * kdoc for why only the source needs identity resolution.
 *
 * While shuffle is off the same move is mirrored into baseline. While shuffle is on this only
 * touches the live order; the reorder evaporates the moment shuffle turns off, per spec.
 */
internal fun ExoPlayerEngine.moveQueueItemInternal(slotId: String, toIndex: Int) {
    controller ?: return
    val moved = orderStore.moveUpcoming(
        slotId = slotId,
        toDisplayIndex = toIndex,
        currentSlotId = _currentSlotId.value,
        mirrorToBaseline = !_shuffleEnabled.value,
    )
    if (!moved) return
    maintainWindow(force = true)
    onQueueStructureChanged(fresh = false)
}

/**
 * Removes the item identified by [slotId] — no position is accepted from the caller. A position
 * captured further up (in the UI, at gesture time) can be invalidated by any queue mutation
 * that lands first — every row after the mutation shifts — and would then name whichever song
 * slid into that slot, removing it permanently. Resolving by stable slot id against the order
 * store closes that gap, and does so whether or not the slot is currently loaded.
 *
 * The currently-playing slot is explicitly not removable, matching the old behaviour (it is
 * never one of the Up Next rows the gesture can reach) and keeping the window's anchor alive.
 *
 * Always permanent, regardless of shuffle state — see [QueueOrderStore.remove].
 */
internal fun ExoPlayerEngine.removeQueueItemInternal(slotId: String) {
    controller ?: return
    if (slotId == _currentSlotId.value) return
    if (!orderStore.remove(slotId)) return

    songsBySlotId.remove(slotId)
    artworkLoadedSlotIds.remove(slotId)

    maintainWindow(force = true)
    onQueueStructureChanged(fresh = false)
}

/**
 * [slotId] identifies the tapped row (P4-T8 / R3-F04) — resolved against the order store's
 * *current* position fresh at execution time, never a caller-captured `upcomingQueue` index. A
 * tap is a Compose click plus, in production, a ViewModel hop before this actually runs; a queue
 * mutation landing in that window (auto-advance, a deletion, another reorder) would otherwise
 * shift every row after it, and a stale index would land on whichever song now occupies that
 * position rather than the one the user actually tapped. If [slotId] no longer exists in the
 * current order at all (deleted, or the transition already moved past it) this rejects cleanly —
 * a no-op, never applied to some other occurrence — and if it resolves to the currently-playing
 * slot or anything before it, that is likewise not a valid Up Next tap target.
 *
 * A target already inside the loaded window is a plain seek; anything further out rebuilds the
 * window around it first — an interruption, but a deliberate jump the user asked for, and bounded
 * to [WINDOW_SIZE] items rather than the whole queue.
 */
internal fun ExoPlayerEngine.seekToQueueItemInternal(slotId: String) {
    controller ?: return
    val currentIndex = orderStore.indexOfSlot(_currentSlotId.value)
    val target = resolveUpcomingSeekTarget(currentIndex, orderStore.indexOfSlot(slotId)) ?: return
    seekToLiveIndex(target, play = true)
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
