package com.v1per.synthx.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.material.icons.filled.Check
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
import com.v1per.synthx.ui.theme.Motion
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
    deleteInProgress: Boolean,
    deletePreview: DeleteSongPreviewState,
    onLoadDeletePreview: (String) -> Unit,
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
    // Ids of playlists the song already belongs to — drives the checkmark/disabled state on
    // the Playlists sub-page. Resolved lazily by the caller: see onPlaylistsPageOpened.
    playlistMemberships: Set<String>,
    // Fired when the Playlists sub-page is navigated to, so the caller can resolve
    // playlistMemberships just-in-time rather than on every sheet open.
    onPlaylistsPageOpened: () -> Unit,
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
        showDragHandle = false,
        enableDragToDismiss = false,
    ) {
        AnimatedContent(
            targetState = page,
            transitionSpec = {
                // Rises+fades in, never slides sideways — this is a bottom sheet, so incoming
                // content reads as arriving from below (matching RefinedBottomSheet's own
                // slide-up entrance) rather than fighting it with horizontal motion. Outgoing
                // content only fades — a competing slide on both layers reads as busy. Same in
                // both directions: a content swap this small doesn't need push/pop semantics.
                // Incoming timing/easing reuses RefinedBottomSheet's own entrance spec
                // (Motion.kt). Outgoing fade runs on the faster token instead of matching
                // duration — running both at DurationNormal left old/new content visibly
                // overlapping mid-transition; a quicker exit clears the old page before the
                // incoming one becomes prominent, without reading as an instant cut.
                (fadeIn(tween(Motion.DurationNormal, easing = Motion.EasingDecelerate)) +
                    slideInVertically(
                        tween(Motion.DurationNormal, easing = Motion.EasingDecelerate),
                        initialOffsetY = { height -> height / 8 },
                    )) togetherWith
                    fadeOut(tween(Motion.DurationFast, easing = Motion.EasingAccelerate))
            },
            label = "SongActionsSheetPage",
        ) { targetPage ->
            when (targetPage) {
                SheetPage.Actions -> ActionsPage(
                    song = song,
                    currentPlaylistId = currentPlaylistId,
                    showDeleteRow = showDeleteRow,
                    deleteEnabled = deleteEnabled,
                    showRemoveFromStarredRow = showRemoveFromStarredRow,
                    removeFromStarredEnabled = removeFromStarredEnabled,
                    isCurrentlyPlaying = isCurrentlyPlaying,
                    onDeleteSong = {
                        showDeleteConfirm = true
                        onLoadDeletePreview(song.id)
                    },
                    onGoToAlbum = onGoToAlbum,
                    onGoToArtist = onGoToArtist,
                    onRemoveFromPlaylist = onRemoveFromPlaylist,
                    onRemoveFromStarred = onRemoveFromStarred,
                    onPlayNext = onPlayNext,
                    onAddToQueue = onAddToQueue,
                    onShowPlaylists = {
                        page = SheetPage.Playlists
                        onPlaylistsPageOpened()
                    },
                    onShowArtists = { page = SheetPage.Artists },
                )
                SheetPage.Artists -> SubPage(
                    title = stringResource(R.string.actions_go_to_artist),
                    items = song.artists.map { PickableItem(it.id, it.name) },
                    onSelect = onGoToArtist,
                )
                SheetPage.Playlists -> SubPage(
                    title = stringResource(R.string.actions_add_to_playlist),
                    items = playlists.map {
                        PickableItem(it.id, it.name, alreadyMember = it.id in playlistMemberships)
                    },
                    emptyText = stringResource(R.string.empty_no_playlists),
                    onSelect = onAddToPlaylist,
                )
            }
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
            confirmEnabled = !deleteInProgress,
            previewState = deletePreview,
            onRetryPreview = { onLoadDeletePreview(song.id) },
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
        }
        // Shown regardless of currentPlaylistId: a song can belong to multiple playlists, so
        // "add to a different one" and "remove from this one" are independent actions rather
        // than mutually exclusive — see SongActionsBottomSheet's playlistMemberships, which
        // shows the current playlist (if any) as already-a-member inside the picker itself.
        ActionRow(
            icon = Icons.AutoMirrored.Filled.PlaylistAdd,
            label = stringResource(R.string.actions_add_to_playlist),
            onClick = onShowPlaylists,
        )

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

/** One row in a [SubPage] list: an id/name pair, plus (Playlists page only) whether the
 *  song is already a member — drives the trailing checkmark and disabled state below. */
private data class PickableItem(
    val id: String,
    val name: String,
    val alreadyMember: Boolean = false,
)

/** Shared shape for the Artists and Playlists pages: a caption plus a pickable list. */
@Composable
private fun SubPage(
    title: String,
    items: List<PickableItem>,
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
                items(items, key = { it.id }) { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(Spacing.xxl)
                            .alpha(if (item.alreadyMember) Alpha.Disabled else 1f)
                            .then(
                                if (item.alreadyMember) Modifier
                                else Modifier.clickable { onSelect(item.id) }
                            )
                            .padding(horizontal = Spacing.HorizontalPadding),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = item.name,
                            style = MaterialTheme.typography.bodyLarge,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (item.alreadyMember) {
                            Spacer(modifier = Modifier.width(Spacing.md))
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = stringResource(R.string.cd_already_in_playlist),
                                tint = TextPrimary,
                                modifier = Modifier.size(24.dp),
                            )
                        }
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
