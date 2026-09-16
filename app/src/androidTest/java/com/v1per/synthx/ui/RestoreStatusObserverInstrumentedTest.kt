package com.v1per.synthx.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.net.Uri
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.ViewModelStore
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.v1per.synthx.R
import com.v1per.synthx.data.backup.BackupInfo
import com.v1per.synthx.data.backup.BackupTier
import com.v1per.synthx.data.backup.RestoreWorker
import com.v1per.synthx.data.backup.RestoreCoordinator
import com.v1per.synthx.data.backup.RestoreStartup
import com.v1per.synthx.data.backup.TestWorkManager
import com.v1per.synthx.data.backup.pruneFinishedWorkIfRestoreAcknowledged
import com.v1per.synthx.preferences.PreferencesManager
import com.v1per.synthx.ui.screens.restore.RestoreAppStatus
import com.v1per.synthx.ui.screens.restore.RestoreStatusViewModel
import com.v1per.synthx.ui.theme.SynthXTheme
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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

@RunWith(AndroidJUnit4::class)
class RestoreStatusObserverInstrumentedTest {
    @get:Rule val composeRule = createComposeRule()

    private val base: Context = ApplicationProvider.getApplicationContext()
    private val preferenceName = "restore-status-${UUID.randomUUID()}"
    private val context = object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            base.getSharedPreferences("$preferenceName-$name", mode)
    }
    private val preferences = PreferencesManager(context)
    private val workManager get() = WorkManager.getInstance(context)
    private val viewModelStores = mutableListOf<ViewModelStore>()

    @Before fun setUp() {
        RestoreStartup.await()
        TestWorkManager.ensureInitialized(context)
        if (RestoreCoordinator.activeOperationCountForTest() == 0) RestoreCoordinator.resetForTest()
        workManager.cancelUniqueWork(RestoreWorker.UNIQUE_WORK_NAME).result.get()
        workManager.pruneWork().result.get()
    }

    @After fun tearDown() {
        viewModelStores.forEach(ViewModelStore::clear)
        viewModelStores.clear()
        workManager.cancelUniqueWork(RestoreWorker.UNIQUE_WORK_NAME).result.get()
        workManager.pruneWork().result.get()
        if (RestoreCoordinator.activeOperationCountForTest() == 0) RestoreCoordinator.resetForTest()
        base.deleteSharedPreferences("$preferenceName-synthx_prefs")
    }

    @Test fun terminalFailureCompletedWithoutWizardIsShownAndAcknowledged() {
        val requestId = enqueueInvalidRestore()
        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(requestId)
        awaitFinished(requestId)

        // Construct the observer only after completion: no route or collector existed when the
        // worker failed, matching the process-death/relaunch handoff without racing a process.
        val viewModel = observedViewModel()
        composeRule.setContent {
            SynthXTheme {
                val status by viewModel.status.collectAsState()
                RestoreObservedStatusHost(status, routeOwnsRestoreOutcome = false, viewModel)
            }
        }

        composeRule.onNodeWithText(base.getString(R.string.restore_failed_title)).assertExists()
        composeRule.waitUntil(10_000) { preferences.restoreOutcomeAcknowledged }
        assertTrue(viewModel.status.value is RestoreAppStatus.Failed)
        // The raw exception message (see BackupManager.restore) is wrapped by
        // RestoreFailureFormatter.describe with a "restore failed: <ExceptionType>: " prefix.
        assertTrue(
            (viewModel.status.value as RestoreAppStatus.Failed).message
                .contains(base.getString(R.string.restore_ineligible_missing_metadata)),
        )
    }

    @Test fun pruningRetainsUnshownTerminalWorkThenRemovesAcknowledgedWork() {
        val requestId = enqueueInvalidRestore()
        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(requestId)
        awaitFinished(requestId)

        assertNull(runBlocking { pruneFinishedWorkIfRestoreAcknowledged(workManager, preferences) })
        assertNotNull(workManager.getWorkInfoById(requestId).get())

        assertTrue(preferences.acknowledgeRestoreOutcomeDurably(requestId.toString()))
        val prune = runBlocking { pruneFinishedWorkIfRestoreAcknowledged(workManager, preferences) }
        assertNotNull(prune)
        prune!!.result.get()
        assertNull(workManager.getWorkInfoById(requestId).get())
    }

    @Test fun constrainedQueuedRestoreShowsStorageWaitAfterGracePeriod() {
        val requestId = enqueueInvalidRestore()
        val viewModel = observedViewModel()
        composeRule.setContent {
            SynthXTheme {
                val status by viewModel.status.collectAsState()
                RestoreObservedStatusHost(status, routeOwnsRestoreOutcome = false, viewModel)
            }
        }

        composeRule.waitUntil(10_000) {
            viewModel.status.value is RestoreAppStatus.WaitingForStorage
        }
        composeRule.onNodeWithTag(ShellTestTags.RestorePending).assertExists()
        composeRule.onNodeWithText(base.getString(R.string.restore_pending_storage)).assertExists()
        val queued = workManager.getWorkInfoById(requestId).get()!!
        assertEquals(WorkInfo.State.ENQUEUED, queued.state)
        assertTrue(queued.constraints.requiresStorageNotLow())
        assertFalse(queued.constraints.requiresCharging())
        assertFalse(queued.constraints.requiresBatteryNotLow())
        assertFalse(preferences.restoreOutcomeAcknowledged)
    }

    private fun enqueueInvalidRestore(): UUID = runBlocking {
        RestoreWorker.enqueue(
            context,
            BackupInfo(
                databaseUri = Uri.EMPTY,
                metadataUri = null,
                fileName = "missing.db",
                tier = BackupTier.Manual,
                timestamp = System.currentTimeMillis(),
                reason = null,
                sizeBytes = 1L,
                restoreEligible = true,
            ),
        )
    }

    private fun observedViewModel(): RestoreStatusViewModel {
        val viewModel = RestoreStatusViewModel(context, preferences, pendingGraceMillis = 50L)
        viewModelStores += ViewModelStore().also { it.put("restore", viewModel) }
        return viewModel
    }

    private fun awaitFinished(requestId: UUID): WorkInfo = runBlocking {
        withTimeout(10_000) {
            workManager.getWorkInfoByIdFlow(requestId).first { it?.state?.isFinished == true }!!
        }
    }
}
