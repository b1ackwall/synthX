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
    // would leave a captured index stale. Resolving both ends by song id against the live
    // localQueue immediately before calling onMove mirrors onRemove's identity-based
    // resolution below and the engine-level principle documented on
    // PlaybackEngine.removeQueueItem.
    var dragStartSongId by remember { mutableStateOf<String?>(null) }

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
                        onDragStarted = { dragStartSongId = song.id },
                        onDragStopped = {
                            // Resolved fresh, by identity, against the live localQueue —
                            // not the index captured at drag start, which may have gone
                            // stale if the queue changed underneath an arbitrarily long
                            // drag (e.g. playback auto-advancing).
                            val startSongId = dragStartSongId
                            val startIndex = startSongId
                                ?.let { id -> localQueue.indexOfFirst { it.id == id } }
                                ?: -1
                            val endIndex = localQueue.indexOfFirst { it.id == song.id }
                            // If the dragged song can no longer be found (e.g. removed
                            // during the drag), there is nothing valid to move — drop the
                            // reorder rather than call onMove with a -1 index.
                            if (enabled && startIndex != -1 && endIndex != -1 && startIndex != endIndex) {
                                onMove(startIndex, endIndex)
                            }
                            dragStartSongId = null
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
