package com.v1per.synthx.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.v1per.synthx.R
import com.v1per.synthx.data.repository.Playlist
import com.v1per.synthx.data.repository.PlaylistGroup
import com.v1per.synthx.ui.theme.Accent
import com.v1per.synthx.ui.theme.Hairline
import com.v1per.synthx.ui.theme.Spacing
import com.v1per.synthx.ui.theme.TextPrimary
import com.v1per.synthx.ui.theme.TextSecondary
import com.v1per.synthx.ui.theme.TextTertiary

@Composable
internal fun PlaylistRow(
    playlist: Playlist,
    onClick: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onMoveToGroup: (String?) -> Unit = {},
    availableGroups: List<PlaylistGroup> = emptyList(),
) {
    val interactionSource = remember { MutableInteractionSource() }
    var isRenaming by remember { mutableStateOf(false) }
    var renameValue by remember { mutableStateOf(playlist.name) }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clickable(
                    interactionSource = interactionSource,
                    indication = ripple(color = Accent),
                    onClick = onClick,
                )
                .padding(horizontal = Spacing.HorizontalPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (playlist.albumArtUri != null) {
                AsyncImage(
                    model = crossfadeModel(playlist.albumArtUri),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(MaterialTheme.shapes.small),
                )
            } else {
                GlitchPlaceholder(
                    songId = playlist.id,
                    isPlaying = false,
                    size = 40.dp,
                )
            }
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
                availableGroups = availableGroups,
                currentGroupId = playlist.groupId,
                onRename = {
                    renameValue = playlist.name
                    isRenaming = true
                },
                onMoveToGroup = onMoveToGroup,
                onDelete = onDelete,
            )
        }
        HorizontalDivider(
            thickness = 1.dp,
            color = Hairline,
        )
    }
}

@Composable
private fun PlaylistThreeDotsMenu(
    playlistName: String,
    availableGroups: List<PlaylistGroup>,
    currentGroupId: String?,
    onRename: () -> Unit,
    onMoveToGroup: (String?) -> Unit,
    onDelete: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    var showGroupMenu by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Box {
        IconButton(onClick = { showMenu = true }) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = stringResource(R.string.cd_more_options),
                tint = TextTertiary,
            )
        }
        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_rename)) },
                onClick = {
                    showMenu = false
                    onRename()
                },
            )
            if (availableGroups.isNotEmpty()) {
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
                        showMenu = false
                        showGroupMenu = true
                    },
                )
            }
            if (currentGroupId != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_remove_from_group)) },
                    onClick = {
                        showMenu = false
                        onMoveToGroup(null)
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_delete)) },
                onClick = {
                    showMenu = false
                    showDeleteConfirm = true
                },
            )
        }
        DropdownMenu(
            expanded = showGroupMenu,
            onDismissRequest = { showGroupMenu = false },
        ) {
            availableGroups.forEach { group ->
                DropdownMenuItem(
                    text = { Text(group.name) },
                    onClick = {
                        showGroupMenu = false
                        onMoveToGroup(group.id)
                    },
                )
            }
        }
    }

    if (showDeleteConfirm) {
        DeletePlaylistDialog(
            playlistName = playlistName,
            onConfirm = {
                showDeleteConfirm = false
                onDelete()
            },
            onDismiss = { showDeleteConfirm = false },
        )
    }
}
