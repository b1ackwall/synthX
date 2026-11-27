package com.v1per.synthx.data.repository

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.data.AudioFileDeletion
import com.v1per.synthx.data.FileOperationCoordinator
import com.v1per.synthx.data.backup.RestoreCoordinator
import com.v1per.synthx.data.db.PendingCleanupEntity
import com.v1per.synthx.data.db.PendingCleanupPhase
import com.v1per.synthx.data.db.PendingCleanupType
import com.v1per.synthx.data.db.SongEntity
import com.v1per.synthx.ui.testing.TestTimeouts
import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [SongRepository.retryAudioFileDeletion] deletes only under the old song's lock and restore
 * admission, and never deletes a file a song row owns again.
 */
@RunWith(AndroidJUnit4::class)
class AudioFileRetryInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.device()

    private lateinit var fx: ReplaceSongTestFixture
    private val path = "test://old"
    private val deleteAttempts: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @Before fun setUp() {
        fx = ReplaceSongTestFixture(ApplicationProvider.getApplicationContext())
    }

    @After fun tearDown() = fx.close()

    private fun repository(outcome: AudioFileDeletion = AudioFileDeletion.Deleted) = fx.repository(
        audioFileDelete = { attempted ->
            deleteAttempts += attempted
            outcome
        },
    )

    @Test fun aRetryWaitsForTheSongLockBeforeDeleting() = runBlocking {
        val repository = repository()
        val lockHeld = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = async(Dispatchers.IO) {
            FileOperationCoordinator.withSongLock("old") {
                lockHeld.complete(Unit)
                release.await()
            }
        }
        lockHeld.await()

        val retry = async(Dispatchers.IO) { repository.retryAudioFileDeletion("old", path) }
        delay(300)
        assertTrue("nothing may be deleted while another operation holds the song", deleteAttempts.isEmpty())

        release.complete(Unit)
        holder.await()
        assertEquals(AudioFileRetryOutcome.Deleted, withTimeout(5_000) { retry.await() })
        assertEquals(listOf(path), deleteAttempts)
    }

    @Test fun aFileASongOwnsAgainIsLeftInPlace() = runBlocking {
        // A scan imported the surviving file as a song between the replace and the retry.
        fx.songDao.upsertSong(
            SongEntity(
                id = "old",
                title = "old",
                albumId = null,
                duration = 1,
                filePath = path,
                albumArtUri = null,
                year = null,
                dateAdded = 1,
            ),
        )

        assertEquals(AudioFileRetryOutcome.FileInLibrary, repository().retryAudioFileDeletion("old", path))
        assertTrue(deleteAttempts.isEmpty())
    }

    @Test fun aRetryDeletesWhenNoSongOwnsThePathAndClearsTheKeptRecord() = runBlocking {
        fx.pendingCleanupDao.insert(
            PendingCleanupEntity(
                operationId = "op",
                songId = "old",
                operationType = PendingCleanupType.DELETE.name,
                filePath = path,
                publishedPath = null,
                phase = PendingCleanupPhase.AudioFileRemains.name,
                createdAt = 1,
            ),
        )

        assertEquals(AudioFileRetryOutcome.Deleted, repository().retryAudioFileDeletion("old", path))
        assertEquals(listOf(path), deleteAttempts)
        assertTrue(fx.pendingCleanupDao.getAll().isEmpty())
    }

    @Test fun aRefusedDeleteKeepsTheRecord() = runBlocking {
        fx.pendingCleanupDao.insert(
            PendingCleanupEntity(
                "op",
                "old",
                PendingCleanupType.DELETE.name,
                path,
                null,
                PendingCleanupPhase.AudioFileRemains.name,
                1,
            ),
        )

        assertEquals(
            AudioFileRetryOutcome.FileRemained,
            repository(AudioFileDeletion.Failed).retryAudioFileDeletion("old", path),
        )
        assertEquals(1, fx.pendingCleanupDao.getAll().size)
    }

    @Test fun withoutRestoreAdmissionNothingIsDeleted() = runBlocking {
        val repository = repository()
        RestoreCoordinator.exclusive { }

        assertEquals(AudioFileRetryOutcome.CleanupUncertain, repository.retryAudioFileDeletion("old", path))
        assertTrue(deleteAttempts.isEmpty())
    }
}
