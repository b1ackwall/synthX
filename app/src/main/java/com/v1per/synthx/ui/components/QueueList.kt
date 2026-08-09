package com.v1per.synthx.ui.components

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
 * Drag-reorder is disabled whenever [shuffleEnabled] is true — moving an item while
 * shuffled is unsafe (see [com.v1per.synthx.playback.ExoPlayerEngine.moveQueueItem]'s doc
 * comment for why); swipe-to-remove has no such restriction and stays enabled either way.
 *
 * [state] is hoisted (rather than owned internally) so a host embedding this list — e.g.
 * PlayerExpandedContent's Queue mode — can observe its scroll position for its own purposes
 * (there: collapsing the seek bar/transport controls once the list scrolls).
 */
@Composable
fun QueueList(
    queue: List<Song>,
    shuffleEnabled: Boolean,
    onSongClick: (index: Int) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
    onRemove: (index: Int) -> Unit,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
) {
    var localQueue by remember(queue) { mutableStateOf(queue) }
    var dragStartIndex by remember { mutableStateOf(-1) }

    val reorderableState = rememberReorderableLazyListState(state) { from, to ->
        localQueue = localQueue.toMutableList().apply {
            add(to.index, removeAt(from.index))
        }
    }

    LazyColumn(state = state, modifier = modifier) {
        itemsIndexed(localQueue, key = { _, song -> song.id }) { index, song ->
            ReorderableItem(reorderableState, key = song.id) {
                QueueRow(
                    song = song,
                    dragEnabled = !shuffleEnabled,
                    onClick = { onSongClick(index) },
                    onRemove = { onRemove(index) },
                    dragHandleModifier = Modifier.draggableHandle(
                        enabled = !shuffleEnabled,
                        onDragStarted = { dragStartIndex = index },
                        onDragStopped = {
                            val endIndex = localQueue.indexOfFirst { it.id == song.id }
                            if (dragStartIndex != -1 && dragStartIndex != endIndex) {
                                onMove(dragStartIndex, endIndex)
                            }
                            dragStartIndex = -1
                        },
                    ),
                )
            }
        }
    }
}
