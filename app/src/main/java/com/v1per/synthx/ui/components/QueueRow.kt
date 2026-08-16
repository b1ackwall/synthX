package com.v1per.synthx.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.v1per.synthx.R
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.ui.theme.Hairline
import com.v1per.synthx.ui.theme.Spacing
import com.v1per.synthx.ui.theme.Surface
import com.v1per.synthx.ui.theme.TextTertiary

/**
 * One Up Next row: tap to jump to and play, drag the trailing handle to reorder. The handle
 * is the only part [dragHandleModifier] is applied to — the rest of the row must never start
 * a reorder-drag, only [onClick]. No swipe gesture — removal is a separate, later feature.
 *
 * The currently-playing song is never rendered as a row here (it lives in
 * PlayerExpandedContent's Queue-mode header instead), so there is no "is this the playing
 * row" state to track any more.
 *
 * The drag handle is always enabled — queue ordering is fully app-managed, so reordering
 * is safe regardless of shuffle state, see
 * [com.v1per.synthx.playback.ExoPlayerEngine.moveQueueItem]'s doc comment.
 *
 * [onMoveUp]/[onMoveDown] give TalkBack/switch-access users a non-gesture equivalent of the
 * drag reorder — the handle's `onClick` is a no-op (it exists only to host
 * [dragHandleModifier]'s gesture detection), so without these the handle would announce as an
 * interactive button that does nothing when activated. Null when the move isn't possible
 * (already first/last), matching how the drag itself has nowhere to go at either end.
 */
@Composable
fun QueueRow(
    song: Song,
    onClick: () -> Unit,
    dragHandleModifier: Modifier,
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null,
) {
    Box(modifier = Modifier.background(Surface)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .clickable(onClick = onClick)
                .padding(vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SongIdentity(
                song = song,
                isCurrentlyPlaying = false,
                modifier = Modifier.weight(1f),
            )

            val moveUpLabel = stringResource(R.string.action_move_up)
            val moveDownLabel = stringResource(R.string.action_move_down)
            IconButton(
                onClick = {},
                modifier = dragHandleModifier
                    .padding(end = Spacing.sm)
                    .semantics {
                        customActions = listOfNotNull(
                            onMoveUp?.let {
                                CustomAccessibilityAction(moveUpLabel) { it(); true }
                            },
                            onMoveDown?.let {
                                CustomAccessibilityAction(moveDownLabel) { it(); true }
                            },
                        )
                    },
            ) {
                Icon(
                    imageVector = Icons.Filled.DragHandle,
                    contentDescription = stringResource(R.string.cd_drag_handle),
                    tint = TextTertiary,
                )
            }
        }
    }

    HorizontalDivider(thickness = 1.dp, color = Hairline)
}
