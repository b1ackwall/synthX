package com.v1per.synthx.data.repository

data class Playlist(
    val id: String,
    val name: String,
    val groupId: String?,
    val albumArtUri: String?,
    /**
     * The two values that fully determine this playlist's generated cover art — see
     * [com.v1per.synthx.data.db.PlaylistEntity.artHue]. Both null means "not generated yet",
     * which [com.v1per.synthx.ui.components.PlaylistArt] renders as the flat placeholder.
     */
    val artHue: Int? = null,
    val artStructureSeed: Long? = null,
)
