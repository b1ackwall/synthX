package com.v1per.synthx.ui.screens.songs

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.viewmodel.compose.viewModel
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.playback.PlaybackViewModel
import com.v1per.synthx.ui.components.SongRow
import com.v1per.synthx.ui.theme.Accent
import com.v1per.synthx.ui.theme.Background
import com.v1per.synthx.ui.theme.Spacing
// Aliased: the Material3 `Surface` composable already owns this simple name in this file.
import com.v1per.synthx.ui.theme.Surface as SurfaceColor
import com.v1per.synthx.ui.theme.SynthXTheme
import com.v1per.synthx.ui.theme.TextPrimary
import com.v1per.synthx.ui.theme.TextSecondary

@Composable
fun SongsScreen(
    topBar: @Composable (sortOrder: SortOrder, onSortClick: () -> Unit) -> Unit,
    playbackViewModel: PlaybackViewModel,
) {
    val context = LocalContext.current
    val viewModel: SongsViewModel = viewModel(
        factory = SongsViewModel.Factory(context, playbackViewModel),
    )
    val uiState by viewModel.uiState.collectAsState()
    val currentlyPlayingSongId by viewModel.currentlyPlayingSongId.collectAsState()
    val currentSortOrder by viewModel.sortOrder.collectAsState()

    var showSortSheet by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        topBar(currentSortOrder, { showSortSheet = true })

        SongsScreenContent(
            uiState = uiState,
            currentlyPlayingSongId = currentlyPlayingSongId,
            onSongTap = viewModel::onSongTap,
        )
    }

    if (showSortSheet) {
        SortBottomSheet(
            selectedOrder = currentSortOrder,
            onOrderSelected = { order ->
                viewModel.setSortOrder(order)
                showSortSheet = false
            },
            onDismiss = { showSortSheet = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SortBottomSheet(
    selectedOrder: SortOrder,
    onOrderSelected: (SortOrder) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = SurfaceColor,
    ) {
        Text(
            text = "Sort by",
            style = MaterialTheme.typography.labelLarge,
            color = TextSecondary,
            modifier = Modifier.padding(horizontal = Spacing.HorizontalPadding),
        )
        SortOrder.entries.forEach { order ->
            val selected = order == selectedOrder
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(Spacing.xxl)
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

private val SortOrder.label: String
    get() = when (this) {
        SortOrder.RECENTLY_ADDED -> "Recently Added"
        SortOrder.TITLE -> "Title"
        SortOrder.ARTIST -> "Artist"
        SortOrder.ALBUM -> "Album"
    }

@Composable
private fun SongsScreenContent(
    uiState: UiState,
    currentlyPlayingSongId: String?,
    onSongTap: (Song) -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        when (uiState) {
            is UiState.Loading -> CircularProgressIndicator(
                color = Accent,
                modifier = Modifier.align(Alignment.Center),
            )
            is UiState.Empty -> Text(
                text = "No songs found",
                style = MaterialTheme.typography.bodyLarge,
                color = TextSecondary,
                modifier = Modifier.align(Alignment.Center),
            )
            is UiState.Error -> Text(
                text = uiState.message,
                style = MaterialTheme.typography.bodyLarge,
                color = TextSecondary,
                modifier = Modifier.align(Alignment.Center),
            )
            is UiState.Success -> SongList(
                songs = uiState.songs,
                currentlyPlayingSongId = currentlyPlayingSongId,
                onSongTap = onSongTap,
            )
        }
    }
}

@Composable
private fun SongList(
    songs: List<Song>,
    currentlyPlayingSongId: String?,
    onSongTap: (Song) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = Spacing.md),
    ) {
        items(songs, key = { it.id }) { song ->
            SongRow(
                song = song,
                isCurrentlyPlaying = song.id == currentlyPlayingSongId,
                onClick = { onSongTap(song) },
            )
        }
    }
}

@Preview(name = "Songs Screen — Success", showBackground = true)
@Composable
private fun SongsScreenPreview() {
    val sampleSongs = listOf(
        Song(id = "1", title = "Neon Pulse", artist = "v1per", album = "Ultraviolet", duration = 243000L, filePath = ""),
        Song(id = "2", title = "Static Bloom", artist = "KNTRL", album = "Fragments", duration = 198000L, filePath = ""),
        Song(id = "3", title = "Glitch Cascade", artist = "v1per", album = "Ultraviolet", duration = 317000L, filePath = ""),
        Song(id = "4", title = "Low Orbit", artist = "Null Signal", album = "Deep Cut", duration = 272000L, filePath = ""),
        Song(id = "5", title = "Severed Link", artist = "KNTRL", album = "Fragments", duration = 205000L, filePath = "", isMissing = true),
    )
    SynthXTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Background,
        ) {
            SongsScreenContent(
                uiState = UiState.Success(sampleSongs),
                currentlyPlayingSongId = "1",
                onSongTap = {},
            )
        }
    }
}
