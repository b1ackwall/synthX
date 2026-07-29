package com.v1per.synthx.navigation

import android.net.Uri

sealed class Screen(val route: String) {
    object Home : Screen("home")
    object Library : Screen("library")
    object Search : Screen("search")
    object Settings : Screen("settings")
    object Songs : Screen("songs")
    object ArtistList : Screen("artist_list")
    object ArtistDetail : Screen("artist_detail/{artistId}/{artistName}") {
        // Must mirror the constructor arg — a member isn't referencable in the supertype call
        const val ROUTE = "artist_detail/{artistId}/{artistName}"
        fun createRoute(artistId: String, artistName: String) =
            "artist_detail/$artistId/${Uri.encode(artistName)}"
    }
}
