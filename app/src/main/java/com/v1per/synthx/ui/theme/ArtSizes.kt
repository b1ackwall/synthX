package com.v1per.synthx.ui.theme

import androidx.compose.ui.unit.dp

/** Edge lengths for the thumbnail at the start of a list row; each row type has its own size. */
object ArtSizes {
    /** Playlist rows. */
    val Playlist = 40.dp

    /** Album rows, including album search results. */
    val Album = 44.dp

    /** Song rows, and the art placeholder's default. */
    val Song = 48.dp
}
