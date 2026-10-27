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
 * [onSongClick]/[onMove]/[onRemove] each take the tapped/dragged/swiped row's stable slot id rather than a
 * position — [localQueue] is Compose-local state that free-runs between
 * `remember(queue)` resets (live-reordered mid-drag, or reset wholesale by an upstream mutation
 * landing mid-gesture), so any index captured off it here can already be stale by the time the
 * command reaches the engine. Passing identity instead of a display position closes that gap at
 * the layer that actually matters: the engine's queue ops (ExoPlayerEngineQueueOps.kt) resolve
 * the slot id against the order store's live position fresh at execution time and reject the
 * command outright if it no longer exists there, so a stale/divergent [localQueue] here cannot
 * produce a command that lands on the wrong song — only, at worst, a transiently stale
 * *display* until the next [queue] emission resets it, which is a cosmetic concern, not a
 * correctness one.
 */
@Composable
fun QueueList(
    queue: ImmutableList<QueueSlot>,
    onSongClick: (slotId: String) -> Unit,
    onMove: (slotId: String, toIndex: Int) -> Unit,
    onRemove: (slotId: String) -> Unit,
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
    // The engine order, read the same way; replaced in the same composition as the holder.
    val readQueue by rememberUpdatedState { queue }
    val currentOnMove by rememberUpdatedState(onMove)
    // The dragged row's identity — captured once at drag-start and never re-derived from a
    // position, since [moveQueueItemInternal] resolves it fresh against the engine's own
    // live order at execution time regardless of what this UI layer believes. See the class
    // kdoc above for why nothing index-shaped needs to survive the trip to the engine.
    var dragStartSlotId by remember { mutableStateOf<String?>(null) }

    val reorderableState = rememberReorderableLazyListState(state) { from, to ->
        // Resolved by key (each item's key is its slotId): the library's indices come from the
        // layout pass that saw the drag, which can predate a holder swapped in since.
        val fromIndex = localQueue.indexOfFirst { it.slotId == from.key }
        val toIndex = localQueue.indexOfFirst { it.slotId == to.key }
        if (fromIndex == -1 || toIndex == -1) return@rememberReorderableLazyListState
        localQueue = localQueue.toMutableList().apply {
            add(toIndex, removeAt(fromIndex))
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
                    // Identity, not a position: the engine resolves the slot id against its live
                    // order and rejects one it does not contain.
                    onRemove = { if (currentEnabled) onRemove(slot.slotId) },
                    enabled = { currentEnabled },
                    dragHandleModifier = Modifier.draggableHandle(
                        onDragStarted = { dragStartSlotId = slot.slotId },
                        onDragStopped = {
                            // The moved row's identity — never re-derived from a position; the
                            // engine re-resolves it against its live order and rejects it if
                            // it no longer exists.
                            val startSlotId = dragStartSlotId
                            // The holder is [queue] with only the dragged row relocated, so
                            // the drop is a no-op exactly when that row sits at the same index
                            // in both. Both are read now, so a queue change mid-drag (which
                            // swaps the holder) cannot put the two indices in different lists.
                            val endIndex = startSlotId
                                ?.let { id -> readLocalQueue().indexOfFirst { it.slotId == id } }
                                ?: -1
                            val engineIndex = startSlotId
                                ?.let { id -> readQueue().indexOfFirst { it.slotId == id } }
                                ?: -1
                            // If the dragged song can no longer be found in the live list at
                            // all (e.g. removed during the drag), there is nothing valid to
                            // move — drop the reorder rather than call onMove with a -1 index.
                            if (currentEnabled && startSlotId != null && endIndex != -1 && endIndex != engineIndex) {
                                currentOnMove(startSlotId, endIndex)
                            } else if (!currentEnabled) {
                                // The engine did not take this order, so the display drops it.
                                resetLocalQueue()
                            }
                            dragStartSlotId = null
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
