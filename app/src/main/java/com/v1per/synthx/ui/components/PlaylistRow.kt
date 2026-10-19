package com.v1per.synthx.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.v1per.synthx.R
import com.v1per.synthx.data.repository.Playlist
import com.v1per.synthx.data.repository.PlaylistGroup
import com.v1per.synthx.ui.theme.Accent
import com.v1per.synthx.ui.theme.Hairline
import com.v1per.synthx.ui.theme.Spacing
import com.v1per.synthx.ui.theme.TextPrimary
import com.v1per.synthx.ui.theme.TextSecondary

@Composable
internal fun PlaylistRow(
    playlist: Playlist,
    onClick: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    // Opens the group picker sheet. Hosted by MainScaffold, not by this row: a sheet composed
    // inside the NavHost draws *under* BottomChromeOverlay, so the bottom nav and player would
    // paint over its lower rows — the same reason the sort sheet is hoisted (see MainScaffold).
    // Null means the caller doesn't support grouping actions at all (e.g. Search) — distinct
    // from "supports it but nothing to show right now", which is instead expressed via
    // availableGroups being empty/all-current-group.
    onShowGroupPicker: (() -> Unit)? = null,
    // Direct, sheet-less ungrouping. Separate from the picker: "remove" is a one-tap answer, not
    // a choice among groups, and the picker has no "no group" row to express it. Null means the
    // caller doesn't support grouping actions at all — see onShowGroupPicker above.
    onRemoveFromGroup: (() -> Unit)? = null,
    // Read only to decide whether offering a group at all makes sense — the rows themselves are
    // rendered by the picker sheet now, not by this menu.
    availableGroups: List<PlaylistGroup> = emptyList(),
) {
    val interactionSource = remember { MutableInteractionSource() }
    // rememberSaveable: this row is a lazy-list item at all three call sites, so plain remember
    // dropped a half-typed rename whenever the row scrolled off screen, and on recreation.
    // Lazy items save through the list's keyed state holder, so the row keys must stay stable.
    var isRenaming by rememberSaveable { mutableStateOf(false) }
    var renameValue by rememberSaveable { mutableStateOf(playlist.name) }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(Spacing.RowHeight)
                .clickable(
                    interactionSource = interactionSource,
                    indication = ripple(color = Accent),
                    onClick = onClick,
                )
                .padding(horizontal = Spacing.HorizontalPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // playlist.albumArtUri (playlists.cover_art_uri) is never written by any path in
            // this app, so the cover is always generated art — it falls back to
            // ArtPlaceholder itself while artHue/artStructureSeed are still null.
            PlaylistArt(
                hue = playlist.artHue,
                structureSeed = playlist.artStructureSeed,
                size = 40.dp,
            )
            Spacer(modifier = Modifier.width(Spacing.md))
            if (isRenaming) {
                OutlinedTextField(
                    value = renameValue,
                    onValueChange = { renameValue = it },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    textStyle = MaterialTheme.typography.bodyLarge,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            onRename(renameValue)
                            isRenaming = false
                        },
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = TextSecondary,
                        cursorColor = TextPrimary,
                    ),
                )
            } else {
                Text(
                    text = playlist.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            PlaylistThreeDotsMenu(
                playlistName = playlist.name,
                // "Somewhere else to put it", not merely "groups exist": for a playlist that is
                // already grouped, a library whose only group is that one has nothing to offer,
                // and the picker would open showing a single checked, inert row.
                hasGroupsToChooseFrom = availableGroups.any { it.id != playlist.groupId },
                currentGroupId = playlist.groupId,
                onRename = {
                    renameValue = playlist.name
                    isRenaming = true
                },
                onShowGroupPicker = onShowGroupPicker,
                onRemoveFromGroup = onRemoveFromGroup,
                onDelete = onDelete,
            )
        }
        HorizontalDivider(
            thickness = 1.dp,
            color = Hairline,
        )
    }
}

/**
 * Group membership is a single nullable column, so a playlist is in exactly one group or none —
 * which gives this menu two shapes, never a third:
 * - ungrouped: "Add to group" alone. There is nothing to remove it from, and "move" would be a
 *   misnomer for a first assignment.
 * - grouped: "Move to group" and "Remove from group" as two separate items, which keeps the
 *   direct action out of a sheet whose whole content is groups to move *into*.
 *
 * Both group entries are gated on at least one group existing — creating one stays the main
 * Playlists screen's own "New Group" flow and is deliberately not shortcut from here.
 */
@Composable
private fun PlaylistThreeDotsMenu(
    playlistName: String,
    hasGroupsToChooseFrom: Boolean,
    currentGroupId: String?,
    onRename: () -> Unit,
    onShowGroupPicker: (() -> Unit)?,
    onRemoveFromGroup: (() -> Unit)?,
    onDelete: () -> Unit,
) {
    ThreeDotMenuWithConfirm(
        menuItems = { closeMenu, requestConfirm ->
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_rename)) },
                onClick = {
                    closeMenu()
                    onRename()
                },
            )
            // Captured into locals so the smart-cast to non-null survives inside each
            // DropdownMenuItem's onClick lambda — referencing the parameter itself there
            // would not smart-cast across the lambda boundary.
            val showGroupPicker = onShowGroupPicker
            if (showGroupPicker != null && hasGroupsToChooseFrom) {
                DropdownMenuItem(
                    text = {
                        Text(
                            if (currentGroupId == null) {
                                stringResource(R.string.action_add_to_group)
                            } else {
                                stringResource(R.string.action_move_to_group)
                            }
                        )
                    },
                    onClick = {
                        closeMenu()
                        showGroupPicker()
                    },
                )
            }
            val removeFromGroup = onRemoveFromGroup
            if (removeFromGroup != null && currentGroupId != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_remove_from_group)) },
                    onClick = {
                        closeMenu()
                        removeFromGroup()
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_delete)) },
                onClick = requestConfirm,
            )
        },
        confirmDialog = { onConfirm, onDismiss ->
            DeletePlaylistDialog(
                playlistName = playlistName,
                onConfirm = {
                    onConfirm()
                    onDelete()
                },
                onDismiss = onDismiss,
            )
        },
    )
}
