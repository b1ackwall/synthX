package com.v1per.synthx

import com.v1per.synthx.ui.testing.TestTimeouts
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The order [runLaunchSequence] gives the launch steps: the saved-queue restore is requested
 * first, before pruning (the first suspension, from which the UI takes input) and long before the
 * daily backup finishes; the rest of launch maintenance keeps its order behind the backup.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LaunchSequenceTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.jvm()

    private class RecordingSteps(override val maintenanceAllowed: Boolean, private val scope: CoroutineScope) :
        LaunchSteps {
        val log = mutableListOf<String>()
        val backupGate = CompletableDeferred<Unit>()
        val pruneGate = CompletableDeferred<Unit>()

        override fun restoreSavedQueue() {
            log += "restore"
        }

        override suspend fun pruneFinishedWork() {
            log += "prune"
            pruneGate.await()
        }

        override fun launchMaintenance(block: suspend () -> Unit) {
            log += "launch"
            scope.launch { block() }
        }

        override suspend fun backupDailyIfDue() {
            log += "backup"
            backupGate.await()
            log += "backup done"
        }

        override fun scanIssuesDailyIfDue() {
            log += "issues"
        }

        override suspend fun runArtSweepIfPending() {
            log += "art sweep"
        }

        override suspend fun backfillPlaylistArt() {
            log += "playlist art"
        }

        override fun runLaunchScansIfFolderSet() {
            log += "launch scans"
        }
    }

    @Test fun theRestoreIsRequestedBeforePruningAndBeforeTheBackupFinishes() = runTest {
        val steps = RecordingSteps(maintenanceAllowed = true, scope = this)

        launch { runLaunchSequence(steps) }
        runCurrent()
        assertEquals(listOf("restore", "prune"), steps.log, "requested before the first suspension")

        steps.pruneGate.complete(Unit)
        runCurrent()
        assertEquals(listOf("restore", "prune", "launch", "backup"), steps.log, "the backup is still running")

        steps.backupGate.complete(Unit)
        advanceUntilIdle()
        assertEquals(
            listOf(
                "restore",
                "prune",
                "launch",
                "backup",
                "backup done",
                "issues",
                "art sweep",
                "playlist art",
                "launch scans",
            ),
            steps.log,
        )
    }

    @Test fun withMaintenanceHeldBackOnlyPruningRuns() = runTest {
        val steps = RecordingSteps(maintenanceAllowed = false, scope = this)
        steps.pruneGate.complete(Unit)

        runLaunchSequence(steps)
        advanceUntilIdle()

        assertEquals(listOf("prune"), steps.log, "no restore and no maintenance during the wizard")
    }
}
