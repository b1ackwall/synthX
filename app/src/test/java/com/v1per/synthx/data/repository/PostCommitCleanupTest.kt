package com.v1per.synthx.data.repository

import com.v1per.synthx.data.AudioFileDeletion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PostCommitCleanupTest {

    @Test
    fun failedStepIsLoggedAndDoesNotPreventTheNextStep() = runBlocking {
        val failure = IllegalStateException("event bus unavailable")
        val logged = mutableListOf<Pair<String, Throwable>>()
        var laterStepRan = false

        val firstSucceeded = runPostCommitStep(
            step = "notify song deletion",
            logFailure = { step, error -> logged += step to error },
        ) {
            throw failure
        }
        val secondSucceeded = runPostCommitStep(
            step = "delete cached song art",
            logFailure = { step, error -> logged += step to error },
        ) {
            laterStepRan = true
        }

        assertFalse(firstSucceeded)
        assertTrue(secondSucceeded)
        assertTrue(laterStepRan)
        assertEquals("notify song deletion", logged.single().first)
        assertSame(failure, logged.single().second)
    }

    @Test
    fun unknownAudioStateProducesCleanupUncertain() {
        assertEquals(
            SongDeletionOutcome.CleanupUncertain,
            songDeletionOutcome(
                audioOutcome = AudioFileDeletion.Unknown,
                cleanupUncertain = false,
            ),
        )
        assertEquals(
            SongDeletionOutcome.Deleted,
            songDeletionOutcome(
                audioOutcome = AudioFileDeletion.ConfirmedAbsent,
                cleanupUncertain = false,
            ),
        )
        assertEquals(
            SongDeletionOutcome.FileRemained,
            songDeletionOutcome(
                audioOutcome = AudioFileDeletion.Failed,
                cleanupUncertain = false,
            ),
        )
    }

    @Test
    fun confirmedFileRemainedTakesPriorityOverUncertainExport() {
        assertEquals(
            SongDeletionOutcome.FileRemained,
            includePlaylistExportOutcome(
                deletionOutcome = SongDeletionOutcome.FileRemained,
                exportSucceeded = false,
            ),
        )
    }

    @Test
    fun cancellationStillPropagates() {
        assertFailsWith<CancellationException> {
            runBlocking {
                runPostCommitStep(
                    step = "notify song deletion",
                    logFailure = { _, _ -> error("Cancellation must not be logged as failure") },
                ) {
                    throw CancellationException("cancelled")
                }
            }
        }
    }
}
