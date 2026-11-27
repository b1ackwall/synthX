package com.v1per.synthx.data.backup

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.core.content.FileProvider
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.data.db.PendingCleanupEntity
import com.v1per.synthx.data.db.PendingCleanupPhase
import com.v1per.synthx.data.db.PendingCleanupType
import com.v1per.synthx.data.db.SongEntity
import com.v1per.synthx.data.db.SynthXDatabase
import com.v1per.synthx.preferences.DestructiveActionTracker
import com.v1per.synthx.preferences.PendingCleanupReconciliationTracker
import com.v1per.synthx.ui.testing.TestTimeouts
import java.io.File
import java.util.UUID
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
 * Proves the durable-record + startup-reconciliation mechanism itself — a destructive
 * operation interrupted between its DB commit and physical completion (delete/replace) or between
 * source deletion and DB commit (graduate) is fully resolved by [PendingCleanupReconciliation] on
 * the next pass, without ever having run the original operation's own cleanup code.
 *
 * Each "interrupted" state is constructed directly (row deleted / pending record inserted) rather
 * than by cancelling a real coroutine mid-flight — this is deliberately what a fresh process would
 * actually observe after a kill: the transaction that made the record durable already committed,
 * and nothing downstream of it ever ran. See [RepositoryDeletionInstrumentedTest]'s "test://"
 * convention for unresolvable audio paths, reused here for the same reason (no real SAF document
 * needed to prove the reconciliation logic itself).
 */
@RunWith(AndroidJUnit4::class)
class PendingCleanupReconciliationInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.device()

    private lateinit var context: ReconciliationTestContext
    private lateinit var database: SynthXDatabase
    private val publishedFiles = mutableListOf<File>()

    private var exportRequests = 0

    @Before
    fun setUp() {
        PendingCleanupReconciliationTracker.resetForTest()
        context = ReconciliationTestContext(ApplicationProvider.getApplicationContext())
        DestructiveActionTracker.resetForTest(context)
        database = Room.inMemoryDatabaseBuilder(context, SynthXDatabase::class.java).build()
        File(context.filesDir, "art").mkdirs()
    }

    @After
    fun tearDown() {
        publishedFiles.forEach(File::delete)
        database.close()
        // Before cleanUp(): resetting afterwards would recreate the isolated preference file.
        DestructiveActionTracker.resetForTest(context)
        context.cleanUp()
        PendingCleanupReconciliationTracker.resetForTest()
    }

    @Test
    fun deleteInterruptedAfterCommitIsReconciledOnNextLaunch() = runBlocking {
        val audio = reachableDocument("song.audio")
        seedSong("song", filePath = audio)
        File(context.filesDir, "art/art_song.jpg").writeText("test art")

        // The exact durable state a kill right after deleteSong's transaction commits, but
        // before finishSongDeletion ever ran, leaves behind — see
        // SongRepositoryImpl.deleteSong's pendingCleanupDao.insert call.
        database.songDao().deleteSongById("song")
        insertDeleteRecord("song", audio, PendingCleanupPhase.Committed)

        // Precondition: row is gone, cleanup never ran, record survives.
        assertNull(database.songDao().getSongById("song"))
        assertTrue(File(context.filesDir, "art/art_song.jpg").exists())
        assertEquals(1, database.pendingCleanupDao().getAll().size)

        reconcile()

        assertFalse(File(context.filesDir, "art/art_song.jpg").exists())
        assertTrue(database.pendingCleanupDao().getAll().isEmpty())
        assertEquals(1, PendingCleanupReconciliationTracker.lastCount.value)
        // The interrupted delete's own after-commit work, finished here.
        assertEquals(1, DestructiveActionTracker.count.value)
        assertEquals(1, exportRequests)
    }

    @Test
    fun interruptedDeleteWhoseFileCannotBeConfirmedGoneKeepsItsRecordForTheNextLaunch() = runBlocking {
        // test:// opens no provider, so the delete is unconfirmed, as a refusing provider's would be.
        insertDeleteRecord("song", "test://song", PendingCleanupPhase.Committed)
        File(context.filesDir, "art/art_song.jpg").writeText("test art")

        reconcile()

        val kept = database.pendingCleanupDao().getAll().single()
        assertEquals(PendingCleanupPhase.AudioFileRemains.name, kept.phase)
        assertEquals("test://song", kept.filePath)
        assertEquals(listOf("test://song"), database.pendingCleanupDao().getPendingDeletePaths())
        // Everything but the file is finished, once.
        assertFalse(File(context.filesDir, "art/art_song.jpg").exists())
        assertEquals(1, DestructiveActionTracker.count.value)
        assertEquals(1, exportRequests)
        // Surfaced through the existing Issues count even though it is not resolved.
        assertEquals(1, PendingCleanupReconciliationTracker.lastCount.value)

        reconcile()

        assertEquals(PendingCleanupPhase.AudioFileRemains.name, database.pendingCleanupDao().getAll().single().phase)
        assertEquals("a retry must not count the destructive action again", 1, DestructiveActionTracker.count.value)
        assertEquals("a retry needs no second export", 1, exportRequests)
        assertEquals(1, PendingCleanupReconciliationTracker.lastCount.value)
    }

    @Test
    fun aKeptRecordIsClearedOnceItsFileIsGone() = runBlocking {
        val audio = reachableDocument("remains.audio")
        insertDeleteRecord("song", audio, PendingCleanupPhase.AudioFileRemains)

        reconcile()

        assertTrue(database.pendingCleanupDao().getAll().isEmpty())
        assertEquals("the replace already counted itself", 0, DestructiveActionTracker.count.value)
        assertEquals(0, exportRequests)
        assertEquals(1, PendingCleanupReconciliationTracker.lastCount.value)
    }

    @Test
    fun aStaleSentinelPathHasNoFileToWaitFor() = runBlocking {
        insertDeleteRecord("song", "stale://song", PendingCleanupPhase.AudioFileRemains)

        reconcile()

        assertTrue(database.pendingCleanupDao().getAll().isEmpty())
    }

    @Test
    fun graduationInterruptedBeforeCommitReconcilesRowToPublishedPathWhenReachable() = runBlocking {
        // publishedDocumentExists can't resolve a real SAF document in this harness (see the
        // class kdoc) — this proves the row-gone/already-reconciled/unreachable branches, and the
        // no-op-when-already-pointing-at-publishedPath branch, which together are the branches
        // reachable without a real DocumentsProvider.
        seedSong("song", filePath = "test://already-published")
        val operationId = UUID.randomUUID().toString()
        database.pendingCleanupDao().insert(
            PendingCleanupEntity(
                operationId = operationId,
                songId = "song",
                operationType = PendingCleanupType.GRADUATE.name,
                filePath = null,
                publishedPath = "test://already-published",
                phase = PendingCleanupPhase.SourceDeleted.name,
                createdAt = System.currentTimeMillis(),
            ),
        )

        reconcile()

        val song = requireNotNull(database.songDao().getSongById("song"))
        assertEquals("test://already-published", song.filePath)
        assertFalse("Row already matched publishedPath — must not be flagged missing", song.isMissing)
        assertTrue(database.pendingCleanupDao().getAll().isEmpty())

        // A published document that really exists: a FileProvider URI to a real file.
        val publishedUri = reachablePublishedDocument()
        seedSong("moved", filePath = "test://deleted-source")
        database.pendingCleanupDao().insert(
            PendingCleanupEntity(
                operationId = UUID.randomUUID().toString(),
                songId = "moved",
                operationType = PendingCleanupType.GRADUATE.name,
                filePath = null,
                publishedPath = publishedUri,
                phase = PendingCleanupPhase.SourceDeleted.name,
                createdAt = System.currentTimeMillis(),
            ),
        )

        reconcile()

        val moved = requireNotNull(database.songDao().getSongById("moved"))
        assertEquals("row must point at the reachable published document", publishedUri, moved.filePath)
        assertFalse(moved.isMissing)
        assertTrue(database.pendingCleanupDao().getAll().isEmpty())
    }

    @Test
    fun graduationInterruptedWithUnreachablePublishedPathFlagsRowMissing() = runBlocking {
        // Source is gone (per PendingCleanupPhase.SourceDeleted) and the row still points at it —
        // the published destination is also unresolvable in this harness, which is the same
        // "can no longer honestly claim either path" case a genuinely-also-gone destination file
        // would hit in production. See PendingCleanupReconciliation.reconcileGraduate's kdoc.
        seedSong("song", filePath = "test://deleted-source")
        val operationId = UUID.randomUUID().toString()
        database.pendingCleanupDao().insert(
            PendingCleanupEntity(
                operationId = operationId,
                songId = "song",
                operationType = PendingCleanupType.GRADUATE.name,
                filePath = null,
                publishedPath = "test://unreachable-published",
                phase = PendingCleanupPhase.SourceDeleted.name,
                createdAt = System.currentTimeMillis(),
            ),
        )

        reconcile()

        val song = requireNotNull(database.songDao().getSongById("song"))
        assertEquals("test://deleted-source", song.filePath)
        assertTrue("Row must be flagged missing rather than left pointing at a deleted file", song.isMissing)
        assertNotNull(song.missingSince)
        assertTrue(database.pendingCleanupDao().getAll().isEmpty())
    }

    @Test
    fun graduationRecordForAnAlreadyGoneRowIsJustCleared() = runBlocking {
        // The row vanished some other way (e.g. GraduationCommit.RowGone) before reconciliation
        // ever ran — nothing left to re-point, the record should simply be consumed.
        val operationId = UUID.randomUUID().toString()
        database.pendingCleanupDao().insert(
            PendingCleanupEntity(
                operationId = operationId,
                songId = "gone-song",
                operationType = PendingCleanupType.GRADUATE.name,
                filePath = null,
                publishedPath = "test://published",
                phase = PendingCleanupPhase.SourceDeleted.name,
                createdAt = System.currentTimeMillis(),
            ),
        )

        reconcile()

        assertNull(database.songDao().getSongById("gone-song"))
        assertTrue(database.pendingCleanupDao().getAll().isEmpty())
    }

    @Test
    fun noLeftoverRecordsIsANoOpThatDoesNotTouchTheTracker() = runBlocking {
        reconcile()

        assertEquals(0, PendingCleanupReconciliationTracker.lastCount.value)
        assertEquals(
            "an empty pass must not record a reconciliation",
            0L,
            PendingCleanupReconciliationTracker.lastTimestamp.value,
        )
    }

    private fun reachablePublishedDocument(): String = reachableDocument("published")

    /**
     * A content URI the app's FileProvider serves for a real file. Its existence query returns a
     * row and its delete call succeeds, so deleteSongAudioFile reports Deleted. FileProvider does
     * not implement document deletion, so the file itself stays; tearDown removes it.
     */
    private fun reachableDocument(suffix: String): String {
        val appContext = context.baseContext
        val file =
            File(File(appContext.filesDir, "restore_incidents").apply { mkdirs() }, "${UUID.randomUUID()}.$suffix")
        file.writeText(suffix)
        publishedFiles += file
        return FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", file).toString()
    }

    private suspend fun reconcile() {
        PendingCleanupReconciliation.reconcile(context, database) { exportRequests++ }
    }

    private suspend fun insertDeleteRecord(songId: String, filePath: String, phase: PendingCleanupPhase) {
        database.pendingCleanupDao().insert(
            PendingCleanupEntity(
                operationId = UUID.randomUUID().toString(),
                songId = songId,
                operationType = PendingCleanupType.DELETE.name,
                filePath = filePath,
                publishedPath = null,
                phase = phase.name,
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    private suspend fun seedSong(id: String, filePath: String) {
        database.songDao().upsertSong(
            SongEntity(
                id = id,
                title = id,
                albumId = null,
                duration = 1,
                filePath = filePath,
                albumArtUri = null,
                year = null,
                dateAdded = 1,
            ),
        )
    }

    /** Keeps all preference/art writes separate from the installed app's library. */
    private class ReconciliationTestContext(base: Context) : ContextWrapper(base) {
        private val prefix = "reconciliation-test-${UUID.randomUUID()}"
        private val preferenceNames = mutableSetOf<String>()
        private val testFiles = File(base.cacheDir, prefix).also { it.mkdirs() }

        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = testFiles

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolatedName = "$prefix-$name"
            preferenceNames += isolatedName
            return super.getSharedPreferences(isolatedName, mode)
        }

        fun cleanUp() {
            preferenceNames.forEach { baseContext.deleteSharedPreferences(it) }
            check(testFiles.parentFile == baseContext.cacheDir)
            testFiles.deleteRecursively()
        }
    }
}
