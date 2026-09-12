package com.v1per.synthx.ui.screens.groupdetail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import com.v1per.synthx.R
import com.v1per.synthx.data.repository.PlaylistGroup
import com.v1per.synthx.ui.common.UiState
import com.v1per.synthx.ui.components.LoadErrorText
import com.v1per.synthx.ui.components.LoadingIndicator
import com.v1per.synthx.ui.components.PlaylistRow
import com.v1per.synthx.ui.theme.Background
import com.v1per.synthx.ui.theme.TextSecondary

@Composable
fun GroupDetailScreen(
    topBar: @Composable () -> Unit,
    onPlaylistClick: (playlistId: String, playlistName: String) -> Unit,
    groupId: String,
    // Opens the group picker, hosted by MainScaffold above the bottom nav/player — see
    // PlaylistRoutes/SynthXNavHost.
    onShowGroupPicker: (
        groups: List<PlaylistGroup>,
        currentGroupId: String?,
        onGroupSelected: (groupId: String) -> Unit,
    ) -> Unit = { _, _, _ -> },
    viewModel: GroupDetailViewModel = viewModel(
        key = groupId,
        factory = GroupDetailViewModel.factory(LocalContext.current, groupId),
    ),
) {
    val uiState by viewModel.uiState.collectAsState()
    val groups by viewModel.groups.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.actionErrors.collect { error ->
            val messageRes = when (error) {
                GroupDetailViewModel.ActionError.RenameFailed -> R.string.error_rename_playlist
                GroupDetailViewModel.ActionError.DeleteFailed -> R.string.error_delete_playlist
                GroupDetailViewModel.ActionError.RemoveFromGroupFailed -> R.string.error_remove_from_group
                GroupDetailViewModel.ActionError.MoveToGroupFailed -> R.string.error_move_playlist
            }
            snackbarHostState.showSnackbar(context.getString(messageRes))
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(Background)) {
        topBar()

        Box(modifier = Modifier.fillMaxSize()) {
            when (val state = uiState) {
                is UiState.Loading -> LoadingIndicator()
                is UiState.Empty -> Text(
                    text = stringResource(R.string.empty_group_no_playlists),
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextSecondary,
                    modifier = Modifier.align(Alignment.Center),
                )
                is UiState.Success -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(state.data, key = { it.id }) { playlist ->
                        // Every playlist here is in this group, so its menu offers "Move to
                        // group" (picker, current group checked and inert) and "Remove from
                        // group" (direct) as two separate items. Both used to collapse into one
                        // onMoveToGroup that discarded its argument and always ungrouped, and
                        // availableGroups was never passed at all — so the move item never even
                        // rendered here.
                        PlaylistRow(
                            playlist = playlist,
                            onClick = { onPlaylistClick(playlist.id, playlist.name) },
                            onRename = { name -> viewModel.renamePlaylist(playlist.id, name) },
                            onDelete = { viewModel.deletePlaylist(playlist.id) },
                            onShowGroupPicker = {
                                onShowGroupPicker(groups, playlist.groupId) { targetGroupId ->
                                    viewModel.moveToGroup(playlist.id, targetGroupId)
                                }
                            },
                            onRemoveFromGroup = { viewModel.removeFromGroup(playlist.id) },
                            availableGroups = groups,
                        )
                    }
                }
                is UiState.Error -> LoadErrorText(
                    message = state.message,
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}
