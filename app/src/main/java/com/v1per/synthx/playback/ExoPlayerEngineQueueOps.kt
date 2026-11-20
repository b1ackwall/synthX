package com.v1per.synthx.playback

import android.util.Log
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
 * Every one of them follows the same two-step shape: **mutate the order store, then
 * reconcile the window.** The store edit is a cheap pure list operation that always succeeds
 * regardless of queue size or where in the queue it lands; [maintainWindow] afterwards is a
 * no-op when the edit fell outside the loaded window, and a handful of bounded controller calls
 * when it did not. No mutation translates a display position into a timeline index, so an edit
 * to a slot that is not loaded still lands.
 *
 * Building `MediaItem`s needs no dispatcher hop of its own: the most that is ever built at once
 * is [WINDOW_SIZE] of them.
 */

/**
 * Shuffle ON keeps the history and the current slot in place and shuffles what follows; under
 * Repeat All the current slot leads and every other slot is shuffled after it. Baseline becomes
 * the order from before the tap. Shuffle OFF keeps the history (under Repeat All only its
 * extras), then the current slot, the Play Next extras newest first, the album in baseline order
 * from the current slot with a wrap, and the Add to Queue extras oldest first; that result also
 * becomes the new baseline. See [QueueOrderStore.shuffle] and [QueueOrderStore.unshuffle].
 *
 * Always executes when called: asking before discarding a hand reorder is the ViewModel's job
 * ([PlaybackViewModel.setShuffleEnabled]). A toggle never changes the current slot, its position
 * or play state; the store refuses, changing nothing, any computed order that would not hold
 * exactly the same slots, and the refusal is logged.
 *
 * The reorder itself is a pure permutation of the order store's records — no `MediaItem`s,
 * no timeline rebuild — followed by [maintainWindow] reconciling the ~[WINDOW_SIZE] items that
 * are actually loaded. The currently-playing item is never removed and re-added by that
 * reconciliation, so this does not tear down and rebuffer the playing track, and its cost does
 * not scale with queue size.
 */
internal suspend fun ExoPlayerEngine.setShuffleEnabledInternal(enabled: Boolean) {
    withContext(Dispatchers.Main.immediate) {
        if (enabled == _shuffleEnabled.value) return@withContext
        controller ?: return@withContext
        val currentSlotId = _currentSlotId.value ?: return@withContext
        if (orderStore.indexOfSlot(currentSlotId) < 0) return@withContext

        val repeatMode = _repeatMode.value
        val reordered = if (enabled) {
            orderStore.shuffle(currentSlotId, repeatMode)
        } else {
            // writeBaseline=false: baseline is rewritten again as part of the SAME atomic write
            // below — letting this land as its own independent write would reopen
            // exactly the torn-write window unification exists to close.
            orderStore.unshuffle(currentSlotId, repeatMode, writeBaseline = false)
        }
        if (!reordered) {
            Log.w(
                ExoPlayerEngine.TAG,
                "Shuffle ${if (enabled) "on" else "off"} refused for slot $currentSlotId: the new order of " +
                    "${orderStore.size} slots would not keep the same slots and cursor; the order is unchanged",
            )
            return@withContext
        }

        // Baseline is rebuilt from the live order by either toggle, so a slot only baseline held is
        // gone from the store and its cached song with it.
        songsBySlotId.keys.retainAll(orderStore.live.mapTo(HashSet()) { it.slotId })

        _shuffleEnabled.value = enabled
        maintainWindow(force = true)
        refreshQueue()
        refreshUpcomingQueue()
        refreshNavigationState()
        refreshQueueEnded()
        // Baseline + active order + shuffle + repeat + cursor, all in one atomic write: separate
        // writes would be independent commits for one logical change, and a process death
        // between any two of them could leave them mismatched.
        persistUnifiedQueueState()
        prefetchArtwork()
    }
}

/**
 * While shuffle is off, [song] is also woven into baseline at the matching relative slot —
 * permanently, per the locked spec (see [QueueOrderStore.insertAfterCurrent]). While shuffle is
 * on, baseline is left untouched and [song] is a Play Next extra; if shuffle later turns off, it
 * is placed right after the current slot then (see [setShuffleEnabledInternal]), not here.
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
 * Same baseline-weaving rule as [playNextInternal], appended at the end instead of next-slot; while
 * shuffle is on [song] is an Add to Queue extra, placed after the album when shuffle turns off.
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
 * [slotId] identifies the item being moved — resolved against the order store's
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
 * touches the live order and sets the reorder flag: turning shuffle off restores the original
 * order, which [PlaybackViewModel.setShuffleEnabled] asks the user to confirm first.
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
 * The currently-playing slot is explicitly not removable: it is never one of the Up Next rows
 * the gesture can reach, and keeping it keeps the window's anchor alive.
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
 * [slotId] identifies the tapped row — resolved against the order store's
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
