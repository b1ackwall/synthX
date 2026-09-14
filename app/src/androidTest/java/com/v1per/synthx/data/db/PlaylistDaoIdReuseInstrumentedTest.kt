package com.v1per.synthx.data.db

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * R1-F02: `insertGroup`/`insertPlaylist` must ABORT (Room's default) rather than REPLACE on a
 * primary-key conflict. REPLACE deletes the existing parent row first, which cascades away
 * everything under it before the "new" row is inserted — a group's `id` reuse silently orphans
 * every playlist that pointed at it (`playlists.group_id` is `ON DELETE SET NULL`), and a
 * playlist's `id` reuse silently deletes every membership row under it
 * (`playlist_songs.playlist_id` is `ON DELETE CASCADE`). No caller ever legitimately reuses an
 * existing id — see [PlaylistDao.insertGroup]/[PlaylistDao.insertPlaylist]'s kdoc — so a
 * collision should throw loudly instead.
 */
@RunWith(AndroidJUnit4::class)
class PlaylistDaoIdReuseInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: SynthXDatabase

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, SynthXDatabase::class.java).build()
    }

    @After fun tearDown() { database.close() }

    @Test fun reusingAGroupIdThrowsAndLeavesItsPlaylistsGroupedStill() = runBlocking<Unit> {
        val dao = database.playlistDao()
        dao.insertGroup(PlaylistGroupEntity(id = "g1", name = "Original", position = 0, createdAt = 1L))
        dao.insertPlaylist(
            PlaylistEntity(
                id = "p1", name = "Playlist", description = null, groupId = "g1", position = 0,
                createdAt = 1L, updatedAt = 1L, coverArtUri = null, artHue = 0, artStructureSeed = 0L,
            ),
        )

        try {
            dao.insertGroup(PlaylistGroupEntity(id = "g1", name = "Colliding", position = 0, createdAt = 2L))
            fail("Reusing an existing group id must throw rather than silently REPLACE")
        } catch (expected: SQLiteConstraintException) {
            // Expected — ABORT on the primary-key conflict.
        }

        // The original group and its playlist's membership in it both survived the aborted
        // insert untouched. Under the old REPLACE strategy this playlist would have been
        // silently orphaned (group_id nulled by the cascade) even though the insert then threw.
        val group = dao.getAllGroups().first().single { it.id == "g1" }
        assertEquals("Original", group.name)
        val playlist = dao.getPlaylistsByIds(listOf("p1")).single()
        assertEquals("g1", playlist.groupId)
    }

    @Test fun reusingAPlaylistIdThrowsAndLeavesItsMembershipsIntact() = runBlocking<Unit> {
        val dao = database.playlistDao()
        val songDao = database.songDao()
        songDao.upsertSong(
            SongEntity(
                id = "s1", title = "Song", albumId = null, duration = 1000L,
                filePath = "content://audio/s1", albumArtUri = null, year = null, dateAdded = 1L,
            ),
        )
        dao.insertPlaylist(
            PlaylistEntity(
                id = "p1", name = "Original", description = null, groupId = null, position = 0,
                createdAt = 1L, updatedAt = 1L, coverArtUri = null, artHue = 0, artStructureSeed = 0L,
            ),
        )
        dao.insertPlaylistSong(PlaylistSongEntity(playlistId = "p1", songId = "s1", position = 0, addedAt = 1L))
        assertTrue(dao.containsSong("p1", "s1"))

        try {
            dao.insertPlaylist(
                PlaylistEntity(
                    id = "p1", name = "Colliding", description = null, groupId = null, position = 0,
                    createdAt = 2L, updatedAt = 2L, coverArtUri = null, artHue = 0, artStructureSeed = 0L,
                ),
            )
            fail("Reusing an existing playlist id must throw rather than silently REPLACE")
        } catch (expected: SQLiteConstraintException) {
            // Expected — ABORT on the primary-key conflict.
        }

        // Under the old REPLACE strategy this membership would have been cascade-deleted the
        // instant the colliding insert replaced the parent playlist row.
        assertTrue(dao.containsSong("p1", "s1"))
        val playlist = dao.getPlaylistsByIds(listOf("p1")).single()
        assertEquals("Original", playlist.name)
    }
}
