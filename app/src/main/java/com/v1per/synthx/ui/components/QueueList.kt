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
 * PlayerExpandedContent's Queue mode — can observe its scroll position for its own purposes
 * (there: collapsing the seek bar/transport controls once the list scrolls).
 *
 * [contentPadding] exists for hosts that let this list extend underneath an overlay: the
 * rows draw through the full viewport, but the padding extends the scroll range so the last
 * row can still be brought clear of whatever is drawn on top. Callers should keep it
 * constant — varying it mid-scroll moves the max-scroll offset under the user's finger.
 */
@Composable
fun QueueList(
    queue: List<Song>,
    onSongClick: (index: Int) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
    surfaceColor: Color,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    state: LazyListState = rememberLazyListState(),
    enabled: Boolean = true,
) {
    var localQueue by remember(queue) { mutableStateOf(queue) }
    var dragStartIndex by remember { mutableStateOf(-1) }

    val reorderableState = rememberReorderableLazyListState(state) { from, to ->
        localQueue = localQueue.toMutableList().apply {
            add(to.index, removeAt(from.index))
        }
    }

    LazyColumn(state = state, contentPadding = contentPadding, modifier = modifier) {
        itemsIndexed(localQueue, key = { _, song -> song.id }) { index, song ->
            ReorderableItem(reorderableState, key = song.id) {
                QueueRow(
                    song = song,
                    onClick = { if (enabled) onSongClick(index) },
                    surfaceColor = surfaceColor,
                    dragHandleModifier = Modifier.draggableHandle(
                        onDragStarted = { dragStartIndex = index },
                        onDragStopped = {
                            val endIndex = localQueue.indexOfFirst { it.id == song.id }
                            if (enabled && dragStartIndex != -1 && dragStartIndex != endIndex) {
                                onMove(dragStartIndex, endIndex)
                            }
                            dragStartIndex = -1
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
