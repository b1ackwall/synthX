package com.v1per.synthx.data.scanner

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.data.backup.BackupManager
import com.v1per.synthx.data.backup.PlaylistExportManager
import com.v1per.synthx.data.db.BaselineQueueItemEntity
import com.v1per.synthx.data.db.PlaybackCursorEntity
import com.v1per.synthx.data.db.QueueItemEntity
import com.v1per.synthx.data.repository.QueueExtraKind
import com.v1per.synthx.data.repository.QueueExtraTag
import com.v1per.synthx.data.repository.QueueSlotRecord
import com.v1per.synthx.data.repository.RepositoryTestFixture
import com.v1per.synthx.data.repository.SongRepositoryImpl
import com.v1per.synthx.data.scanner.ScanTestFixture.Companion.bytes
import com.v1per.synthx.data.scanner.ScanTestFixture.Companion.write
import com.v1per.synthx.playback.QueuePersistence
import com.v1per.synthx.ui.testing.TestTimeouts
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A rescan that gives a changed file a new id, with no playback engine running: the scan moves every
 * saved queue row of the old id, live and baseline, to the new id in its own transaction. Slot ids,
 * positions, extra tags, the shuffled order and the cursor stay as they are, and the next restore
 * keeps the slot, resuming on the new song at the saved position when it is the current one. The old
 * row stays missing and hidden at its `stale://` path.
 */
@RunWith(AndroidJUnit4::class)
class ScanRepointsSavedQueueInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.device()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var fx: ScanTestFixture
    private lateinit var music: File
    private lateinit var arrivals: File
    private lateinit var repositoryContext: RepositoryTestFixture.IsolatedContext
    private lateinit var repository: SongRepositoryImpl
    private lateinit var persistence: QueuePersistence

    @Before fun setUp() {
        fx = ScanTestFixture(context)
        music = fx.root("music")
        arrivals = fx.root("arrivals")
        repositoryContext = RepositoryTestFixture.IsolatedContext(context)
        val exports = PlaylistExportManager(
            repositoryContext,
            fx.database,
            fx.database.playlistDao(),
            fx.database.songDao(),
            fx.database.artistDao(),
            fx.database.albumDao(),
        )
        repository = SongRepositoryImpl(
            repositoryContext, fx.database, fx.database.songDao(), fx.database.artistDao(), fx.database.albumDao(),
            fx.database.playlistDao(), fx.database.queueDao(), fx.database.pendingCleanupDao(),
            fx.scanner(), BackupManager(repositoryContext), exports,
            destructiveFileCheck = { _, _ -> DestructiveFilePreflightResult.Ambiguous },
            replacementFileCheck = { _, _ -> ReplacementFilePreflightResult.Ambiguous },
        )
        persistence = QueuePersistence(repository)
    }

    @After fun tearDown() {
        runBlocking {
            persistence.quiesceForRestore()
            repository.repositoryScope.coroutineContext[Job]!!.cancelAndJoin()
        }
        fx.close()
        repositoryContext.cleanUp()
    }

    private val queueDao get() = fx.database.queueDao()

    private val playNextTag = QueueExtraTag(QueueExtraKind.PlayNext, 7L)

    /**
     * Saves a shuffled queue the way the engine's unified write does: live [live] (slot id to song id)
     * with a Play Next extra on [extraSlot], baseline [baseline], the cursor on [current] at 42 s.
     */
    private suspend fun saveQueue(
        live: List<Pair<String, String>>,
        baseline: List<Pair<String, String>>,
        current: String,
        extraSlot: String,
    ) {
        queueDao.replaceQueueBaselineAndCursor(
            activeSlots = live.map { (slot, song) -> QueueSlotRecord(slot, song) },
            baselineSlots = baseline.map { (slot, song) -> QueueSlotRecord(slot, song) },
            currentSlotId = current,
            positionMs = 42_000L,
            shuffleEnabled = true,
            repeatMode = 2,
            extras = mapOf(extraSlot to playNextTag),
            shuffleReordered = true,
            queueCustomized = true,
        )
    }

    private data class Saved(
        val live: List<QueueItemEntity>,
        val baseline: List<BaselineQueueItemEntity>,
        val cursor: PlaybackCursorEntity?,
    )

    private suspend fun saved() =
        Saved(queueDao.getQueueItems(), queueDao.getBaselineQueueItems(), queueDao.getCursor())

    /** [before] with every row of [oldId] on [newId] and nothing else changed. */
    private fun Saved.repointed(oldId: String, newId: String) = Saved(
        live.map { if (it.songId == oldId) it.copy(songId = newId) else it },
        baseline.map { if (it.songId == oldId) it.copy(songId = newId) else it },
        cursor,
    )

    private suspend fun assertHiddenOldRow(oldId: String) {
        val hidden = fx.songDao.getSongById(oldId)!!
        assertTrue("the old row stays missing", hidden.isMissing)
        assertEquals("the old row stays hidden", "stale://$oldId", hidden.filePath)
    }

    @Test fun theCurrentSongsNewIdTakesItsSavedRowsAndResumesWhereItWas() = runBlocking {
        val a = write(music, "a.mp3", bytes(2_000, seed = 1))
        val b = write(music, "b.mp3", bytes(2_000, seed = 2))
        val c = write(music, "c.mp3", bytes(2_000, seed = 3))
        fx.scan(music)
        val (idA, idB, idC) = listOf(a, b, c).map { fx.requireSongAt(it).id }
        saveQueue(
            live = listOf("s-c" to idC, "s-a" to idA, "s-b" to idB),
            baseline = listOf("s-a" to idA, "s-b" to idB, "s-c" to idC),
            current = "s-a",
            extraSlot = "s-a",
        )
        val before = saved()

        a.writeBytes(bytes(2_000, seed = 11))
        assertEquals(1, fx.scan(music).hashMismatchFlagged)

        val newA = fx.requireSongAt(a).id
        assertNotEquals("a changed file gets a new id", idA, newA)
        assertEquals("both tables follow the new id, nothing else moves", before.repointed(idA, newA), saved())
        assertHiddenOldRow(idA)

        val restored = checkNotNull(persistence.loadQueue()) { "the saved queue must be restored" }
        assertEquals(listOf("s-c", "s-a", "s-b"), restored.slots.map { it.slotId })
        assertEquals("the shuffled order is kept", listOf(idC, newA, idB), restored.slots.map { it.song.id })
        assertEquals(0, restored.droppedCount)
        assertEquals("s-a", restored.slots[restored.startIndex].slotId)
        assertEquals("the new song resumes at the saved position", 42_000L, restored.positionMs)
        assertEquals(mapOf("s-a" to playNextTag), restored.extras)
        assertTrue(restored.shuffleEnabled && restored.shuffleReordered && restored.queueCustomized)
        assertEquals(listOf(newA, idB, idC), persistence.loadBaselineQueue().map { it.song.id })
    }

    @Test fun everySlotOfTheOldIdMovesAndAnotherCurrentSlotStaysPut() = runBlocking {
        val a = write(music, "a.mp3", bytes(2_000, seed = 1))
        val b = write(music, "b.mp3", bytes(2_000, seed = 2))
        fx.scan(music)
        val (idA, idB) = listOf(a, b).map { fx.requireSongAt(it).id }
        saveQueue(
            live = listOf("s-1" to idA, "s-2" to idB, "s-3" to idA),
            baseline = listOf("s-1" to idA, "s-2" to idB, "s-3" to idA),
            current = "s-2",
            extraSlot = "s-3",
        )
        val before = saved()

        a.writeBytes(bytes(2_000, seed = 12))
        fx.scan(music)

        val newA = fx.requireSongAt(a).id
        assertEquals(before.repointed(idA, newA), saved())
        val restored = checkNotNull(persistence.loadQueue())
        assertEquals(listOf(newA, idB, newA), restored.slots.map { it.song.id })
        assertEquals("s-2" to 42_000L, restored.slots[restored.startIndex].slotId to restored.positionMs)
    }

    @Test fun aNewArrivalsRescanRepointsTheSavedQueueToo() = runBlocking {
        val a = write(arrivals, "a.mp3", bytes(2_000, seed = 1))
        val b = write(arrivals, "b.mp3", bytes(2_000, seed = 2))
        fx.scan(arrivals, newArrivals = true)
        val (idA, idB) = listOf(a, b).map { fx.requireSongAt(it).id }
        saveQueue(
            live = listOf("s-b" to idB, "s-a" to idA),
            baseline = listOf("s-a" to idA, "s-b" to idB),
            current = "s-a",
            extraSlot = "s-b",
        )
        val before = saved()

        a.writeBytes(bytes(2_000, seed = 13))
        assertEquals(1, fx.scan(arrivals, newArrivals = true).hashMismatchFlagged)

        val fresh = fx.requireSongAt(a)
        assertTrue("a New Arrivals song comes in starred", fresh.isStarred)
        assertEquals(before.repointed(idA, fresh.id), saved())
        assertHiddenOldRow(idA)
        val restored = checkNotNull(persistence.loadQueue())
        assertEquals(listOf(idB, fresh.id), restored.slots.map { it.song.id })
        assertEquals("s-a" to 42_000L, restored.slots[restored.startIndex].slotId to restored.positionMs)
    }

    @Test fun aRescanThatChangesNothingLeavesTheSavedQueueAlone() = runBlocking {
        val a = write(music, "a.mp3", bytes(2_000, seed = 1))
        fx.scan(music)
        val idA = fx.requireSongAt(a).id
        saveQueue(listOf("s-a" to idA), listOf("s-a" to idA), current = "s-a", extraSlot = "s-a")
        val before = saved()

        fx.scan(music)

        assertEquals(before, saved())
    }
}
