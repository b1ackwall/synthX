package com.v1per.synthx.ui.screens.playlistlist

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.v1per.synthx.data.repository.PlaylistGroup
import com.v1per.synthx.ui.components.PlaylistRow
import com.v1per.synthx.ui.theme.Accent
import com.v1per.synthx.ui.theme.Hairline
import com.v1per.synthx.ui.theme.Spacing
import com.v1per.synthx.ui.theme.Surface
import com.v1per.synthx.ui.theme.SurfaceVariant
import com.v1per.synthx.ui.theme.TextPrimary
import com.v1per.synthx.ui.theme.TextSecondary
import com.v1per.synthx.ui.theme.TextTertiary

@Composable
fun PlaylistListScreen(
    topBar: @Composable () -> Unit,
    onGroupClick: (groupId: String, groupName: String) -> Unit,
    onPlaylistClick: (playlistId: String, playlistName: String) -> Unit,
    onCreatePlaylistClick: () -> Unit,
    viewModel: PlaylistListViewModel = viewModel(
        factory = PlaylistListViewModel.factory(LocalContext.current),
    ),
) {
    val uiState by viewModel.uiState.collectAsState()
    val showCreateGroup by viewModel.showCreateGroup.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        topBar()

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.HorizontalPadding, vertical = Spacing.md),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            OutlinedButton(
                onClick = onCreatePlaylistClick,
                border = BorderStroke(1.dp, TextSecondary),
                shape = MaterialTheme.shapes.small,
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    tint = TextSecondary,
                )
                Spacer(modifier = Modifier.width(Spacing.xs))
                Text(
                    text = "New Playlist",
                    color = TextSecondary,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            OutlinedButton(
                onClick = viewModel::onCreateGroupClick,
                border = BorderStroke(1.dp, TextSecondary),
                shape = MaterialTheme.shapes.small,
            ) {
                Icon(
                    imageVector = Icons.Default.CreateNewFolder,
                    contentDescription = null,
                    tint = TextSecondary,
                )
                Spacer(modifier = Modifier.width(Spacing.xs))
                Text(
                    text = "New Group",
                    color = TextSecondary,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }

        AnimatedVisibility(
            visible = showCreateGroup,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            CreateGroupCard(
                onConfirm = { name -> viewModel.createGroup(name) },
                onDismiss = viewModel::onDismissCreateGroup,
            )
        }

        LazyColumn {
            if (uiState.groups.isNotEmpty()) {
                items(uiState.groups, key = { it.id }) { group ->
                    GroupRow(
                        group = group,
                        onClick = { onGroupClick(group.id, group.name) },
                        onRename = { name -> viewModel.renameGroup(group.id, name) },
                        onDelete = { viewModel.deleteGroup(group.id) },
                    )
                }
            }

            if (uiState.ungroupedPlaylists.isNotEmpty()) {
                items(uiState.ungroupedPlaylists, key = { it.id }) { playlist ->
                    PlaylistRow(
                        playlist = playlist,
                        onClick = { onPlaylistClick(playlist.id, playlist.name) },
                        onRename = { name -> viewModel.renamePlaylist(playlist.id, name) },
                        onDelete = { viewModel.deletePlaylist(playlist.id) },
                        onMoveToGroup = { groupId ->
                            viewModel.movePlaylistToGroup(playlist.id, groupId)
                        },
                        availableGroups = uiState.groups,
                    )
                }
            }

            if (uiState.groups.isEmpty() && uiState.ungroupedPlaylists.isEmpty() && !uiState.isLoading) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(Spacing.xxl),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "No playlists yet",
                            style = MaterialTheme.typography.bodyLarge,
                            color = TextSecondary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CreateGroupCard(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.HorizontalPadding, vertical = Spacing.md),
        shape = MaterialTheme.shapes.small,
        colors = CardDefaults.cardColors(containerColor = Surface),
    ) {
        Column(modifier = Modifier.padding(Spacing.md)) {
            Text(
                text = "New Group",
                style = MaterialTheme.typography.labelLarge,
                color = TextSecondary,
            )
            Spacer(modifier = Modifier.height(Spacing.sm))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text(text = "Group name", color = TextTertiary) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onConfirm(name) }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = TextSecondary,
                    cursorColor = TextPrimary,
                ),
            )
            Spacer(modifier = Modifier.height(Spacing.sm))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(text = "Cancel", color = TextSecondary)
                }
                Spacer(modifier = Modifier.width(Spacing.sm))
                Button(
                    onClick = { onConfirm(name) },
                    shape = MaterialTheme.shapes.small,
                    colors = ButtonDefaults.buttonColors(containerColor = SurfaceVariant),
                ) {
                    Text(text = "Create", color = TextPrimary)
                }
            }
        }
    }
}

@Composable
private fun GroupRow(
    group: PlaylistGroup,
    onClick: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    var isRenaming by remember { mutableStateOf(false) }
    var renameValue by remember { mutableStateOf(group.name) }

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
            Icon(
                imageVector = Icons.Default.Folder,
                contentDescription = null,
                tint = TextSecondary,
                modifier = Modifier.size(24.dp),
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
                    text = group.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            GroupThreeDotsMenu(
                onRename = {
                    renameValue = group.name
                    isRenaming = true
                },
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
private fun GroupThreeDotsMenu(
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }

    Box {
        IconButton(onClick = { showMenu = true }) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = "More options",
                tint = TextTertiary,
            )
        }
        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
        ) {
            DropdownMenuItem(
                text = { Text("Rename") },
                onClick = {
                    showMenu = false
                    onRename()
                },
            )
            DropdownMenuItem(
                text = { Text("Delete") },
                onClick = {
                    showMenu = false
                    onDelete()
                },
            )
        }
    }
}
