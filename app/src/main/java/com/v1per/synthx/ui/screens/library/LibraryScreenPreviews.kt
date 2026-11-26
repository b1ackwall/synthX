package com.v1per.synthx.ui.screens.library

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.ui.theme.Background
import com.v1per.synthx.ui.theme.SynthXTheme
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

@Preview(name = "Library Screen", showBackground = true)
@Composable
private fun LibraryScreenPreview() {
    LibraryScreenPreviewContent(
        recentSongs = persistentListOf(
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
        ),
    )
}

// 840dp gives five columns of 152dp; twelve songs leave a short last row.
@Preview(name = "Library Screen, tablet width", showBackground = true, widthDp = 840, heightDp = 1280)
@Composable
private fun LibraryScreenTabletPreview() {
    LibraryScreenPreviewContent(
        recentSongs = List(12) { index ->
            Song(
                id = "tablet-$index",
                title = "Track ${index + 1}",
                artist = "Artist",
                album = "Album",
                duration = 200000L,
                filePath = "",
            )
        }.toImmutableList(),
    )
}

@Composable
private fun LibraryScreenPreviewContent(recentSongs: ImmutableList<Song>) {
    SynthXTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Background,
        ) {
            LibraryScreenContent(
                recentSongs = recentSongs,
                error = null,
                isLoading = false,
                starredCount = 12,
                onPlaylistsClick = {},
                onArtistsClick = {},
                onAlbumsClick = {},
                onSongsClick = {},
                onStarredClick = {},
                onSeeAllClick = {},
                onSongClick = {},
            )
        }
    }
}
