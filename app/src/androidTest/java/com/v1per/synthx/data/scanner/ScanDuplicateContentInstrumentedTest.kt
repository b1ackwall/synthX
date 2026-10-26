package com.v1per.synthx.data.scanner

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Byte-identical files share one content id, so they are one song. The song always points at one
 * deterministic copy: a copy under the Music root beats a copy under another root, and within a
 * root the lowest path wins, whatever order the walk lists the files in.
 */
@RunWith(AndroidJUnit4::class)
class ScanDuplicateContentInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.device()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var fx: ScanTestFixture
    private lateinit var music: File
    private lateinit var arrivals: File
    private val content = bytes(2_000, seed = 1)

    @Before fun setUp() {
        fx = ScanTestFixture(context)
        music = fx.root("music")
        arrivals = fx.root("arrivals")
    }

    @After fun tearDown() = fx.close()

    private suspend fun scanMusic() = fx.scan(music, otherRoots = listOf(arrivals))

    private suspend fun scanArrivals() = fx.scan(arrivals, newArrivals = true, otherRoots = listOf(music))

    private suspend fun onlyRowPath(): String = fx.songDao.getAllSongsOnce().single().filePath

    private fun sameRootBecomesOneSongAtTheLowestPath(reverse: Boolean) = runBlocking {
        fx.reverseWalk = reverse
        val a = write(music, "a.mp3", content)
        val b = write(music, "b.mp3", content)

        val result = scanMusic()

        assertEquals(1, result.added)
        assertEquals(0, result.updated)
        assertEquals(0, result.missing)
        assertEquals(uriOf(a), onlyRowPath())
        assertNull("the losing copy has no song of its own", fx.songAt(b))
    }

    @Test fun twoIdenticalFilesBecomeOneSongAtTheLowestPathInForwardWalk() =
        sameRootBecomesOneSongAtTheLowestPath(reverse = false)

    @Test fun twoIdenticalFilesBecomeOneSongAtTheLowestPathInReverseWalk() =
        sameRootBecomesOneSongAtTheLowestPath(reverse = true)

    private fun sameRootRescanChangesNothing(reverse: Boolean) = runBlocking {
        fx.reverseWalk = reverse
        val a = write(music, "a.mp3", content)
        write(music, "b.mp3", content)
        scanMusic()
        val id = fx.requireSongAt(a).id

        val remaps = fx.recordRemaps(this)
        val second = scanMusic()
        val third = scanMusic()
        coroutineContext.cancelChildren()

        assertEquals(0, second.added)
        assertEquals(0, second.updated)
        assertEquals(0, second.missing)
        assertEquals(0, third.updated)
        assertEquals("no remap for the id", emptyList<Any>(), remaps.filter { it.oldId == id })
        assertEquals(uriOf(a), fx.songDao.getSongById(id)!!.filePath)
    }

    @Test fun sameRootRescanChangesNothingInForwardWalk() = sameRootRescanChangesNothing(reverse = false)

    @Test fun sameRootRescanChangesNothingInReverseWalk() = sameRootRescanChangesNothing(reverse = true)

    @Test fun musicCopyWinsWhenMusicIsScannedLast() = runBlocking {
        val inMusic = write(music, "song.mp3", content)
        write(arrivals, "song.mp3", content)
        scanArrivals()
        val atArrivals = fx.songDao.getAllSongsOnce().single()

        val remaps = fx.recordRemaps(this)
        val healed = scanMusic()
        val again = scanMusic()
        val arrivalsAgain = scanArrivals()
        coroutineContext.cancelChildren()

        assertEquals(1, healed.updated)
        assertEquals(0, again.updated)
        assertEquals(0, arrivalsAgain.updated)
        assertEquals(uriOf(inMusic), onlyRowPath())
        assertEquals("one remap, from the heal", 1, remaps.count { it.oldId == atArrivals.id })
    }

    @Test fun musicCopyWinsWhenNewArrivalsIsScannedLast() = runBlocking {
        val inMusic = write(music, "song.mp3", content)
        write(arrivals, "song.mp3", content)
        scanMusic()
        val id = fx.requireSongAt(inMusic).id

        val remaps = fx.recordRemaps(this)
        val first = scanArrivals()
        val second = scanArrivals()
        coroutineContext.cancelChildren()

        assertEquals(0, first.added)
        assertEquals(0, first.updated)
        assertEquals(0, second.updated)
        assertEquals(emptyList<Any>(), remaps.filter { it.oldId == id })
        assertEquals(uriOf(inMusic), onlyRowPath())
    }

    @Test fun aStarSetByTheUserSurvivesScanningTheOtherRoot() = runBlocking {
        val inMusic = write(music, "song.mp3", content)
        write(arrivals, "song.mp3", content)
        scanMusic()
        val row = fx.requireSongAt(inMusic)
        fx.songDao.upsertSong(row.copy(isStarred = true))

        scanArrivals()
        scanArrivals()

        assertTrue("star kept", fx.songDao.getSongById(row.id)!!.isStarred)
        assertEquals(uriOf(inMusic), onlyRowPath())
    }

    @Test fun aDismissedNewArrivalStaysDismissedWhenTheMusicCopyTakesTheRow() = runBlocking {
        val inMusic = write(music, "song.mp3", content)
        write(arrivals, "song.mp3", content)
        scanArrivals()
        val id = fx.songDao.getAllSongsOnce().single().id
        assertTrue("new arrivals start starred", fx.songDao.getSongById(id)!!.isStarred)
        fx.songDao.dismissFromStarred(id)

        scanMusic()

        assertEquals(uriOf(inMusic), onlyRowPath())
        assertFalse("dismissal kept", fx.songDao.getSongById(id)!!.isStarred)
    }

    @Test fun aStarSetByTheUserSurvivesTheRowMovingToTheLowestPathCopy() = runBlocking {
        val b = write(music, "b.mp3", content)
        scanMusic()
        val row = fx.requireSongAt(b)
        fx.songDao.upsertSong(row.copy(isStarred = true))
        val a = write(music, "a.mp3", content)

        scanMusic()

        assertEquals(uriOf(a), onlyRowPath())
        assertTrue("star kept", fx.songDao.getSongById(row.id)!!.isStarred)
    }

    @Test fun aNewArrivalStaysStarredWhenTheMusicCopyTakesTheRow() = runBlocking {
        val inMusic = write(music, "song.mp3", content)
        write(arrivals, "song.mp3", content)
        scanArrivals()
        val id = fx.songDao.getAllSongsOnce().single().id

        scanMusic()

        assertEquals(uriOf(inMusic), onlyRowPath())
        assertTrue("star kept", fx.songDao.getSongById(id)!!.isStarred)
    }

    @Test fun whenTheMusicCopyIsDeletedTheArrivalsCopyTakesTheRow() = runBlocking {
        val inMusic = write(music, "song.mp3", content)
        val inArrivals = write(arrivals, "song.mp3", content)
        scanMusic()
        val id = fx.requireSongAt(inMusic).id
        fx.addPlaylist("p1", id)
        assertTrue(inMusic.delete())

        scanMusic()
        assertEquals(
            "flagged missing while only the arrivals copy remains",
            true,
            fx.songDao.getSongById(id)!!.isMissing,
        )
        scanArrivals()
        val settled = scanArrivals()

        val row = fx.songDao.getAllSongsOnce().single()
        assertEquals(id, row.id)
        assertEquals(uriOf(inArrivals), row.filePath)
        assertFalse(row.isMissing)
        assertEquals(0, settled.updated)
        assertEquals(listOf(id), fx.playlistDao.getSongIdsForPlaylist("p1"))
    }

    @Test fun whenTheLowestPathCopyIsDeletedTheNextCopyTakesTheRow() = runBlocking {
        val a = write(music, "a.mp3", content)
        val b = write(music, "b.mp3", content)
        scanMusic()
        val id = fx.requireSongAt(a).id
        fx.addPlaylist("p1", id)
        assertTrue(a.delete())

        val result = scanMusic()

        val row = fx.songDao.getAllSongsOnce().single()
        assertEquals(id, row.id)
        assertEquals(uriOf(b), row.filePath)
        assertFalse(row.isMissing)
        assertEquals(0, result.missing)
        assertEquals(listOf(id), fx.playlistDao.getSongIdsForPlaylist("p1"))
    }

    @Test fun aRowAlreadyAtTheLosingCopyHealsOnceToTheWinner() = runBlocking {
        val b = write(music, "b.mp3", content)
        scanMusic()
        val id = fx.requireSongAt(b).id
        val a = write(music, "a.mp3", content)

        val remaps = fx.recordRemaps(this)
        val heal = scanMusic()
        val after = scanMusic()
        coroutineContext.cancelChildren()

        assertEquals(1, heal.updated)
        assertEquals(0, after.updated)
        assertEquals(uriOf(a), fx.songDao.getSongById(id)!!.filePath)
        assertEquals("one remap, from the heal", 1, remaps.count { it.oldId == id })
        assertEquals(1, fx.songDao.getAllSongsOnce().size)
    }
}
