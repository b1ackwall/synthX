package com.v1per.synthx.ui.screens.library

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.ui.theme.Background
import com.v1per.synthx.ui.theme.SynthXTheme
import kotlinx.collections.immutable.persistentListOf

@Preview(name = "Library Screen", showBackground = true)
@Composable
private fun LibraryScreenPreview() {
    SynthXTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Background,
        ) {
            LibraryScreenContent(
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
