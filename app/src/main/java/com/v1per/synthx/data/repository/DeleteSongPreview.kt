package com.v1per.synthx.data.repository

/** Human-readable consequences resolved immediately before a delete confirmation. */
data class DeleteSongPreview(val playlists: List<DeleteSongPlaylistImpact>)

data class DeleteSongPlaylistImpact(val name: String, val willBecomeEmpty: Boolean)
