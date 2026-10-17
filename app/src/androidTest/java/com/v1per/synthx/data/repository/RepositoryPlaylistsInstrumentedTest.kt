package com.v1per.synthx.data.repository

import android.database.sqlite.SQLiteConstraintException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.ui.testing.TestTimeouts
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Playlists and their songs changed through the real repository, checked in real Room. */
@RunWith(AndroidJUnit4::class)
class RepositoryPlaylistsInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER) val testTimeout = TestTimeouts.device()

    private lateinit var fx: RepositoryTestFixture
    private val repository get() = fx.repository

    @Before fun setUp() = runBlocking {
        fx = RepositoryTestFixture(ApplicationProvider.getApplicationContext())
        listOf("a", "b", "c", "d").forEach { fx.song(it) }
    }

    @After fun tearDown() = fx.close()

    private suspend fun songIds(playlistId: String) = fx.playlistDao.getSongIdsForPlaylist(playlistId)

    private suspend fun allPlaylists(what: String, done: (List<Playlist>) -> Boolean) =
        awaitEmission(repository.getAllPlaylists(), what, done = done)

    @Test fun createdPlaylistIsListedUngroupedWithArt() = runBlocking {
        val id = repository.createPlaylist("Road trip", null)

        val playlist = allPlaylists("all playlists") { it.isNotEmpty() }.single()
        assertEquals(id, playlist.id)
        assertEquals("Road trip", playlist.name)
        assertEquals(null, playlist.groupId)
        assertNotNull("a new playlist gets its art hue at creation", playlist.artHue)
        assertNotNull(playlist.artStructureSeed)
        assertEquals(listOf(id), awaitEmission(repository.getUngroupedPlaylists(), "ungrouped") { it.isNotEmpty() }.map { it.id })
        assertTrue(songIds(id).isEmpty())
    }

    @Test fun playlistCreatedInAGroupIsListedOnlyUnderThatGroup() = runBlocking {
        fx.group("g", "Group")
        val inGroup = repository.createPlaylist("In group", "g")
        val loose = repository.createPlaylist("Loose", null)

        assertEquals(listOf(inGroup), awaitEmission(repository.getPlaylistsForGroup("g"), "group g") { it.isNotEmpty() }.map { it.id })
        assertEquals(listOf(loose), awaitEmission(repository.getUngroupedPlaylists(), "ungrouped") { it.isNotEmpty() }.map { it.id })
    }

    @Test fun playlistsAreSortedByNameIgnoringCase() = runBlocking {
        listOf("rock", "ambient", "Jazz", "blues").forEach { repository.createPlaylist(it, null) }

        val names = allPlaylists("all playlists") { it.size == 4 }.map { it.name }

        assertEquals(listOf("ambient", "blues", "Jazz", "rock"), names)
    }

    @Test fun pinnedTwoPlaylistsMayShareAName() = runBlocking {
        val first = repository.createPlaylist("Same", null)
        val second = repository.createPlaylist("Same", null)

        val listed = allPlaylists("all playlists") { it.size == 2 }
        assertEquals(setOf(first, second), listed.map { it.id }.toSet())
    }

    @Test fun addingSongsAppendsThemInTheOrderAdded() = runBlocking {
        fx.playlist("p")

        listOf("c", "a", "d").forEach { assertTrue(repository.addSongToPlaylist("p", it)) }

        assertEquals(listOf("c", "a", "d"), songIds("p"))
        val listed = awaitEmission(repository.getSongsForPlaylist("p"), "playlist songs") { it.size == 3 }
        assertEquals(listOf("c", "a", "d"), listed.map { it.id })
    }

    @Test fun addingASongTwiceKeepsOneEntryInItsFirstPlace() = runBlocking {
        fx.playlist("p", songIds = arrayOf("a", "b"))

        assertFalse(repository.addSongToPlaylist("p", "a"))

        assertEquals(listOf("a", "b"), songIds("p"))
    }

    @Test fun removingASongKeepsTheOthersInOrderAndOtherPlaylistsUntouched() = runBlocking {
        fx.playlist("p", songIds = arrayOf("a", "b", "c", "d"))
        fx.playlist("q", songIds = arrayOf("b", "a"))

        repository.removeSongFromPlaylist("p", "b")

        assertEquals(listOf("a", "c", "d"), songIds("p"))
        assertEquals(listOf("b", "a"), songIds("q"))
        assertNotNull("the song itself stays in the library", fx.songDao.getSongById("b"))
        assertEquals(listOf("q"), repository.getPlaylistIdsForSong("b"))
    }

    @Test fun aRemovedSongAddedBackGoesToTheEnd() = runBlocking {
        fx.playlist("p", songIds = arrayOf("a", "b", "c"))

        repository.removeSongFromPlaylist("p", "a")
        assertTrue(repository.addSongToPlaylist("p", "a"))

        assertEquals(listOf("b", "c", "a"), songIds("p"))
    }

    @Test fun pinnedRemovingASongLeavesAGapInStoredPositions() = runBlocking {
        fx.playlist("p", songIds = arrayOf("a", "b", "c"))

        repository.removeSongFromPlaylist("p", "b")

        val positions = listOf("a", "c").map { id ->
            fx.playlistDao.getPlaylistMembershipsForSong(id).single().position
        }
        assertEquals(listOf(0, 2), positions)
    }

    @Test fun playlistIdsForASongNameEveryPlaylistItIsIn() = runBlocking {
        fx.playlist("p", songIds = arrayOf("a"))
        fx.playlist("q", songIds = arrayOf("b"))
        fx.playlist("r", songIds = arrayOf("b", "a"))

        assertEquals(setOf("p", "r"), repository.getPlaylistIdsForSong("a").toSet())
        assertEquals(emptyList<String>(), repository.getPlaylistIdsForSong("d"))
    }

    @Test fun renamingKeepsTheIdGroupAndEverySongInOrder() = runBlocking {
        fx.group("g")
        fx.playlist("p", name = "Old", groupId = "g", songIds = arrayOf("d", "b", "a"))

        repository.renamePlaylist("p", "New")

        val renamed = allPlaylists("renamed playlist") { list -> list.any { it.name == "New" } }.single()
        assertEquals("p", renamed.id)
        assertEquals("g", renamed.groupId)
        assertEquals(listOf("d", "b", "a"), songIds("p"))
        assertTrue(fx.playlistDao.getPlaylistsByIds(listOf("p")).single().updatedAt > 1)
    }

    @Test fun deletingAPlaylistDropsOnlyItsOwnEntries() = runBlocking {
        fx.playlist("p", songIds = arrayOf("a", "b"))
        fx.playlist("q", songIds = arrayOf("b", "c"))

        repository.deletePlaylist("p")

        assertEquals(listOf("q"), allPlaylists("after delete") { it.size == 1 }.map { it.id })
        assertTrue(songIds("p").isEmpty())
        assertEquals(listOf("b", "c"), songIds("q"))
        listOf("a", "b").forEach { assertNotNull("song $it stays in the library", fx.songDao.getSongById(it)) }
        assertEquals(listOf("q"), repository.getPlaylistIdsForSong("b"))
    }

    @Test fun pinnedAddingToADeletedPlaylistThrowsAndWritesNothing() = runBlocking {
        fx.playlist("p")
        repository.deletePlaylist("p")

        try {
            repository.addSongToPlaylist("p", "a")
            fail("adding to a deleted playlist should be refused by the foreign key")
        } catch (expected: SQLiteConstraintException) {
            // Refused by playlist_songs' foreign key.
        }
        assertTrue(repository.getPlaylistIdsForSong("a").isEmpty())
    }

    @Test fun movingAPlaylistBetweenGroupsKeepsItsSongs() = runBlocking {
        fx.group("g")
        fx.playlist("p", songIds = arrayOf("c", "a"))

        repository.movePlaylistToGroup("p", "g")
        awaitEmission(repository.getPlaylistsForGroup("g"), "group g after the move") { list -> list.map { it.id } == listOf("p") }
        repository.movePlaylistToGroup("p", null)

        awaitEmission(repository.getUngroupedPlaylists(), "ungrouped after the move back") { list -> list.map { it.id } == listOf("p") }
        assertEquals(listOf("c", "a"), songIds("p"))
    }

    @Test fun groupsAreCreatedRenamedAndSortedIgnoringCase() = runBlocking {
        repository.createGroup("work")
        repository.createGroup("chill")
        repository.createGroup("Moods")
        val groups = awaitEmission(repository.getAllGroups(), "groups") { it.size == 3 }
        assertEquals(listOf("chill", "Moods", "work"), groups.map { it.name })

        repository.renameGroup(groups.first { it.name == "work" }.id, "Archive")

        awaitEmission(repository.getAllGroups(), "groups after rename") { list ->
            list.map { it.name } == listOf("Archive", "chill", "Moods")
        }
        Unit
    }

    @Test fun deletingAGroupMovesItsPlaylistsToNoGroupWithTheirSongs() = runBlocking {
        fx.group("g")
        fx.group("other")
        fx.playlist("p", groupId = "g", songIds = arrayOf("a", "b"))
        fx.playlist("q", groupId = "other", songIds = arrayOf("c"))

        repository.deleteGroup("g")

        awaitEmission(repository.getAllGroups(), "groups after delete") { list -> list.map { it.id } == listOf("other") }
        val p = allPlaylists("playlists after group delete") { list -> list.any { it.id == "p" && it.groupId == null } }
            .first { it.id == "p" }
        assertEquals(null, p.groupId)
        assertEquals(listOf("a", "b"), songIds("p"))
        assertEquals(listOf("q"), awaitEmission(repository.getPlaylistsForGroup("other"), "group other") { it.isNotEmpty() }.map { it.id })
        assertEquals(listOf("p"), awaitEmission(repository.getUngroupedPlaylists(), "ungrouped") { it.isNotEmpty() }.map { it.id })
    }
}
