package com.v1per.synthx.data.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.preferences.PreferencesManager
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Restore artifacts of other operations that a failed completion cleanup left behind are swept
 * when a new restore starts. The newest valid safety copy among them is kept.
 */
@RunWith(AndroidJUnit4::class)
class RestoreStrandedArtifactSweepInstrumentedTest : RestoreScenarioTestBase() {
    private var savedBackupFolder: String? = null

    @Before fun isolatePreferences() {
        val preferences = PreferencesManager(context)
        savedBackupFolder = preferences.backupFolderUri
        preferences.backupFolderUri = null
        preferences.artSweepPending = false
    }

    @After fun restorePreferences() {
        PreferencesManager(context).backupFolderUri = savedBackupFolder
        PreferencesManager(context).artSweepPending = false
    }

    @Test fun newRestoreSweepsTheLeftoversOfAFailedCleanup() = runBlocking {
        PreferencesManager(context).backupFolderUri = UNREACHABLE_FOLDER
        val first = scenario(
            RestoreTransaction.Phase.POST_VALIDATED,
            old = "original",
            installCandidate = true,
        ).state
        val firstNames = RestoreArtifactNames(first.operationId)
        val source = newTrustedSource("second-restore")

        val result = execute(source, metadata(source))

        assertTrue(result.exceptionOrNull()?.stackTraceToString(), result.isSuccess)
        assertFalse("the first operation's old copy is swept", File(databaseDir, firstNames.old).exists())
        assertTrue(
            "the only safety copy of the first operation is kept",
            File(databaseDir, firstNames.safetyPromotion).isFile,
        )
        val second = readState()
        assertNotEquals(first.operationId, second.operationId)
        assertEquals(RestoreTransaction.Phase.POST_VALIDATED, second.phase)
        assertTrue(File(databaseDir, second.oldName).isFile)
        assertTrue(File(databaseDir, second.safetyName).isFile)
        assertEquals("candidate", readMarker(File(databaseDir, second.oldName)))
        assertEquals("second-restore", readMarker(liveFile()))
    }

    @Test fun cleanStateRestoreSweepsNothingAndStillInstalls() = runBlocking {
        createLive("state-a")
        val source = newTrustedSource("state-b")

        val result = execute(source, metadata(source))

        assertTrue(result.exceptionOrNull()?.stackTraceToString(), result.isSuccess)
        assertEquals("state-b", readMarker(liveFile()))
        assertEquals("state-a", readMarker(File(databaseDir, readState().oldName)))
    }

    @Test fun sweepKeepsOnlyTheNewestSafetyCopyOfTwoStrandedOperations() {
        val older = stranded(safetyAge = 2_000L)
        val newer = stranded(safetyAge = 1_000L)
        val current = newOp()
        val currentCandidate = File(databaseDir, RestoreArtifactNames(current).candidate)
        createDatabaseFile(currentCandidate, "current")

        RestoreTransaction.sweepStrandedArtifacts(databaseDir, current)

        assertTrue(File(databaseDir, newer.safety).isFile)
        assertFalse(File(databaseDir, older.safety).exists())
        listOf(older, newer).forEach {
            assertFalse(File(databaseDir, it.old).exists())
            assertFalse(File(databaseDir, it.candidate).exists())
            assertFalse(File(databaseDir, it.safetyPromotion).exists())
        }
        assertTrue("the operation being started keeps its files", currentCandidate.isFile)
    }

    @Test fun sweepKeepsTheNewestSafetyCopyThatValidates() {
        val older = stranded(safetyAge = 2_000L)
        val newer = stranded(safetyAge = 1_000L)
        File(databaseDir, newer.safety).writeText("not a database")
        File(databaseDir, newer.safety).setLastModified(System.currentTimeMillis() - 1_000L)

        RestoreTransaction.sweepStrandedArtifacts(databaseDir, newOp())

        assertTrue(File(databaseDir, older.safety).isFile)
        assertFalse(File(databaseDir, newer.safety).exists())
    }

    @Test fun sweepCountsAStagedSafetyPromotionAsASafetyCopy() {
        val older = stranded(safetyAge = 2_000L)
        val newer = stranded(safetyAge = 1_000L)
        File(databaseDir, newer.safety).renameTo(File(databaseDir, newer.safetyPromotion))
        File(databaseDir, newer.safetyPromotion).setLastModified(System.currentTimeMillis() - 1_000L)

        RestoreTransaction.sweepStrandedArtifacts(databaseDir, newOp())

        assertTrue(File(databaseDir, newer.safetyPromotion).isFile)
        assertFalse(File(databaseDir, older.safety).exists())
    }

    @Test fun sweepDeletesAnOlderStagedSafetyPromotion() {
        val older = stranded(safetyAge = 2_000L)
        File(databaseDir, older.safety).renameTo(File(databaseDir, older.safetyPromotion))
        File(databaseDir, older.safetyPromotion).setLastModified(System.currentTimeMillis() - 2_000L)
        val newer = stranded(safetyAge = 1_000L)

        RestoreTransaction.sweepStrandedArtifacts(databaseDir, newOp())

        assertFalse(File(databaseDir, older.safetyPromotion).exists())
        assertTrue(File(databaseDir, newer.safety).isFile)
    }

    @Test fun sweepWithNothingStrandedLeavesTheFolderUnchanged() {
        createLive("live")
        val current = newOp()
        createDatabaseFile(File(databaseDir, RestoreArtifactNames(current).candidate), "current")
        val before = databaseDir.list().orEmpty().toSet()

        RestoreTransaction.sweepStrandedArtifacts(databaseDir, current)

        assertEquals(before, databaseDir.list().orEmpty().toSet())
    }

    @Test fun sweepLeavesFailedLiveCopiesRecoveredArchivesAndTheJournalAlone() {
        val other = stranded(safetyAge = 1_000L)
        val failed = File(databaseDir, other.failed)
        val failedIndexed = File(databaseDir, other.failed.removeSuffix(".db") + "-1.db")
        val recovered = File(databaseDir, other.old + ".recovered")
        failed.writeText("parked")
        failedIndexed.writeText("parked")
        recovered.writeText("archive")
        val journal = scenario(RestoreTransaction.Phase.COMPLETE, old = "original").state
        val journalBytes = stateFile().readText()

        RestoreTransaction.sweepStrandedArtifacts(databaseDir, newOp())

        assertTrue(failed.isFile)
        assertTrue(failedIndexed.isFile)
        assertTrue(recovered.isFile)
        assertEquals(journalBytes, stateFile().readText())
        assertFalse(File(databaseDir, other.old).exists())
        assertFalse("the journal's own leftovers are swept", File(databaseDir, journal.oldName).exists())
    }

    @Test fun sweepSkipsTheOperationOfAnUnfinishedJournal() {
        val unfinished = scenario(RestoreTransaction.Phase.PREPARED, live = "live", old = "original").state
        val other = stranded(safetyAge = 1_000L)

        RestoreTransaction.sweepStrandedArtifacts(databaseDir, newOp())

        listOf(unfinished.candidateName, unfinished.oldName, unfinished.safetyName).forEach {
            assertTrue("$it belongs to an unfinished journal", File(databaseDir, it).isFile)
        }
        assertFalse(File(databaseDir, other.old).exists())
    }

    @Test fun sweepDoesNothingWhenTheJournalCannotBeRead() {
        val other = stranded(safetyAge = 1_000L)
        stateFile().writeText("{")

        RestoreTransaction.sweepStrandedArtifacts(databaseDir, newOp())

        assertTrue(File(databaseDir, other.old).isFile)
        assertTrue(File(databaseDir, other.safety).isFile)
    }

    @Test fun executeRefusesAndSweepsNothingWhenRecoveryIsNotNormal() = runBlocking {
        val unfinished = scenario(
            RestoreTransaction.Phase.CANDIDATE_INSTALLED,
            old = "original",
            installCandidate = true,
        ).state
        val other = stranded(safetyAge = 1_000L)
        val source = newTrustedSource("must-not-install")

        val result = execute(source, metadata(source))

        assertTrue(result.isFailure)
        assertTrue(File(databaseDir, other.old).isFile)
        assertTrue(File(databaseDir, other.candidate).isFile)
        assertTrue(File(databaseDir, unfinished.oldName).isFile)
        assertTrue(File(databaseDir, unfinished.safetyName).isFile)
        assertEquals("candidate", readMarker(liveFile()))
    }

    private class Stranded(val names: RestoreArtifactNames) {
        val candidate get() = names.candidate
        val old get() = names.old
        val safety get() = names.safety
        val safetyPromotion get() = names.safetyPromotion
        val failed get() = names.failed
    }

    /** Candidate, old and safety files of an operation that has no journal, the safety copy [safetyAge] ms old. */
    private fun stranded(safetyAge: Long): Stranded {
        val names = RestoreArtifactNames(newOp())
        listOf(names.candidate, names.old, names.safety).forEach {
            createDatabaseFile(File(databaseDir, it), "stranded")
        }
        File(databaseDir, names.safety).setLastModified(System.currentTimeMillis() - safetyAge)
        return Stranded(names)
    }

    private companion object {
        const val UNREACHABLE_FOLDER = "content://com.v1per.synthx.test.nowhere/export"
    }
}
