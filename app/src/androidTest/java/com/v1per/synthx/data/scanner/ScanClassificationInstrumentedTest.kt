package com.v1per.synthx.data.scanner

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.data.SongDeletion
import com.v1per.synthx.data.SongDeletionEvents
import com.v1per.synthx.data.scanner.ScanTestFixture.Companion.bytes
import com.v1per.synthx.data.scanner.ScanTestFixture.Companion.uriOf
import com.v1per.synthx.data.scanner.ScanTestFixture.Companion.write
import com.v1per.synthx.ui.testing.TestTimeouts
import java.io.File
import java.util.Collections
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * How a real scan sorts each file: new, unchanged, moved or renamed, changed content, path taken
 * by another row, gone (missing), back again (found), unreadable. Plain byte files are enough:
 * song identity comes from the bytes, not from tags.
 */
@RunWith(AndroidJUnit4::class)
class ScanClassificationInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER) val testTimeout = TestTimeouts.device()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var fx: ScanTestFixture
    private lateinit var music: File

    @Before fun setUp() {
        fx = ScanTestFixture(context)
        music = fx.root("music")
    }

    @After fun tearDown() = fx.close()

    @Test fun newFilesAreAddedWithFileNameTitleAndNotMissing() = runBlocking {
        val a = write(music, "First Song.mp3", bytes(2_000, seed = 1))
        write(music, "sub/Second.flac", bytes(3_000, seed = 2))

        val result = fx.scan(music)

        assertEquals(2, result.added)
        assertEquals(0, result.updated)
        assertEquals(0, result.missing)
        assertTrue(result.missingReconciled)
        val song = fx.requireSongAt(a)
        assertEquals("First Song", song.title)
        assertFalse(song.isMissing)
        assertFalse("Music root songs are not starred", song.isStarred)
        assertEquals(2, fx.songDao.getAllSongsOnce().size)
    }

    @Test fun filesWithoutAnAudioExtensionAreIgnored() = runBlocking {
        write(music, "notes.txt", bytes(500, seed = 1))
        write(music, "cover.jpg", bytes(500, seed = 2))

        val result = fx.scan(music)

        assertEquals(0, result.added)
        assertTrue(fx.songDao.getAllSongsOnce().isEmpty())
    }

    @Test fun rescanOfUntouchedFilesCountsThemUnchanged() = runBlocking {
        val a = write(music, "a.mp3", bytes(2_000, seed = 1))
        write(music, "b.mp3", bytes(2_000, seed = 2))
        fx.scan(music)
        val id = fx.requireSongAt(a).id

        val second = fx.scan(music)

        assertEquals(0, second.added)
        assertEquals(0, second.updated)
        assertEquals(2, second.unchanged)
        assertEquals(id, fx.requireSongAt(a).id)
    }

    @Test fun renamedFileKeepsItsOneRowAndTakesTheNewPath() = runBlocking {
        val old = write(music, "old name.mp3", bytes(2_000, seed = 1))
        fx.scan(music)
        val id = fx.requireSongAt(old).id

        val renamed = File(music, "new name.mp3")
        assertTrue(old.renameTo(renamed))
        val result = fx.scan(music)

        assertEquals(0, result.added)
        assertEquals(1, result.updated)
        assertEquals(0, result.missing)
        assertEquals(1, fx.songDao.getAllSongsOnce().size)
        assertEquals(id, fx.requireSongAt(renamed).id)
        assertNull(fx.songAt(old))
        assertFalse(fx.songDao.getSongById(id)!!.isMissing)
    }

    @Test fun fileMovedIntoASubfolderKeepsItsRow() = runBlocking {
        val old = write(music, "song.mp3", bytes(2_000, seed = 1))
        fx.scan(music)
        val id = fx.requireSongAt(old).id

        val moved = File(music, "Artist/Album/song.mp3").also { it.parentFile!!.mkdirs() }
        assertTrue(old.renameTo(moved))
        val result = fx.scan(music)

        assertEquals(1, result.updated)
        assertEquals(listOf(id), fx.songDao.getAllSongsOnce().map { it.id })
        assertEquals(uriOf(moved), fx.songDao.getSongById(id)!!.filePath)
    }

    @Test fun changedContentAtTheSamePathAddsANewRowAndHidesTheOldOneAsMissing() = runBlocking {
        val file = write(music, "song.mp3", bytes(2_000, seed = 1))
        fx.scan(music)
        val oldId = fx.requireSongAt(file).id

        file.writeBytes(bytes(2_000, seed = 99))
        val result = fx.scan(music)

        assertEquals(1, result.added)
        assertEquals(1, result.hashMismatchFlagged)
        val newRow = fx.requireSongAt(file)
        assertTrue("new content has a new id", newRow.id != oldId)
        val oldRow = requireNotNull(fx.songDao.getSongById(oldId)) { "old row kept" }
        assertTrue(oldRow.isMissing)
        assertNotNull(oldRow.missingSince)
        assertEquals("stale://$oldId", oldRow.filePath)
    }

    @Test fun goneFileIsMarkedMissingAndPresentFileIsNot() = runBlocking {
        val gone = write(music, "gone.mp3", bytes(2_000, seed = 1))
        val kept = write(music, "kept.mp3", bytes(2_000, seed = 2))
        fx.scan(music)
        val goneId = fx.requireSongAt(gone).id

        assertTrue(gone.delete())
        val result = fx.scan(music)

        assertEquals(1, result.missing)
        val row = fx.songDao.getSongById(goneId)!!
        assertTrue(row.isMissing)
        assertNotNull(row.missingSince)
        assertFalse(fx.requireSongAt(kept).isMissing)
    }

    @Test fun fileThatComesBackAtItsPathIsFoundAgain() = runBlocking {
        val content = bytes(2_000, seed = 1)
        val file = write(music, "song.mp3", content)
        fx.scan(music)
        val id = fx.requireSongAt(file).id
        assertTrue(file.delete())
        fx.scan(music)
        assertTrue(fx.songDao.getSongById(id)!!.isMissing)

        file.writeBytes(content)
        val result = fx.scan(music)

        assertEquals(1, result.updated)
        assertEquals(0, result.added)
        val row = fx.songDao.getSongById(id)!!
        assertFalse(row.isMissing)
        assertNull(row.missingSince)
    }

    @Test fun songUnderAnotherConfiguredRootIsNotMarkedMissing() = runBlocking {
        val other = fx.root("other")
        val elsewhere = write(other, "elsewhere.mp3", bytes(2_000, seed = 1))
        fx.scan(other)
        val id = fx.requireSongAt(elsewhere).id

        val result = fx.scan(music, otherRoots = listOf(other))

        assertEquals(0, result.missing)
        assertFalse(fx.songDao.getSongById(id)!!.isMissing)
    }

    /**
     * Song X's bytes are copied over b.mp3 (song Y's file) and a.mp3 is removed. X moves to
     * b.mp3, so Y's row must be removed first (paths are unique) and a deletion notice sent.
     */
    @Test fun pathTakenByAnotherRowEvictsTheOccupant() = runBlocking {
        val xBytes = bytes(2_000, seed = 1)
        val a = write(music, "a.mp3", xBytes)
        val b = write(music, "b.mp3", bytes(2_000, seed = 2))
        fx.scan(music)
        val x = fx.requireSongAt(a).id
        val y = fx.requireSongAt(b).id

        b.writeBytes(xBytes)
        assertTrue(a.delete())
        val deletions = Collections.synchronizedList(mutableListOf<SongDeletion>())
        launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            SongDeletionEvents.events.collect { deletions += it }
        }
        val result = fx.scan(music)
        coroutineContext.cancelChildren()

        assertEquals(1, result.deleted)
        assertNull("occupant row removed", fx.songDao.getSongById(y))
        assertEquals(x, fx.requireSongAt(b).id)
        assertTrue("deletion notice for $y: $deletions", deletions.any { it.songId == y })
    }

    @Test fun emptyFileIsCountedAsAnErrorAndGetsNoRow() = runBlocking {
        write(music, "empty.mp3", ByteArray(0))
        write(music, "fine.mp3", bytes(2_000, seed = 1))

        val result = fx.scan(music)

        assertEquals(1, result.errors)
        assertEquals(1, result.added)
        assertEquals(1, fx.songDao.getAllSongsOnce().size)
    }

    @Test fun unreachableRootChangesNothing() = runBlocking {
        val file = write(music, "song.mp3", bytes(2_000, seed = 1))
        fx.scan(music)
        val id = fx.requireSongAt(file).id

        val unknown = File(context.cacheDir, "never-registered-${System.nanoTime()}")
        val result = fx.scanner().scan(android.net.Uri.parse(uriOf(unknown)))

        assertEquals(
            setOf(ScanDegradation(KnownAudioRoot.Music, ScanDegradationReason.RootUnreachable)),
            result.degradations,
        )
        assertFalse(result.missingReconciled)
        assertFalse(fx.songDao.getSongById(id)!!.isMissing)
    }
}
