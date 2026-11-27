package com.v1per.synthx.data.repository

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.data.AudioFileDeletion
import com.v1per.synthx.data.backup.RestoreCoordinator
import com.v1per.synthx.data.db.PendingCleanupPhase
import com.v1per.synthx.data.scanner.readsAudioFilePrefix
import com.v1per.synthx.ui.testing.TestTimeouts
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
 * What a replace reports and keeps once its transaction has committed, and the readability probe
 * that runs before it. Nothing after the commit may turn the replace into a failure, and the
 * cleanup record stays while the old file may still be on disk.
 */
@RunWith(AndroidJUnit4::class)
class ReplaceSongPostCommitInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.device()

    private lateinit var fx: ReplaceSongTestFixture

    @Before fun setUp() = runBlocking {
        fx = ReplaceSongTestFixture(ApplicationProvider.getApplicationContext())
    }

    @After fun tearDown() = fx.close()

    @Test fun aRestoreClosingAdmissionAfterTheCommitIsAWarningAndKeepsTheRecord() = runBlocking {
        fx.seed()
        val repository = fx.repository(
            audioFileDelete = {
                // A restore starts between the commit and the record's clear: the nested permit
                // for that clear is refused, exactly as a real restore preparation refuses it.
                runBlocking { RestoreCoordinator.exclusive { } }
                AudioFileDeletion.Deleted
            },
        )

        val result = fx.replace(repository)

        assertEquals(ReplaceSongResult.Success(OldAudioFile.Removed, followUpIncomplete = true), result)
        assertNull("the replace committed", fx.songDao.getSongById("old"))
        assertEquals(2, fx.playlistDao.getPlaylistMembershipsForSong("new").single().position)
        val record = fx.pendingCleanupDao.getAll().single()
        assertEquals("left for startup reconciliation", PendingCleanupPhase.Committed.name, record.phase)
        assertEquals("test://old", record.filePath)
    }

    @Test fun aFailedExportRequestIsAWarningNotAFailure() = runBlocking {
        fx.seed()
        val repository = fx.repository(
            audioFileDelete = { AudioFileDeletion.Deleted },
            schedulePlaylistExport = { error("export request failed") },
        )

        val result = fx.replace(repository)

        assertEquals(ReplaceSongResult.Success(OldAudioFile.Removed, followUpIncomplete = true), result)
        assertTrue("the file is gone, so the record is cleared", fx.pendingCleanupDao.getAll().isEmpty())
    }

    @Test fun aSurvivingOldFileKeepsItsRecordForStartupAndScans() = runBlocking {
        fx.seed()
        val repository = fx.repository(audioFileDelete = { AudioFileDeletion.Failed })

        val result = fx.replace(repository)

        assertEquals(ReplaceSongResult.Success(OldAudioFile.StillPresent), result)
        val record = fx.pendingCleanupDao.getAll().single()
        assertEquals(PendingCleanupPhase.AudioFileRemains.name, record.phase)
        assertEquals("old", record.songId)
        assertEquals(listOf("test://old"), fx.pendingCleanupDao.getPendingDeletePaths())
    }

    @Test fun anUnconfirmedDeleteAlsoKeepsTheRecord() = runBlocking {
        fx.seed()
        val repository = fx.repository(audioFileDelete = { AudioFileDeletion.Unknown })

        assertEquals(ReplaceSongResult.Success(OldAudioFile.Unconfirmed), fx.replace(repository))
        assertEquals(PendingCleanupPhase.AudioFileRemains.name, fx.pendingCleanupDao.getAll().single().phase)
    }

    @Test fun aRemovedOldFileClearsTheRecord() = runBlocking {
        fx.seed()
        val repository = fx.repository(audioFileDelete = { AudioFileDeletion.ConfirmedAbsent })

        assertEquals(ReplaceSongResult.Success(OldAudioFile.Removed), fx.replace(repository))
        assertTrue(fx.pendingCleanupDao.getAll().isEmpty())
    }

    @Test fun aStaleSentinelOldPathHasNoFileToKeepARecordFor() = runBlocking {
        fx.seed(oldPath = "stale://old")
        // The real delete: a stale sentinel resolves to no provider, so it cannot be confirmed.
        val repository = fx.repository()

        assertEquals(ReplaceSongResult.Success(OldAudioFile.Removed), fx.replace(repository))
        assertTrue(fx.pendingCleanupDao.getAll().isEmpty())
    }

    @Test fun realDocumentsReplaceWithTheRealDeletedOutcome() = runBlocking {
        val oldDocument = fx.document()
        val newDocument = fx.document()
        fx.seed(oldPath = oldDocument, newPath = newDocument)
        // The real probe and the real file delete, against file-backed documents.
        val repository = fx.repository(replacementReadable = { readsAudioFilePrefix(fx.context, it) })

        val result = fx.replace(repository)

        assertEquals(ReplaceSongResult.Success(OldAudioFile.Removed), result)
        assertNull(fx.songDao.getSongById("old"))
        assertEquals(newDocument, fx.songDao.getSongById("new")?.filePath)
        assertTrue(fx.pendingCleanupDao.getAll().isEmpty())
    }

    @Test fun anUnreadableReplacementIsRejectedBeforeAnythingChanges() = runBlocking {
        fx.seed(newPath = fx.missingDocument())
        val repository = fx.repository(
            audioFileDelete = { error("Nothing may be deleted") },
            replacementReadable = { readsAudioFilePrefix(fx.context, it) },
        )

        val result = fx.replace(repository)

        assertEquals(ReplaceSongResult.NewSongUnreadable, result)
        assertNotNull(fx.songDao.getSongById("old"))
        assertEquals(2, fx.playlistDao.getPlaylistMembershipsForSong("old").single().position)
        assertTrue(fx.playlistDao.getPlaylistMembershipsForSong("new").isEmpty())
        assertEquals(true, fx.songDao.getSongById("new")?.isStarred)
        assertTrue(fx.pendingCleanupDao.getAll().isEmpty())
    }

    @Test fun anEmptyReplacementFileReadsAsUnreadable() = runBlocking {
        val empty = fx.document(ByteArray(0))
        val readable = fx.document(ByteArray(10) { 1 })

        assertFalse(readsAudioFilePrefix(fx.context, empty))
        assertTrue(readsAudioFilePrefix(fx.context, readable))
        assertFalse(readsAudioFilePrefix(fx.context, fx.missingDocument()))
        assertFalse(readsAudioFilePrefix(fx.context, "test://no-provider"))
    }
}
