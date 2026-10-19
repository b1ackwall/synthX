package com.v1per.synthx.ui.common

import android.content.Context
import androidx.annotation.StringRes
import com.v1per.synthx.R

/**
 * Typed load-failure reason for the simple "one flow, one failure mode" ViewModels — resolved
 * to app-voice strings.xml copy here because these ViewModels feed [UiState.Error], which is
 * built with a resolved [String]. Shared across all of them since they differ only in *what*
 * failed to load, not in how the failure should reach the UI — one enum for all of them, and
 * the UI never shows the raw exception message.
 */
enum class LoadError(@StringRes private val messageRes: Int) {
    Songs(R.string.error_load_songs),
    Starred(R.string.error_load_starred),
    Albums(R.string.error_load_albums),
    AlbumSongs(R.string.error_load_album_songs),
    Artists(R.string.error_load_artists),
    ArtistSongs(R.string.error_load_artist_songs),
    PlaylistSongs(R.string.error_load_playlist),
    GroupPlaylists(R.string.error_load_group_playlists),
    PlaylistList(R.string.error_load_playlists_list),
    RecentlyAdded(R.string.error_load_recently_added),
    Search(R.string.error_search),
    ;

    fun message(context: Context): String = context.getString(messageRes)

    /** Same resolution, without a real [Context] — lets a plain unit test supply a fake
     *  string resolver instead of needing Robolectric just to call [Context.getString]. */
    fun message(resolve: (Int) -> String): String = resolve(messageRes)
}
