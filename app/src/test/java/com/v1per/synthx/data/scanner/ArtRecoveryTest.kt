package com.v1per.synthx.data.scanner

import com.v1per.synthx.data.db.SongEntity
import kotlin.test.Test
import kotlin.test.assertEquals

class ArtRecoveryTest {
    private fun song(id: String, isMissing: Boolean = false) = SongEntity(
        id = id,
        title = "Title $id",
        albumId = null,
        duration = 1000L,
        filePath = "content://fake/$id",
        albumArtUri = "content://fake-art/$id",
        year = null,
        dateAdded = 0L,
        isMissing = isMissing,
    )

    @Test fun excludesSongsWhoseArtExists() {
        val songs = listOf(song("1"), song("2"))
        val result = ArtRecovery.findMissingArt(songs) { id -> id == "1" }
        assertEquals(listOf(song("2")), result)
    }

    @Test fun excludesAlreadyMissingSongsRegardlessOfArtState() {
        val songs = listOf(song("1", isMissing = true))
        val result = ArtRecovery.findMissingArt(songs) { false }
        assertEquals(emptyList(), result)
    }

    @Test fun includesSongsWhoseArtIsGone() {
        val songs = listOf(song("1"))
        val result = ArtRecovery.findMissingArt(songs) { false }
        assertEquals(songs, result)
    }

    @Test fun emptyInputProducesEmptyOutput() {
        assertEquals(emptyList(), ArtRecovery.findMissingArt(emptyList()) { true })
    }

    @Test fun mixOfPresentMissingAndAlreadyFlaggedSongs() {
        val songs = listOf(
            song("has-art"),
            song("no-art"),
            song("missing-audio", isMissing = true),
        )
        val result = ArtRecovery.findMissingArt(songs) { id -> id == "has-art" }
        assertEquals(listOf(song("no-art")), result)
    }
}
