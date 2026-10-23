package com.v1per.synthx.ui.screens.albumlist

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.v1per.synthx.R
import com.v1per.synthx.data.repository.Album
import com.v1per.synthx.ui.common.UiState
import com.v1per.synthx.ui.components.ArtPlaceholder
import com.v1per.synthx.ui.components.LoadingIndicator
import com.v1per.synthx.ui.components.crossfadeModel
import com.v1per.synthx.ui.theme.Accent
import com.v1per.synthx.ui.theme.Alpha
import com.v1per.synthx.ui.theme.ArtSizes
import com.v1per.synthx.ui.theme.Background
import com.v1per.synthx.ui.theme.Error
import com.v1per.synthx.ui.theme.Hairline
import com.v1per.synthx.ui.theme.Spacing
import com.v1per.synthx.ui.theme.Strokes
import com.v1per.synthx.ui.theme.SynthXTheme
import com.v1per.synthx.ui.theme.TextPrimary
import com.v1per.synthx.ui.theme.TextSecondary

@Composable
fun AlbumListScreen(
    topBar: @Composable () -> Unit,
    onAlbumClick: (albumId: String, albumTitle: String) -> Unit,
    viewModel: AlbumListViewModel = viewModel(
        factory = AlbumListViewModel.factory(LocalContext.current),
    ),
) {
    val uiState by viewModel.uiState.collectAsState()

    Column(modifier = Modifier.fillMaxSize().background(Background)) {
        topBar()

        AlbumListContent(
            uiState = uiState,
            onAlbumClick = onAlbumClick,
        )
    }
}

@Composable
private fun AlbumListContent(
    uiState: UiState<List<Album>>,
    onAlbumClick: (albumId: String, albumTitle: String) -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        when (uiState) {
            is UiState.Loading -> LoadingIndicator()

            is UiState.Empty -> Text(
                text = stringResource(R.string.empty_no_albums),
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

            is UiState.Success -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
            ) {
                items(uiState.data, key = { it.id }) { album ->
                    AlbumRow(
                        album = album,
                        onClick = { onAlbumClick(album.id, album.title) },
                    )
                }
            }
        }
    }
}

// Tapping through to an all-missing album's detail screen is allowed — that screen
// (AlbumDetailScreen) shows its missing songs dimmed via SongRow rather than empty, so
// there's a real destination there. Only the alpha dims here, unlike SongRow's fully
// disabled row: a dead end tap target would leave the album's missing songs unreachable.
@Composable
private fun AlbumRow(album: Album, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }

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
                .padding(horizontal = Spacing.HorizontalPadding)
                .alpha(if (album.allSongsMissing) Alpha.Disabled else 1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (album.albumArtUri != null) {
                AsyncImage(
                    model = crossfadeModel(album.albumArtUri),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(ArtSizes.Album)
                        .clip(MaterialTheme.shapes.small),
                )
            } else {
                ArtPlaceholder(size = ArtSizes.Album)
            }
            Spacer(modifier = Modifier.width(Spacing.md))
            Column {
                Text(
                    text = album.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = album.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        HorizontalDivider(
            thickness = Strokes.Hairline,
            color = Hairline,
        )
    }
}

@Preview(name = "Album List — Success", showBackground = true)
@Composable
private fun AlbumListPreview() {
    SynthXTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Background,
        ) {
            AlbumListContent(
                uiState = UiState.Success(
                    data = listOf(
                        Album(id = "1", title = "Ultraviolet", artist = "v1per", albumArtUri = null),
                        Album(id = "2", title = "Fragments", artist = "KNTRL", albumArtUri = null),
                        Album(id = "3", title = "Deep Cut", artist = "Null Signal", albumArtUri = null),
                    ),
                ),
                onAlbumClick = { _, _ -> },
            )
        }
    }
}
