package com.v1per.synthx.ui.components

import androidx.compose.foundation.lazy.LazyColumn
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
 * The Up Next list. Reordering is visual-only until the drag ends — [localQueue] mirrors
 * [queue] and animates through intermediate positions, but [onMove] (the engine mutation)
 * only fires once, on drop, with the net from/to indices. [queue] is still the single
 * source of truth: once the engine's move lands, the next [queue] emission resets
 * [localQueue] to match it.
 */
@Composable
fun QueueList(
    queue: List<Song>,
    currentSongId: String?,
    onSongClick: (index: Int) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
    onRemove: (index: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var localQueue by remember(queue) { mutableStateOf(queue) }
    var dragStartIndex by remember { mutableStateOf(-1) }

    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        localQueue = localQueue.toMutableList().apply {
            add(to.index, removeAt(from.index))
        }
    }

    LazyColumn(state = lazyListState, modifier = modifier) {
        itemsIndexed(localQueue, key = { _, song -> song.id }) { index, song ->
            ReorderableItem(reorderableState, key = song.id) {
                QueueRow(
                    song = song,
                    isCurrentlyPlaying = song.id == currentSongId,
                    onClick = { onSongClick(index) },
                    onRemove = { onRemove(index) },
                    dragHandleModifier = Modifier.draggableHandle(
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
