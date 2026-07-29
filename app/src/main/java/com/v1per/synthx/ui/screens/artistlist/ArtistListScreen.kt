package com.v1per.synthx.ui.screens.artistlist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.v1per.synthx.data.repository.Artist
import com.v1per.synthx.ui.theme.Accent
import com.v1per.synthx.ui.theme.Hairline
import com.v1per.synthx.ui.theme.Spacing
import com.v1per.synthx.ui.theme.SynthXTheme
import com.v1per.synthx.ui.theme.TextPrimary
import com.v1per.synthx.ui.theme.TextSecondary

@Composable
fun ArtistListScreen(
    topBar: @Composable () -> Unit,
    onArtistClick: (artistId: String, artistName: String) -> Unit,
    viewModel: ArtistListViewModel = viewModel(
        factory = ArtistListViewModel.factory(LocalContext.current),
    ),
) {
    val uiState by viewModel.uiState.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        topBar()

        ArtistListContent(
            uiState = uiState,
            onArtistClick = onArtistClick,
        )
    }
}

@Composable
private fun ArtistListContent(
    uiState: ArtistListViewModel.UiState,
    onArtistClick: (artistId: String, artistName: String) -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        when (uiState) {
            is ArtistListViewModel.UiState.Loading -> Unit
            is ArtistListViewModel.UiState.Empty -> Text(
                text = "No artists found",
                style = MaterialTheme.typography.bodyLarge,
                color = TextSecondary,
                modifier = Modifier.align(Alignment.Center),
            )
            is ArtistListViewModel.UiState.Error -> Text(
                text = uiState.message,
                style = MaterialTheme.typography.bodyLarge,
                color = TextSecondary,
                modifier = Modifier.align(Alignment.Center),
            )
            is ArtistListViewModel.UiState.Success -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
            ) {
                items(uiState.artists, key = { it.id }) { artist ->
                    ArtistRow(
                        artist = artist,
                        onClick = { onArtistClick(artist.id, artist.name) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ArtistRow(
    artist: Artist,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(Spacing.xxl)
                .clickable(
                    interactionSource = interactionSource,
                    indication = ripple(color = Accent),
                    onClick = onClick,
                )
                .padding(horizontal = Spacing.HorizontalPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = artist.name,
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        HorizontalDivider(
            thickness = 1.dp,
            color = Hairline,
        )
    }
}

@Preview(name = "Artist List — Success", showBackground = true)
@Composable
private fun ArtistListPreview() {
    SynthXTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            ArtistListContent(
                uiState = ArtistListViewModel.UiState.Success(
                    listOf(
                        Artist(id = "1", name = "v1per"),
                        Artist(id = "2", name = "KNTRL"),
                        Artist(id = "3", name = "Null Signal"),
                    ),
                ),
                onArtistClick = { _, _ -> },
            )
        }
    }
}
