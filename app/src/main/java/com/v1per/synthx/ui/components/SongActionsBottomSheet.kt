package com.v1per.synthx.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlaylistRemove
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.v1per.synthx.R
import com.v1per.synthx.data.repository.Playlist
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.ui.theme.Alpha
import com.v1per.synthx.ui.theme.Error
import com.v1per.synthx.ui.theme.Hairline
import com.v1per.synthx.ui.theme.Spacing
import com.v1per.synthx.ui.theme.TextPrimary
import com.v1per.synthx.ui.theme.TextTertiary
import kotlinx.collections.immutable.ImmutableList
// Aliased: the Material3 `Surface` composable owns the simple name in Compose files.
import com.v1per.synthx.ui.theme.Surface as SurfaceColor

/**
 * Pages are swapped inside a single sheet rather than opened as nested sheets —
 * Material3's ModalBottomSheet does not compose cleanly inside another, and two
 * stacked scrims fight over dismissal.
 */
enum class SheetPage { Actions, Artists, Playlists }

@Composable
fun SongActionsBottomSheet(
    song: Song,
    sheetState: RefinedBottomSheetState,
    playlists: ImmutableList<Playlist>,
    currentPlaylistId: String?,
    showDeleteRow: Boolean,
    deleteEnabled: Boolean,
    showRemoveFromStarredRow: Boolean,
    removeFromStarredEnabled: Boolean,
    isCurrentlyPlaying: Boolean,
    onDeleteSong: () -> Unit,
    onGoToAlbum: (albumId: String) -> Unit,
    onGoToArtist: (artistId: String) -> Unit,
    onAddToPlaylist: (playlistId: String) -> Unit,
    onRemoveFromPlaylist: () -> Unit,
    onRemoveFromStarred: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Keyed to the song so reopening on a different row always starts on Actions, while
    // paging within one sheet session is preserved.
    var page by remember(song.id) { mutableStateOf(SheetPage.Actions) }
    var showDeleteConfirm by remember(song.id) { mutableStateOf(false) }

    RefinedBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SurfaceColor,
        shape = MaterialTheme.shapes.small,
    ) {
        when (page) {
            SheetPage.Actions -> ActionsPage(
                song = song,
                currentPlaylistId = currentPlaylistId,
                showDeleteRow = showDeleteRow,
                deleteEnabled = deleteEnabled,
                showRemoveFromStarredRow = showRemoveFromStarredRow,
                removeFromStarredEnabled = removeFromStarredEnabled,
                isCurrentlyPlaying = isCurrentlyPlaying,
                onDeleteSong = { showDeleteConfirm = true },
                onGoToAlbum = onGoToAlbum,
                onGoToArtist = onGoToArtist,
                onRemoveFromPlaylist = onRemoveFromPlaylist,
                onRemoveFromStarred = onRemoveFromStarred,
                onPlayNext = onPlayNext,
                onAddToQueue = onAddToQueue,
                onShowPlaylists = { page = SheetPage.Playlists },
                onShowArtists = { page = SheetPage.Artists },
            )
            SheetPage.Artists -> SubPage(
                title = stringResource(R.string.actions_go_to_artist),
                items = song.artists.map { it.id to it.name },
                onSelect = onGoToArtist,
            )
            SheetPage.Playlists -> SubPage(
                title = stringResource(R.string.actions_add_to_playlist),
                items = playlists.map { it.id to it.name },
                emptyText = stringResource(R.string.empty_no_playlists),
                onSelect = onAddToPlaylist,
            )
        }
    }

    // Rendered outside the sheet so it stacks above it — cancelling returns to the still
    // open sheet, confirming lets onDeleteSong close the whole thing.
    if (showDeleteConfirm) {
        DeleteSongDialog(
            onConfirm = {
                showDeleteConfirm = false
                onDeleteSong()
            },
            onDismiss = { showDeleteConfirm = false },
        )
    }
}

@Composable
private fun ActionsPage(
    song: Song,
    currentPlaylistId: String?,
    showDeleteRow: Boolean,
    deleteEnabled: Boolean,
    showRemoveFromStarredRow: Boolean,
    removeFromStarredEnabled: Boolean,
    isCurrentlyPlaying: Boolean,
    onDeleteSong: () -> Unit,
    onGoToAlbum: (String) -> Unit,
    onGoToArtist: (String) -> Unit,
    onRemoveFromPlaylist: () -> Unit,
    onRemoveFromStarred: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onShowPlaylists: () -> Unit,
    onShowArtists: () -> Unit,
) {
    Column {
        SongIdentity(
            song = song,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = Spacing.sm),
        )
        HorizontalDivider(thickness = 1.dp, color = Hairline)

        if (showDeleteRow) {
            ActionRow(
                icon = Icons.Default.Delete,
                label = stringResource(R.string.actions_delete_song),
                tint = Error,
                enabled = deleteEnabled,
                onClick = onDeleteSong,
            )
        }

        if (currentPlaylistId != null) {
            ActionRow(
                icon = Icons.Default.PlaylistRemove,
                label = stringResource(R.string.actions_remove_from_playlist),
                onClick = onRemoveFromPlaylist,
            )
        } else {
            ActionRow(
                icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                label = stringResource(R.string.actions_add_to_playlist),
                onClick = onShowPlaylists,
            )
        }

        // Starred screen only. Stays visible but disabled until the song is in at least one
        // playlist: the action exists to file away something already organized, and showing
        // it greyed out explains *why* it isn't available better than hiding it would.
        if (showRemoveFromStarredRow) {
            ActionRow(
                icon = Icons.Default.StarBorder,
                label = stringResource(R.string.actions_remove_from_starred),
                enabled = removeFromStarredEnabled,
                onClick = onRemoveFromStarred,
            )
        }

        // Hidden, not disabled, for the currently playing song — "play next" has no
        // meaning against the song already playing.
        if (!isCurrentlyPlaying) {
            ActionRow(
                icon = Icons.AutoMirrored.Filled.PlaylistPlay,
                label = stringResource(R.string.actions_play_next),
                onClick = onPlayNext,
            )
        }
        ActionRow(
            icon = Icons.AutoMirrored.Filled.QueueMusic,
            label = stringResource(R.string.actions_add_to_queue),
            onClick = onAddToQueue,
        )

        song.albumId?.let { albumId ->
            ActionRow(
                icon = Icons.Default.Album,
                label = stringResource(R.string.actions_go_to_album),
                onClick = { onGoToAlbum(albumId) },
            )
        }

        when (song.artists.size) {
            0 -> Unit
            1 -> ActionRow(
                icon = Icons.Default.Person,
                label = stringResource(R.string.actions_go_to_artist),
                onClick = { onGoToArtist(song.artists.first().id) },
            )
            else -> ActionRow(
                icon = Icons.Default.Person,
                label = stringResource(R.string.actions_go_to_artist),
                onClick = onShowArtists,
            )
        }

        Spacer(modifier = Modifier.height(Spacing.md))
    }
}

/** Shared shape for the Artists and Playlists pages: a caption plus a pickable list. */
@Composable
private fun SubPage(
    title: String,
    items: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
    emptyText: String? = null,
) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = TextTertiary,
            modifier = Modifier.padding(
                horizontal = Spacing.HorizontalPadding,
                vertical = Spacing.sm,
            ),
        )
        HorizontalDivider(thickness = 1.dp, color = Hairline)

        if (items.isEmpty() && emptyText != null) {
            Text(
                text = emptyText,
                style = MaterialTheme.typography.bodyLarge,
                color = TextTertiary,
                modifier = Modifier.padding(
                    horizontal = Spacing.HorizontalPadding,
                    vertical = Spacing.md,
                ),
            )
        } else {
            LazyColumn {
                items(items, key = { it.first }) { (id, name) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(Spacing.xxl)
                            .clickable { onSelect(id) }
                            .padding(horizontal = Spacing.HorizontalPadding),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = name,
                            style = MaterialTheme.typography.bodyLarge,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(Spacing.md))
    }
}

/**
 * The SortBottomSheet row recipe with a leading icon added. Disabled rows stay visible
 * but dim to [Alpha.Disabled] and take no clicks — the canonical disabled-content
 * convention shared with SongRow and PlayerTransportControls.
 */
@Composable
private fun ActionRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tint: androidx.compose.ui.graphics.Color = TextPrimary,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(Spacing.xxl)
            .alpha(if (enabled) 1f else Alpha.Disabled)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = Spacing.HorizontalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(24.dp),
        )
        Spacer(modifier = Modifier.width(Spacing.md))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
