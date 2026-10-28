package com.v1per.synthx.data.scanner

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.data.scanner.ScanAudioFixtures.flac
import com.v1per.synthx.data.scanner.ScanTestFixture.Companion.bytes
import com.v1per.synthx.data.scanner.ScanTestFixture.Companion.uriOf
import com.v1per.synthx.data.scanner.ScanTestFixture.Companion.write
import com.v1per.synthx.ui.testing.TestTimeouts
import java.io.File
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What a rescan does to the user's own data (playlist entries, the starred flag) when a file is
 * renamed, moved or rewritten. Tests named `pinned…` lock today's behaviour, which is filed as an
 * open question; they are not a statement that the behaviour is right.
 */
@RunWith(AndroidJUnit4::class)
class ScanKeepsUserDataInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.device()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var fx: ScanTestFixture
    private lateinit var music: File
    private lateinit var arrivals: File

    @Before fun setUp() {
        fx = ScanTestFixture(context)
        music = fx.root("music")
        arrivals = fx.root("arrivals")
    }

    @After fun tearDown() = fx.close()

    @Test fun renameKeepsPlaylistEntriesAndTheirOrder() = runBlocking {
        val a = write(music, "a.mp3", bytes(2_000, seed = 1))
        val b = write(music, "b.mp3", bytes(2_000, seed = 2))
        fx.scan(music)
        val idA = fx.requireSongAt(a).id
        val idB = fx.requireSongAt(b).id
        fx.addPlaylist("p1", idB, idA)

        assertTrue(a.renameTo(File(music, "z renamed.mp3")))
        fx.scan(music)

        assertEquals(listOf(idB, idA), fx.playlistDao.getSongIdsForPlaylist("p1"))
    }

    @Test fun renameSendsASameIdRemapCarryingTheNewPath() = runBlocking {
        val old = write(music, "old.mp3", bytes(2_000, seed = 1))
        fx.scan(music)
        val id = fx.requireSongAt(old).id

        val renamed = File(music, "new.mp3")
        assertTrue(old.renameTo(renamed))
        val remaps = fx.recordRemaps(this)
        fx.scan(music)
        coroutineContext.cancelChildren()

        val remap = remaps.single { it.oldId == id }
        assertEquals(id, remap.newSong.id)
        assertEquals(uriOf(renamed), remap.newSong.filePath)
    }

    @Test fun goneFileKeepsItsPlaylistEntryWhileMissing() = runBlocking {
        val gone = write(music, "gone.mp3", bytes(2_000, seed = 1))
        fx.scan(music)
        val id = fx.requireSongAt(gone).id
        fx.addPlaylist("p1", id)
        assertFalse("present file is not missing", fx.songDao.getSongById(id)!!.isMissing)

        assertTrue(gone.delete())
        fx.scan(music)

        assertTrue(fx.songDao.getSongById(id)!!.isMissing)
        assertEquals(listOf(id), fx.playlistDao.getSongIdsForPlaylist("p1"))
    }

    @Test fun untouchedRescanLeavesStarAndDismissalAlone() = runBlocking {
        val starred = write(arrivals, "starred.mp3", bytes(2_000, seed = 1))
        val dismissed = write(arrivals, "dismissed.mp3", bytes(2_000, seed = 2))
        fx.scan(arrivals, newArrivals = true)
        fx.songDao.dismissFromStarred(fx.requireSongAt(dismissed).id)

        fx.scan(arrivals, newArrivals = true)

        assertTrue(fx.requireSongAt(starred).isStarred)
        assertFalse(fx.requireSongAt(dismissed).isStarred)
    }

    /** Pinned current behaviour (open question): renaming a starred song in Music clears its star. */
    @Test fun pinnedRenameInMusicClearsTheStar() = runBlocking {
        val old = write(music, "song.mp3", bytes(2_000, seed = 1))
        fx.scan(music)
        val row = fx.requireSongAt(old)
        fx.songDao.upsertSong(row.copy(isStarred = true))

        assertTrue(old.renameTo(File(music, "renamed.mp3")))
        fx.scan(music)

        assertFalse("pinned: star cleared by a rename", fx.songDao.getSongById(row.id)!!.isStarred)
    }

    /** Pinned current behaviour (open question): renaming a dismissed song in New Arrivals re-stars it. */
    @Test fun pinnedRenameInNewArrivalsReStarsADismissedSong() = runBlocking {
        val old = write(arrivals, "song.mp3", bytes(2_000, seed = 1))
        fx.scan(arrivals, newArrivals = true)
        val id = fx.requireSongAt(old).id
        assertTrue("new arrivals start starred", fx.songDao.getSongById(id)!!.isStarred)
        fx.songDao.dismissFromStarred(id)

        assertTrue(old.renameTo(File(arrivals, "renamed.mp3")))
        fx.scan(arrivals, newArrivals = true)

        assertTrue("pinned: dismissal undone by a rename", fx.songDao.getSongById(id)!!.isStarred)
    }

    /** Pinned current behaviour (open question): the star follows the root a file is found in. */
    @Test fun pinnedMoveBetweenRootsKeepsTheRowAndTheStarFollowsTheRoot() = runBlocking {
        val inMusic = write(music, "song.mp3", bytes(2_000, seed = 1))
        fx.scan(music)
        val id = fx.requireSongAt(inMusic).id
        fx.addPlaylist("p1", id)

        val inArrivals = File(arrivals, "song.mp3")
        assertTrue(inMusic.renameTo(inArrivals))
        fx.scan(arrivals, newArrivals = true)
        assertEquals(uriOf(inArrivals), fx.songDao.getSongById(id)!!.filePath)
        assertTrue("pinned: starred once under New Arrivals", fx.songDao.getSongById(id)!!.isStarred)

        val back = File(music, "song.mp3")
        assertTrue(inArrivals.renameTo(back))
        fx.scan(music, otherRoots = listOf(arrivals))
        assertFalse("pinned: unstarred once back under Music", fx.songDao.getSongById(id)!!.isStarred)
        assertEquals(listOf(id), fx.playlistDao.getSongIdsForPlaylist("p1"))
        assertEquals(1, fx.songDao.getAllSongsOnce().size)
    }

    /**
     * Pinned current behaviour (open question): a FLAC tag edit rewrites bytes inside the hashed first
     * 64 KB, so the file becomes a new song. The playlist entry and star stay on the old row, which
     * is hidden as missing at a `stale://` path; the new row is in no playlist.
     */
    @Test fun pinnedFlacTagEditMakesANewSongAndPlaylistStaysOnHiddenCopy() = runBlocking {
        val before = flac(mapOf("TITLE" to "Take One", "ARTIST" to "Band"))
        val after = flac(mapOf("TITLE" to "Take Two", "ARTIST" to "Band"))
        assertEquals("an in-place tag edit keeps the size", before.size, after.size)
        val file = write(music, "track.flac", before)
        fx.scan(music)
        val old = fx.requireSongAt(file)
        fx.songDao.upsertSong(old.copy(isStarred = true))
        fx.addPlaylist("p1", old.id)

        file.writeBytes(after)
        val remaps = fx.recordRemaps(this)
        val result = fx.scan(music)
        coroutineContext.cancelChildren()

        val fresh = fx.requireSongAt(file)
        assertNotEquals("pinned: new id after a tag edit", old.id, fresh.id)
        assertEquals("Take Two", fresh.title)
        assertEquals(1, result.added)
        assertEquals(1, result.hashMismatchFlagged)
        val hidden = fx.songDao.getSongById(old.id)!!
        assertTrue(hidden.isMissing)
        assertEquals("stale://${old.id}", hidden.filePath)
        assertTrue("pinned: star stays on the hidden copy", hidden.isStarred)
        assertEquals(
            "pinned: playlist stays on the hidden copy",
            listOf(old.id),
            fx.playlistDao.getSongIdsForPlaylist("p1"),
        )
        assertTrue("new row is in no playlist", fx.playlistDao.getPlaylistIdsForSong(fresh.id).isEmpty())
        assertTrue("remap old to new: $remaps", remaps.any { it.oldId == old.id && it.newSong.id == fresh.id })
    }

    @Test fun rowHiddenByATagEditIsNotCountedAsMissingOnTheNextScan() = runBlocking {
        val file = write(music, "track.flac", flac(mapOf("TITLE" to "Take One", "ARTIST" to "Band")))
        fx.scan(music)
        val old = fx.requireSongAt(file)
        file.writeBytes(flac(mapOf("TITLE" to "Take Two", "ARTIST" to "Band")))
        assertEquals(1, fx.scan(music).hashMismatchFlagged)

        val next = fx.scan(music)

        assertEquals(0, next.missing)
        assertEquals(0, next.totalImpact)
        assertEquals("stale://${old.id}", fx.songDao.getSongById(old.id)!!.filePath)
    }
}
