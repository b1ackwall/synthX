package com.v1per.synthx.navigation

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Touching a [Screen] subclass object before [Screen] itself must work. Class initialization
 * happens once per process, so each first-touch case only proves anything as the first access in
 * a fresh process: run one method per `am instrument` invocation (`-e class <this class>#<method>`).
 * Every method reads the subclass route as its first statement and touches nothing else of [Screen]
 * before it.
 */
@RunWith(AndroidJUnit4::class)
class ScreenInitOrderInstrumentedTest {
    @Test fun songsTouchedFirst() {
        assertEquals("songs", Screen.Songs.route)
    }

    @Test fun starredTouchedFirst() {
        assertEquals("starred", Screen.Starred.route)
    }

    @Test fun artistListTouchedFirst() {
        assertEquals("artist_list", Screen.ArtistList.route)
    }

    @Test fun albumListTouchedFirst() {
        assertEquals("album_list", Screen.AlbumList.route)
    }

    @Test fun playlistListTouchedFirst() {
        assertEquals("playlist_list", Screen.PlaylistList.route)
    }

    @Test fun createPlaylistTouchedFirst() {
        assertEquals("create_playlist", Screen.CreatePlaylist.route)
    }

    @Test fun libraryDetailRoutesHoldTheTenRoutes() {
        assertEquals(
            setOf(
                "songs",
                "starred",
                "artist_list",
                "artist_detail/{artistId}/{artistName}",
                "album_list",
                "album_detail/{albumId}/{albumTitle}",
                "playlist_list",
                "create_playlist",
                "group_detail/{groupId}/{groupName}",
                "playlist_detail/{playlistId}/{playlistName}",
            ),
            Screen.libraryDetailRoutes,
        )
    }
}
