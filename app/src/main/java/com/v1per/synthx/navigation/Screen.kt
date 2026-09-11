package com.v1per.synthx.navigation

import android.net.Uri

sealed class Screen(val route: String) {
    object Onboarding : Screen("onboarding")
    object Home : Screen("home")
    object Library : Screen("library")
    object Search : Screen("search")
    object Settings : Screen("settings")
    object BackupsAndRestore : Screen("backups_and_restore")
    object DeveloperSettings : Screen("developer_settings")
    object LibraryManagement : Screen("library_management")
    object Issues : Screen("issues")
    object ReplaceSong : Screen("replace_song")
    object Restore : Screen("restore")
    object Songs : Screen("songs")
    object Starred : Screen("starred")
    object ArtistList : Screen("artist_list")
    object ArtistDetail : Screen("artist_detail/{artistId}/{artistName}") {
        // Must mirror the constructor arg — a member isn't referencable in the supertype call
        const val ROUTE = "artist_detail/{artistId}/{artistName}"
        fun createRoute(artistId: String, artistName: String) =
            "artist_detail/$artistId/${Uri.encode(artistName)}"
    }
    object AlbumList : Screen("album_list")
    object AlbumDetail : Screen("album_detail/{albumId}/{albumTitle}") {
        // Must mirror the constructor arg — a member isn't referencable in the supertype call
        const val ROUTE = "album_detail/{albumId}/{albumTitle}"
        fun createRoute(albumId: String, albumTitle: String) =
            "album_detail/$albumId/${Uri.encode(albumTitle)}"
    }
    object PlaylistList : Screen("playlist_list")
    object CreatePlaylist : Screen("create_playlist")
    object GroupDetail : Screen("group_detail/{groupId}/{groupName}") {
        // Must mirror the constructor arg — a member isn't referencable in the supertype call
        const val ROUTE = "group_detail/{groupId}/{groupName}"
        fun createRoute(groupId: String, groupName: String) =
            "group_detail/$groupId/${Uri.encode(groupName)}"
    }
    object PlaylistDetail : Screen("playlist_detail/{playlistId}/{playlistName}") {
        // Must mirror the constructor arg — a member isn't referencable in the supertype call
        const val ROUTE = "playlist_detail/{playlistId}/{playlistName}"
        fun createRoute(playlistId: String, playlistName: String) =
            "playlist_detail/$playlistId/${Uri.encode(playlistName)}"
    }
    companion object {
        // Routes reachable by pushing further from Library's root. Shared by MainScaffold
        // (which unions it with Home/Library/Search for bottom-bar visibility) and
        // SynthXBottomNav (which uses it as-is to detect "still inside Library's hierarchy"
        // for reset-to-root-vs-restore nav behavior) — previously each file listed these
        // routes independently and could drift out of sync.
        val libraryDetailRoutes = setOf(
            Songs.route,
            Starred.route,
            ArtistList.route,
            ArtistDetail.ROUTE,
            AlbumList.route,
            AlbumDetail.ROUTE,
            PlaylistList.route,
            CreatePlaylist.route,
            GroupDetail.ROUTE,
            PlaylistDetail.ROUTE,
        )
    }
}
