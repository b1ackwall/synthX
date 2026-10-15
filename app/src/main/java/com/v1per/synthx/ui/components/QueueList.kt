package com.v1per.synthx.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.v1per.synthx.data.repository.QueueSlot
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toPersistentList
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * The Up Next list — [queue] is the upcoming-only, shuffle-respecting order (the currently
 * playing song is never one of its rows). Reordering is visual-only until the drag ends —
 * [localQueue] mirrors [queue] and animates through intermediate positions, but [onMove]
 * (the engine mutation) only fires once, on drop, naming the moved slot by id plus its net
 * target index. [queue] is still the single source of truth: once the engine's move lands, the
 * next [queue] emission resets [localQueue] to match it.
 *
 * Drag-reorder is always available, regardless of shuffle state — queue ordering is fully
 * app-managed (see [com.v1per.synthx.playback.ExoPlayerEngine.moveQueueItem]'s doc comment),
 * so a reorder while shuffled is a safe, if temporary, active-timeline-only edit.
 *
 * [state] is hoisted (rather than owned internally) so a host embedding this list — e.g.
 * PlayerSkeletonQueueContent's Queue mode — can observe its scroll position for its own
 * purposes (there: collapsing the seek bar/transport controls once the list scrolls).
 *
 * [contentPadding] exists for hosts that let this list extend underneath an overlay: the
 * rows draw through the full viewport, but the padding extends the scroll range so the last
 * row can still be brought clear of whatever is drawn on top. Callers should keep it
 * constant — varying it mid-scroll moves the max-scroll offset under the user's finger.
 *
 * [onRemove]'s index is into [queue], not [localQueue] — see its wiring below.
 *
 * [onSongClick]/[onMove] both take the tapped/dragged row's stable slot id rather than a
 * position (P4-T8 / R3-F04) — [localQueue] is Compose-local state that free-runs between
 * `remember(queue)` resets (live-reordered mid-drag, or reset wholesale by an upstream mutation
 * landing mid-gesture), so any index captured off it here can already be stale by the time the
 * command reaches the engine. Passing identity instead of a display position closes that gap at
 * the layer that actually matters: [com.v1per.synthx.playback.ExoPlayerEngineQueueOps] resolves
 * the slot id against the order store's live position fresh at execution time and rejects the
 * command outright if it no longer exists there, so a stale/divergent [localQueue] here can no
 * longer produce a command that lands on the wrong song — only, at worst, a transiently stale
 * *display* until the next [queue] emission resets it, which is a cosmetic concern, not a
 * correctness one.
 */
@Composable
fun QueueList(
    queue: ImmutableList<QueueSlot>,
    onSongClick: (slotId: String) -> Unit,
    onMove: (slotId: String, toIndex: Int) -> Unit,
    onRemove: (index: Int) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    state: LazyListState = rememberLazyListState(),
    enabled: Boolean = true,
) {
    // Read only inside callbacks, so a flip of [enabled] never recomposes the rows.
    val currentEnabled by rememberUpdatedState(enabled)
    var localQueue by remember(queue) { mutableStateOf(queue) }
    // Read through State: a drag handle keeps the closures of its first touch, which would
    // otherwise hold an older [queue], an older [localQueue] holder and an older [onMove].
    val resetLocalQueue by rememberUpdatedState { localQueue = queue }
    // A getter, not the value: it reads the current holder at call time, so it also sees a
    // library swap that has not been recomposed yet.
    val readLocalQueue by rememberUpdatedState { localQueue }
    val currentOnMove by rememberUpdatedState(onMove)
    // The dragged row's identity — captured once at drag-start and never re-derived from a
    // position, since [moveQueueItemInternal] now resolves it fresh against the engine's own
    // live order at execution time regardless of what this UI layer believes. See the class
    // kdoc above for why nothing index-shaped needs to survive the trip to the engine any more.
    var dragStartSlotId by remember { mutableStateOf<String?>(null) }
    // The "before" list, snapshotted the instant the drag begins — a cheap reference copy
    // (localQueue is a PersistentList). Used only to detect a pure no-op drag (dropped back at
    // its own starting position) so [onMove] isn't called for nothing; it is NOT used to
    // identify which row moved — that is [dragStartSlotId] alone, resolved by identity, never
    // by a position read off this snapshot.
    var dragStartQueueSnapshot by remember { mutableStateOf<ImmutableList<QueueSlot>?>(null) }

    val reorderableState = rememberReorderableLazyListState(state) { from, to ->
        localQueue = localQueue.toMutableList().apply {
            add(to.index, removeAt(from.index))
        }.toPersistentList()
    }

    LazyColumn(state = state, contentPadding = contentPadding, modifier = modifier) {
        itemsIndexed(localQueue, key = { _, slot -> slot.slotId }) { index, slot ->
            ReorderableItem(reorderableState, key = slot.slotId) {
                QueueRow(
                    slotId = slot.slotId,
                    song = slot.song,
                    queue = queue,
                    onClick = { if (currentEnabled) onSongClick(slot.slotId) },
                    // Resolved against [queue] by identity rather than reported as `index`:
                    // `index` is a position in [localQueue], whose optimistic order can differ
                    // from [queue]'s between a drop and the engine's echo, and the host
                    // resolves this index back to a song against [queue].
                    onRemove = {
                        val queueIndex = queue.indexOfFirst { it.slotId == slot.slotId }
                        if (currentEnabled && queueIndex != -1) onRemove(queueIndex)
                    },
                    enabled = { currentEnabled },
                    dragHandleModifier = Modifier.draggableHandle(
                        onDragStarted = {
                            dragStartSlotId = slot.slotId
                            dragStartQueueSnapshot = readLocalQueue()
                        },
                        onDragStopped = {
                            // The moved row's identity — never re-derived from a position.
                            // What used to be a "same slot SET, different order" guard here
                            // (comparing beforeQueue/localQueue as sets) is no longer the
                            // correctness backstop: it was order-blind by construction, so an
                            // external reorder landing mid-drag (same slots, shuffled order)
                            // would have passed it right along with a wrong from/to pair. Now
                            // that the moved item is identified by slot id end to end, and
                            // ExoPlayerEngineQueueOps.moveQueueItemInternal re-resolves that id
                            // against the engine's live order at execution time (rejecting it
                            // outright if it no longer exists), that guard would only ever be
                            // redundant when it agreed with the engine and wrong when it
                            // didn't — so it's dropped rather than kept as false reassurance.
                            //
                            // dragStartQueueSnapshot is kept only to detect a pure no-op drag
                            // (dropped back at its own starting position), purely to avoid an
                            // unnecessary onMove call — not for correctness.
                            val startSlotId = dragStartSlotId
                            val beforeQueue = dragStartQueueSnapshot
                            val startIndex = if (startSlotId != null && beforeQueue != null) {
                                beforeQueue.indexOfFirst { it.slotId == startSlotId }
                            } else {
                                -1
                            }
                            val endIndex = startSlotId
                                ?.let { id -> readLocalQueue().indexOfFirst { it.slotId == id } }
                                ?: -1
                            // If the dragged song can no longer be found in the live list at
                            // all (e.g. removed during the drag), there is nothing valid to
                            // move — drop the reorder rather than call onMove with a -1 index.
                            if (currentEnabled && startSlotId != null && endIndex != -1 && startIndex != endIndex) {
                                currentOnMove(startSlotId, endIndex)
                            } else if (!currentEnabled) {
                                // The engine did not take this order, so the display drops it.
                                resetLocalQueue()
                            }
                            dragStartSlotId = null
                            dragStartQueueSnapshot = null
                        },
                    ),
                    // Same onMove the drag gesture calls on drop — accessibility-action and
                    // drag paths land on the identical engine mutation, just with a fixed
                    // one-position delta instead of a live drag endpoint.
                    onMoveUp = if (index > 0) {
                        { if (currentEnabled) onMove(slot.slotId, index - 1) }
                    } else {
                        null
                    },
                    onMoveDown = if (index < localQueue.lastIndex) {
                        { if (currentEnabled) onMove(slot.slotId, index + 1) }
                    } else {
                        null
                    },
                )
            }
        }
    }
}
