package com.v1per.synthx.ui.screens.songs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.viewmodel.compose.viewModel
import com.v1per.synthx.R
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.playback.PlaybackViewModel
import com.v1per.synthx.ui.common.UiState
import com.v1per.synthx.ui.components.LoadingIndicator
import com.v1per.synthx.ui.components.RefinedBottomSheet
import com.v1per.synthx.ui.components.RefinedBottomSheetState
import com.v1per.synthx.ui.components.SongRow
import com.v1per.synthx.ui.theme.Background
import com.v1per.synthx.ui.theme.Error
import com.v1per.synthx.ui.theme.Spacing
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
// Aliased: the Material3 `Surface` composable already owns this simple name in this file.
import com.v1per.synthx.ui.theme.Surface as SurfaceColor
import com.v1per.synthx.ui.theme.SynthXTheme
import com.v1per.synthx.ui.theme.TextPrimary
import com.v1per.synthx.ui.theme.TextSecondary

@Composable
fun SongsScreen(
    topBar: @Composable (onSortClick: () -> Unit) -> Unit,
    playbackViewModel: PlaybackViewModel,
    // Rendering (and the sheet's own state) lives at MainScaffold level so the sheet draws
    // above the bottom nav/player as a structural sibling of BottomChromeOverlay — this
    // screen only ever needs to hand off "show it, with this order and this setter" and
    // never mounts a RefinedBottomSheet of its own. See MainScaffold's sortSheetTarget.
    onShowSortSheet: (currentOrder: SortOrder, onOrderSelected: (SortOrder) -> Unit) -> Unit,
    viewModel: SongsViewModel = viewModel(
        factory = SongsViewModel.Factory(LocalContext.current, playbackViewModel),
    ),
) {
    val uiState by viewModel.uiState.collectAsState()
    val currentlyPlayingSongId by viewModel.currentlyPlayingSongId.collectAsState()
    val currentSortOrder by viewModel.sortOrder.collectAsState()

    Column(modifier = Modifier.fillMaxSize().background(Background)) {
        topBar({ onShowSortSheet(currentSortOrder, viewModel::setSortOrder) })

        SongsScreenContent(
            uiState = uiState,
            currentlyPlayingSongId = currentlyPlayingSongId,
            onSongTap = viewModel::onSongTap,
        )
    }
}

/** Rendered from MainScaffold, above the bottom chrome — see [SongsScreen]'s onShowSortSheet. */
@Composable
internal fun SortBottomSheet(
    selectedOrder: SortOrder,
    sheetState: RefinedBottomSheetState,
    onOrderSelected: (SortOrder) -> Unit,
    onDismiss: () -> Unit,
) {
    RefinedBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SurfaceColor,
        shape = MaterialTheme.shapes.small,
        showDragHandle = false,
    ) {
        Text(
            text = stringResource(R.string.sort_by),
            style = MaterialTheme.typography.labelLarge,
            color = TextSecondary,
            modifier = Modifier.padding(horizontal = Spacing.HorizontalPadding),
        )
        SortOrder.entries.forEach { order ->
            val selected = order == selectedOrder
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(Spacing.RowHeight)
                    .clickable { onOrderSelected(order) }
                    .padding(horizontal = Spacing.HorizontalPadding),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = order.label,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    ),
                    color = TextPrimary,
                )
            }
        }
    }
}

// Composable getter: stringResource needs a composition to read resources from.
private val SortOrder.label: String
    @Composable get() = when (this) {
        SortOrder.RECENTLY_ADDED -> stringResource(R.string.sort_recently_added)
        SortOrder.TITLE -> stringResource(R.string.sort_title)
        SortOrder.ARTIST -> stringResource(R.string.sort_artist)
        SortOrder.ALBUM -> stringResource(R.string.sort_album)
    }

@Composable
private fun SongsScreenContent(
    uiState: UiState<ImmutableList<Song>>,
    currentlyPlayingSongId: String?,
    onSongTap: (Song) -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        when (uiState) {
            is UiState.Loading -> LoadingIndicator()

            is UiState.Empty -> Text(
                text = stringResource(R.string.empty_no_songs),
                style = MaterialTheme.typography.bodyLarge,
                color = TextSecondary,
                modifier = Modifier.align(Alignment.Center),
            )

            is UiState.Error -> Text(
                text = uiState.message,
                style = MaterialTheme.typography.bodyLarge,
                color = Error,
                modifier = Modifier.align(Alignment.Center),
            )

            is UiState.Success -> SongList(
                songs = uiState.data,
                currentlyPlayingSongId = currentlyPlayingSongId,
                onSongTap = onSongTap,
            )
        }
    }
}

@Composable
private fun SongList(songs: ImmutableList<Song>, currentlyPlayingSongId: String?, onSongTap: (Song) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = Spacing.md),
    ) {
        items(songs, key = { it.id }) { song ->
            // Remembered per song id so an unrelated recomposition (e.g. isCurrentlyPlaying
            // toggling for a different row) doesn't hand this row a fresh lambda instance
            // and defeat Compose's row-level skip.
            val onClick = remember(song.id, onSongTap) { { onSongTap(song) } }
            SongRow(
                song = song,
                isCurrentlyPlaying = song.id == currentlyPlayingSongId,
                onClick = onClick,
            )
        }
    }
}

@Preview(name = "Songs Screen — Success", showBackground = true)
@Composable
private fun SongsScreenPreview() {
    val sampleSongs = persistentListOf(
        Song(
            id = "1",
            title = "Neon Pulse",
            artist = "v1per",
            album = "Ultraviolet",
            duration = 243000L,
            filePath = "",
        ),
        Song(
            id = "2",
            title = "Static Bloom",
            artist = "KNTRL",
            album = "Fragments",
            duration = 198000L,
            filePath = "",
        ),
        Song(
            id = "3",
            title = "Glitch Cascade",
            artist = "v1per",
            album = "Ultraviolet",
            duration = 317000L,
            filePath = "",
        ),
        Song(
            id = "4",
            title = "Low Orbit",
            artist = "Null Signal",
            album = "Deep Cut",
            duration = 272000L,
            filePath = "",
        ),
        Song(
            id = "5",
            title = "Severed Link",
            artist = "KNTRL",
            album = "Fragments",
            duration = 205000L,
            filePath = "",
            isMissing = true,
        ),
    )
    SynthXTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Background,
        ) {
            SongsScreenContent(
                uiState = UiState.Success(data = sampleSongs),
                currentlyPlayingSongId = "1",
                onSongTap = {},
            )
        }
    }
}
