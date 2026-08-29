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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.v1per.synthx.data.repository.Song
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toPersistentList
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * The Up Next list — [queue] is the upcoming-only, shuffle-respecting order (the currently
 * playing song is never one of its rows). Reordering is visual-only until the drag ends —
 * [localQueue] mirrors [queue] and animates through intermediate positions, but [onMove]
 * (the engine mutation) only fires once, on drop, with the net from/to indices. [queue] is
 * still the single source of truth: once the engine's move lands, the next [queue] emission
 * resets [localQueue] to match it.
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
 */
@Composable
fun QueueList(
    queue: ImmutableList<Song>,
    onSongClick: (index: Int) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
    onRemove: (index: Int) -> Unit,
    surfaceColor: Color,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    state: LazyListState = rememberLazyListState(),
    enabled: Boolean = true,
) {
    var localQueue by remember(queue) { mutableStateOf(queue) }
    // Identity, not a raw index: an arbitrarily long drag can straddle a queue mutation
    // (e.g. auto-advance resetting localQueue via the remember(queue) key above), which
    // would leave a captured index stale. Resolving the end position by song id against
    // the live localQueue immediately before calling onMove mirrors onRemove's
    // identity-based resolution below and the engine-level principle documented on
    // PlaybackEngine.removeQueueItem.
    var dragStartSongId by remember { mutableStateOf<String?>(null) }
    // The "before" list, snapshotted the instant the drag begins — a cheap reference copy
    // (localQueue is a PersistentList). Needed because localQueue is already live-reordered
    // by reorderableState's onMove callback below as the drag happens, so by drop time it
    // is the song's *final* position for every lookup, not its starting one. Resolving
    // start against this snapshot and end against the live localQueue is what makes the
    // two indices genuinely different for a real reorder, instead of both resolving the
    // same dragged song's identity against the same (already-reordered) list.
    var dragStartQueueSnapshot by remember { mutableStateOf<ImmutableList<Song>?>(null) }

    val reorderableState = rememberReorderableLazyListState(state) { from, to ->
        localQueue = localQueue.toMutableList().apply {
            add(to.index, removeAt(from.index))
        }.toPersistentList()
    }

    LazyColumn(state = state, contentPadding = contentPadding, modifier = modifier) {
        itemsIndexed(localQueue, key = { _, song -> song.id }) { index, song ->
            ReorderableItem(reorderableState, key = song.id) {
                QueueRow(
                    song = song,
                    queue = queue,
                    onClick = { if (enabled) onSongClick(index) },
                    // Resolved against [queue] by identity rather than reported as `index`:
                    // `index` is a position in [localQueue], whose optimistic order can differ
                    // from [queue]'s between a drop and the engine's echo, and the host
                    // resolves this index back to a song against [queue].
                    onRemove = {
                        val queueIndex = queue.indexOfFirst { it.id == song.id }
                        if (enabled && queueIndex != -1) onRemove(queueIndex)
                    },
                    surfaceColor = surfaceColor,
                    enabled = enabled,
                    dragHandleModifier = Modifier.draggableHandle(
                        onDragStarted = {
                            dragStartSongId = song.id
                            dragStartQueueSnapshot = localQueue
                        },
                        onDragStopped = {
                            // Start resolved against the snapshot taken before any live
                            // drag-reordering happened — the song's genuine original
                            // position. End resolved against the current, already
                            // live-reordered localQueue — its final resting position. Two
                            // different lists, so a real reorder yields two different
                            // indices (see the field doc comments above for why resolving
                            // both against the same live list, as the previous fix did,
                            // made them always equal).
                            val startSongId = dragStartSongId
                            val beforeQueue = dragStartQueueSnapshot
                            val startIndex = if (startSongId != null && beforeQueue != null) {
                                beforeQueue.indexOfFirst { it.id == startSongId }
                            } else {
                                -1
                            }
                            val endIndex = startSongId
                                ?.let { id -> localQueue.indexOfFirst { it.id == id } }
                                ?: -1
                            // If the dragged song can no longer be found in either list
                            // (e.g. removed during the drag), there is nothing valid to
                            // move — drop the reorder rather than call onMove with a -1
                            // index.
                            //
                            // Same song set, different order, required: if the queue was
                            // externally mutated mid-drag (e.g. auto-advance resetting
                            // localQueue to a wholly different list via the remember(queue)
                            // key above), beforeQueue and localQueue are two unrelated
                            // lists — indices resolved against one don't mean anything
                            // relative to the other, even if both individually resolve to
                            // a non-(-1) value. Comparing id sets (order-independent) is
                            // the cheapest way to detect that without diffing positions.
                            val sameQueue = beforeQueue != null &&
                                beforeQueue.mapTo(mutableSetOf()) { it.id } ==
                                localQueue.mapTo(mutableSetOf()) { it.id }
                            if (enabled && sameQueue && startIndex != -1 && endIndex != -1 && startIndex != endIndex) {
                                onMove(startIndex, endIndex)
                            }
                            dragStartSongId = null
                            dragStartQueueSnapshot = null
                        },
                    ),
                    // Same onMove the drag gesture calls on drop — accessibility-action and
                    // drag paths land on the identical engine mutation, just with a fixed
                    // one-position delta instead of a live drag endpoint.
                    onMoveUp = if (enabled && index > 0) {
                        { onMove(index, index - 1) }
                    } else {
                        null
                    },
                    onMoveDown = if (enabled && index < localQueue.lastIndex) {
                        { onMove(index, index + 1) }
                    } else {
                        null
                    },
                )
            }
        }
    }
}
