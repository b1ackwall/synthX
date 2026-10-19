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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pinned current behaviour (suspected bug, reproduced): two byte-identical files share one
 * content id, so they become one song whose path jumps to whichever copy the walk reaches last,
 * on every scan. The walk here is sorted by name, so `b.mp3` is always last.
 */
@RunWith(AndroidJUnit4::class)
class ScanDuplicateContentInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER) val testTimeout = TestTimeouts.device()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var fx: ScanTestFixture
    private lateinit var music: File

    @Before fun setUp() {
        fx = ScanTestFixture(context)
        music = fx.root("music")
    }

    @After fun tearDown() = fx.close()

    @Test fun pinnedTwoIdenticalFilesBecomeOneSongAtTheLastCopy() = runBlocking {
        val content = bytes(2_000, seed = 1)
        val a = write(music, "a.mp3", content)
        val b = write(music, "b.mp3", content)

        val result = fx.scan(music)

        assertEquals(1, result.added)
        assertEquals("pinned: the second copy moves the row", 1, result.updated)
        assertEquals(1, fx.songDao.getAllSongsOnce().size)
        assertNull("pinned: the first copy has no song of its own", fx.songAt(a))
        assertEquals(uriOf(b), fx.songDao.getAllSongsOnce().single().filePath)
    }

    @Test fun pinnedTheSongJumpsBetweenCopiesOnEveryRescan() = runBlocking {
        val content = bytes(2_000, seed = 1)
        val a = write(music, "a.mp3", content)
        val b = write(music, "b.mp3", content)
        fx.scan(music)
        val id = fx.requireSongAt(b).id

        val remaps = fx.recordRemaps(this)
        val second = fx.scan(music)
        coroutineContext.cancelChildren()

        assertEquals("pinned: both copies count as moved", 2, second.updated)
        assertEquals(0, second.unchanged)
        assertEquals(0, second.missing)
        assertEquals(
            "pinned: one path remap per copy",
            listOf(uriOf(a), uriOf(b)),
            remaps.filter { it.oldId == id }.map { it.newSong.filePath },
        )
        assertEquals(uriOf(b), fx.songDao.getSongById(id)!!.filePath)
    }
}
