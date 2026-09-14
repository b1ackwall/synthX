package com.v1per.synthx.playback

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.v1per.synthx.data.repository.QueueSlotRecord

/**
 * How far behind the currently-playing slot the live timeline is kept loaded. Bounds how far
 * `previous()` can walk back before it hits the rear edge and simply restarts the current item
 * instead — an accepted, deliberate behaviour change from the old full-timeline player, which
 * could walk back through a whole session. The window is deliberately NOT extended backwards on
 * a `previous()` press: doing so would reintroduce a timeline mutation in exactly the
 * interactive moment windowing exists to keep cheap.
 */
internal const val WINDOW_BACK = 10

/** How far ahead of the currently-playing slot the live timeline is kept loaded. */
internal const val WINDOW_FORWARD = 40

/**
 * The most `MediaItem`s that can ever be live in the player at once, regardless of how large
 * the real queue is. This is the whole point: every `MediaController`/`ExoPlayerImpl` playlist
 * mutation allocates a `Timeline.Window` and a `Timeline.Period` per loaded item on both sides
 * of the session IPC, so the cost of a mutation is O(loaded items), not O(queue). Capping the
 * loaded count caps that cost — and a reorder, which used to be O(N) mutations over N items
 * (the O(N-squared) blow-up that OOMed a ~1500-song shuffle), becomes O(51-squared) worst case.
 */
internal const val WINDOW_SIZE = WINDOW_BACK + WINDOW_FORWARD + 1

/**
 * Hysteresis: a playback-advance refill only happens once lookahead has decayed to less than
 * this, rather than trimming and re-extending by one item on every single track transition. So
 * the steady state is one small add/remove pair roughly every [WINDOW_REFILL_HEADROOM]
 * transitions, and lookahead never drops near zero — which is also what keeps media3 from ever
 * reaching the window's own end and applying its repeat-all wrap there (see [computeWindow]).
 */
internal const val WINDOW_REFILL_HEADROOM = WINDOW_FORWARD / 2

/** One reconciliation step from the loaded timeline towards the desired window. */
internal sealed interface WindowEdit {
    /** Half-open, matching `Player.removeMediaItems`. */
    data class Remove(val fromIndex: Int, val toIndexExclusive: Int) : WindowEdit
    data class Insert(val index: Int, val records: List<QueueSlotRecord>) : WindowEdit
    data class Move(val fromIndex: Int, val toIndex: Int) : WindowEdit
}

/**
 * The slice of [live] that should be loaded into the player, given that [currentIndex] is
 * playing. Pure, so the wraparound and clamping rules are unit-testable without a controller.
 *
 * Three cases, in order:
 *  1. The whole queue fits in a window — return it untouched. Small queues therefore behave
 *     exactly as they did before windowing existed, including letting media3's own
 *     `REPEAT_MODE_ALL` do the end-of-queue wrap, and including `previous()` reaching the very
 *     first song.
 *  2. [wrapAround] (repeat-all on a queue too big to fit) — take [WINDOW_SIZE] consecutive
 *     slots modulo the queue length. Near the true end this genuinely loads the true *start*
 *     after the last song, so repeat-all wraps around the real queue rather than around the
 *     window. As the current index advances the slice slides by one, keeping the relative order
 *     of everything retained, so the wrap costs no extra churn.
 *  3. Otherwise — the plain clamped slice, which is simply shorter near either end.
 */
internal fun computeWindow(
    live: List<QueueSlotRecord>,
    currentIndex: Int,
    wrapAround: Boolean,
): List<QueueSlotRecord> {
    val count = live.size
    if (count == 0) return emptyList()
    if (count <= WINDOW_SIZE) return live
    if (currentIndex !in 0 until count) return live.subList(0, WINDOW_SIZE)

    val start = currentIndex - WINDOW_BACK
    if (!wrapAround) {
        return live.subList(
            start.coerceAtLeast(0),
            (currentIndex + WINDOW_FORWARD + 1).coerceAtMost(count),
        )
    }
    // count > WINDOW_SIZE here, so these WINDOW_SIZE modular indices are all distinct.
    return List(WINDOW_SIZE) { offset -> live[((start + offset) % count + count) % count] }
}

/**
 * Whether an advance-driven refill is worth doing yet — see [WINDOW_REFILL_HEADROOM]. A forced
 * refill (any queue mutation, shuffle toggle, repeat change) skips this entirely, because those
 * change the window's *contents*, not just how much of it is still ahead.
 *
 * `loadedAhead >= desiredAhead` short-circuits the true end of a queue with repeat off: there
 * is nothing more to load, so decaying lookahead must not trigger a refill on every transition.
 */
internal fun windowNeedsRefill(
    loaded: List<String>,
    desired: List<QueueSlotRecord>,
    currentSlotId: String,
): Boolean {
    val loadedIndex = loaded.indexOf(currentSlotId)
    if (loadedIndex < 0) return true
    val desiredIndex = desired.indexOfFirst { it.slotId == currentSlotId }
    if (desiredIndex < 0) return true
    val loadedAhead = loaded.size - 1 - loadedIndex
    val desiredAhead = desired.size - 1 - desiredIndex
    if (loadedAhead >= desiredAhead) return false
    return loadedAhead < WINDOW_REFILL_HEADROOM
}

/**
 * The minimal edit list turning the currently [loaded] slot ids into [desired], expressed in
 * `Player` playlist operations and meant to be applied in order. Pure, and the second half of
 * the windowing logic that is unit-testable without a controller.
 *
 * Phase A drops everything that fell out of the window, as maximal contiguous runs emitted
 * back-to-front so earlier indices stay valid as each range lands. Phase B then walks [desired]
 * once, moving an item already present but out of position and batch-inserting maximal runs of
 * items not present at all. The steady sliding-window case therefore costs exactly one
 * `Remove` plus one `Insert`; a drag-reorder inside the window costs a few `Move`s; nothing
 * ever removes and re-adds the currently-playing item, so playback is never torn down.
 */
internal fun computeWindowEdits(
    loaded: List<String>,
    desired: List<QueueSlotRecord>,
): List<WindowEdit> {
    val edits = mutableListOf<WindowEdit>()
    val desiredIds = desired.mapTo(HashSet()) { it.slotId }

    var runEnd = -1
    for (index in loaded.indices.reversed()) {
        val drop = loaded[index] !in desiredIds
        if (drop && runEnd < 0) runEnd = index + 1
        if (!drop && runEnd >= 0) {
            edits += WindowEdit.Remove(index + 1, runEnd)
            runEnd = -1
        }
    }
    if (runEnd >= 0) edits += WindowEdit.Remove(0, runEnd)

    val working = loaded.filterTo(ArrayList()) { it in desiredIds }
    val present = HashSet(working)

    var target = 0
    while (target < desired.size) {
        val wantedId = desired[target].slotId
        if (target < working.size && working[target] == wantedId) {
            target++
            continue
        }
        if (wantedId in present) {
            val existing = working.indexOf(wantedId)
            edits += WindowEdit.Move(existing, target)
            working.add(target, working.removeAt(existing))
            target++
            continue
        }
        var end = target
        while (end < desired.size && desired[end].slotId !in present) end++
        val run = desired.subList(target, end).toList()
        edits += WindowEdit.Insert(target, run)
        run.forEachIndexed { offset, record ->
            working.add(target + offset, record.slotId)
            present += record.slotId
        }
        target = end
    }
    return edits
}

/** The live timeline's slot ids, in timeline order. */
internal fun loadedSlotIds(ctrl: MediaController): List<String> =
    (0 until ctrl.mediaItemCount).map { ctrl.getMediaItemAt(it).mediaId }

/**
 * What a seek-in-place needs to do first to actually recover playback, decompiled from media3
 * 1.4.0's `MediaSessionImpl.handleMediaControllerPlayRequest`: a fatal [Player.onPlayerError]
 * leaves the player in `STATE_IDLE` (prepared media source torn down, `prepare()` required
 * before anything plays again), while a plain end-of-timeline leaves it in `STATE_ENDED`
 * (still prepared, just parked at the end). That session-side recovery is only reached via an
 * explicit `MediaController.play()` call — never via a local `ctrl.seekTo()`, which is why a
 * transport skip issued straight after a fatal error used to seek silently on a dead player
 * (see [seekToLiveIndex]'s kdoc). Pure so the state-to-action mapping is unit-testable without
 * a real `MediaController`.
 */
internal enum class IdleRecoveryAction { NONE, PREPARE, SEEK_TO_DEFAULT }

internal fun idleRecoveryActionFor(playbackState: Int): IdleRecoveryAction = when (playbackState) {
    Player.STATE_IDLE -> IdleRecoveryAction.PREPARE
    Player.STATE_ENDED -> IdleRecoveryAction.SEEK_TO_DEFAULT
    else -> IdleRecoveryAction.NONE
}

/** Applies [idleRecoveryActionFor] to [ctrl] — call before any raw `ctrl.seekTo()`. */
internal fun applyIdleRecovery(ctrl: MediaController) {
    when (idleRecoveryActionFor(ctrl.playbackState)) {
        IdleRecoveryAction.PREPARE -> ctrl.prepare()
        IdleRecoveryAction.SEEK_TO_DEFAULT -> ctrl.seekToDefaultPosition()
        IdleRecoveryAction.NONE -> {}
    }
}

/**
 * Reconciles the live timeline with the window the order store says should be loaded around the
 * currently-playing slot. The single entry point for every "the player's view of the queue may
 * be stale" moment: a track transition (unforced, so hysteresis applies), any queue mutation, a
 * shuffle toggle, a repeat-mode change (which can flip the wraparound rule).
 *
 * Deliberately not wired to `onTimelineChanged` — that callback fires as a *result* of the
 * edits applied here, so calling it from there would recurse.
 *
 * Bails without touching the controller whenever the engine and the store disagree about what
 * is playing, rather than reconciling blind against an anchor it cannot find.
 */
internal fun ExoPlayerEngine.maintainWindow(force: Boolean = false) {
    val ctrl = controller ?: return
    // Nothing loaded at all means playback has not been seeded yet; playQueue/restore build the
    // first window themselves, and reconciling against an empty timeline would insert items
    // with no current item to anchor them.
    if (ctrl.mediaItemCount == 0) return
    val currentSlotId = _currentSlotId.value ?: return
    val currentIndex = orderStore.indexOfSlot(currentSlotId)
    if (currentIndex < 0) return

    val desired = resolvableWindow(currentIndex)
    if (desired.none { it.slotId == currentSlotId }) return

    val loaded = loadedSlotIds(ctrl)
    if (!force && !windowNeedsRefill(loaded, desired, currentSlotId)) return

    val edits = computeWindowEdits(loaded, desired)
    if (edits.isEmpty()) return
    applyWindowEdits(ctrl, edits)
    // Artwork is deliberately left to the caller: every call site here is immediately followed
    // by one (onQueueStructureChanged/onQueueReordered, or the listener callbacks' own), and
    // prefetchArtwork cancels any in-flight job, so prefetching here too would cancel and
    // restart the same read on every single window refill.
}

/**
 * The desired window, filtered to slots whose `Song` is actually in hand. The filter is
 * belt-and-braces (the engine hydrates every slot in the order store) but it is what lets
 * [applyWindowEdits] assume every record builds a `MediaItem` — a partial insert would leave
 * the timeline out of step with the indices the remaining edits were computed against.
 */
private fun ExoPlayerEngine.resolvableWindow(currentIndex: Int): List<QueueSlotRecord> =
    computeWindow(
        live = orderStore.live,
        currentIndex = currentIndex,
        wrapAround = _repeatMode.value == RepeatMode.All,
    ).filter { songsBySlotId.containsKey(it.slotId) }

private fun ExoPlayerEngine.applyWindowEdits(ctrl: MediaController, edits: List<WindowEdit>) {
    for (edit in edits) {
        when (edit) {
            is WindowEdit.Remove -> ctrl.removeMediaItems(edit.fromIndex, edit.toIndexExclusive)
            is WindowEdit.Insert -> ctrl.addMediaItems(
                edit.index,
                edit.records.map { record ->
                    buildMediaItem(record.slotId, songsBySlotId.getValue(record.slotId), null)
                },
            )
            is WindowEdit.Move -> ctrl.moveMediaItem(edit.fromIndex, edit.toIndex)
        }
    }
}

/**
 * Jumps playback to [targetIndex] in the full order. Cheap when the target is already loaded
 * (a plain seek within the window); otherwise the window is rebuilt around it. The rebuild does
 * tear the current item down, but that is a deliberate jump to somewhere else in the queue, so
 * the interruption is expected — and it is bounded to [WINDOW_SIZE] items rather than the whole
 * queue, which is what made the old whole-timeline rebuild expensive.
 */
internal fun ExoPlayerEngine.seekToLiveIndex(targetIndex: Int, play: Boolean): Boolean {
    val ctrl = controller ?: return false
    val target = orderStore.slotAt(targetIndex) ?: return false
    val loadedIndex = indexOfMediaId(ctrl, target.slotId)
    if (loadedIndex != null) {
        // Recover from a dead player (idle after a fatal error, or ended) before seeking —
        // otherwise this is a seek on a player that will never actually play. See
        // idleRecoveryActionFor's kdoc.
        applyIdleRecovery(ctrl)
        ctrl.seekTo(loadedIndex, C.TIME_UNSET)
        if (play) ctrl.play()
        return true
    }
    return rebuildWindowAround(targetIndex, C.TIME_UNSET, play)
}

/**
 * Replaces the whole loaded window with a fresh one centred on [targetIndex], starting playback
 * there at [positionMs]. Used for the three cases that cannot be reconciled incrementally:
 * seeding the very first window ([playQueueInternal]/[restoreSavedQueueInternal] have their own
 * variants of this), jumping to an Up Next row outside the window, and the safety net in
 * [ExoPlayerEngine.advancePastWindowEnd].
 */
internal fun ExoPlayerEngine.rebuildWindowAround(
    targetIndex: Int,
    positionMs: Long,
    play: Boolean,
): Boolean {
    val ctrl = controller ?: return false
    val target = orderStore.slotAt(targetIndex) ?: return false
    val window = resolvableWindow(targetIndex)
    val startIndex = window.indexOfFirst { it.slotId == target.slotId }
    if (startIndex < 0) return false

    artworkLoadedSlotIds.clear()
    ctrl.setMediaItems(
        window.map { record ->
            buildMediaItem(record.slotId, songsBySlotId.getValue(record.slotId), null)
        },
        startIndex,
        positionMs,
    )
    ctrl.prepare()
    if (play) ctrl.play()
    prefetchArtwork()
    return true
}
