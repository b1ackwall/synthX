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

    @Before
    fun setUp() {
        PendingCleanupReconciliationTracker.resetForTest()
        context = ReconciliationTestContext(ApplicationProvider.getApplicationContext())
        database = Room.inMemoryDatabaseBuilder(context, SynthXDatabase::class.java).build()
        File(context.filesDir, "art").mkdirs()
    }

    @After
    fun tearDown() {
        publishedFiles.forEach(File::delete)
        database.close()
        context.cleanUp()
        PendingCleanupReconciliationTracker.resetForTest()
    }

    @Test
    fun deleteInterruptedAfterCommitIsReconciledOnNextLaunch() = runBlocking {
        seedSong("song", filePath = "test://song")
        File(context.filesDir, "art/art_song.jpg").writeText("test art")

        // The exact durable state a kill right after deleteSong's transaction commits, but
        // before finishSongDeletion ever ran, leaves behind — see
        // SongRepositoryImpl.deleteSong's pendingCleanupDao.insert call.
        val operationId = UUID.randomUUID().toString()
        database.songDao().deleteSongById("song")
        database.pendingCleanupDao().insert(
            PendingCleanupEntity(
                operationId = operationId,
                songId = "song",
                operationType = PendingCleanupType.DELETE.name,
                filePath = "test://song",
                publishedPath = null,
                phase = PendingCleanupPhase.Committed.name,
                createdAt = System.currentTimeMillis(),
            ),
        )

        // Precondition: row is gone, cleanup never ran, record survives.
        assertNull(database.songDao().getSongById("song"))
        assertTrue(File(context.filesDir, "art/art_song.jpg").exists())
        assertEquals(1, database.pendingCleanupDao().getAll().size)

        PendingCleanupReconciliation.reconcile(context, database)

        assertFalse(File(context.filesDir, "art/art_song.jpg").exists())
        assertTrue(database.pendingCleanupDao().getAll().isEmpty())
        assertEquals(1, PendingCleanupReconciliationTracker.lastCount.value)
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

        PendingCleanupReconciliation.reconcile(context, database)

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

        PendingCleanupReconciliation.reconcile(context, database)

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

        PendingCleanupReconciliation.reconcile(context, database)

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

        PendingCleanupReconciliation.reconcile(context, database)

        assertNull(database.songDao().getSongById("gone-song"))
        assertTrue(database.pendingCleanupDao().getAll().isEmpty())
    }

    @Test
    fun noLeftoverRecordsIsANoOpThatDoesNotTouchTheTracker() = runBlocking {
        PendingCleanupReconciliation.reconcile(context, database)

        assertEquals(0, PendingCleanupReconciliationTracker.lastCount.value)
        assertEquals(
            "an empty pass must not record a reconciliation",
            0L,
            PendingCleanupReconciliationTracker.lastTimestamp.value,
        )
    }

    private fun reachablePublishedDocument(): String {
        val appContext = context.baseContext
        val file =
            File(File(appContext.filesDir, "restore_incidents").apply { mkdirs() }, "${UUID.randomUUID()}.published")
        file.writeText("published")
        publishedFiles += file
        return FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", file).toString()
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
