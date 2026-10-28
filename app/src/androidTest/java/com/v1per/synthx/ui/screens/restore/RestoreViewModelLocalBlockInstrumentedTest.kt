package com.v1per.synthx.ui.screens.restore

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.v1per.synthx.data.backup.BackupInfo
import com.v1per.synthx.data.backup.BackupManager
import com.v1per.synthx.data.backup.BackupTier
import com.v1per.synthx.data.backup.RestorePermitLeakCheck
import com.v1per.synthx.data.backup.RestoreStartup
import com.v1per.synthx.data.backup.RestoreWorker
import com.v1per.synthx.data.backup.TestWorkManager
import com.v1per.synthx.preferences.PreferencesManager
import com.v1per.synthx.ui.testing.TestTimeouts
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Which request a dismissed restore failure acknowledges. A failure made up locally (no music
 * folder) has no request of its own, so dismissing it must leave an earlier request's
 * unshown result alone; a real terminal outcome is acknowledged and reported as before.
 */
// Test-only restricted API use; there is no public equality-based alternative.
@SuppressLint("RestrictedApi")
@RunWith(AndroidJUnit4::class)
class RestoreViewModelLocalBlockInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.device()

    private val base: Context = ApplicationProvider.getApplicationContext()
    private val preferenceName = "restore-local-block-${UUID.randomUUID()}"
    private val context = object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            base.getSharedPreferences("$preferenceName-$name", mode)
    }
    private val preferences = PreferencesManager(context)
    private val workManager get() = WorkManager.getInstance(context)

    @Before fun setUp() {
        RestoreStartup.await()
        TestWorkManager.ensureInitialized(context)
        RestorePermitLeakCheck.assertDrainedThenReset()
        workManager.cancelUniqueWork(RestoreWorker.UNIQUE_WORK_NAME).result.get()
        workManager.pruneWork().result.get()
    }

    @After fun tearDown() {
        workManager.cancelUniqueWork(RestoreWorker.UNIQUE_WORK_NAME).result.get()
        workManager.pruneWork().result.get()
        base.deleteSharedPreferences("$preferenceName-synthx_prefs")
        RestorePermitLeakCheck.assertDrainedThenReset()
    }

    @Test fun dismissingALocalBlockAcknowledgesNothingAndReportsNothing() {
        finishedUnacknowledgedRequest()
        preferences.musicFolderUri = null
        val vm = newViewModel()
        val shown = mutableListOf<UUID>()

        vm.restore(backup())
        vm.terminalOutcomeWasShown { shown += it }

        assertFalse("the earlier request's result was never shown", preferences.restoreOutcomeAcknowledged)
        assertTrue("no shown callback for a local block: $shown", shown.isEmpty())
    }

    @Test fun dismissingARealTerminalOutcomeAcknowledgesItAndReportsIt() {
        val requestId = finishedUnacknowledgedRequest()
        val vm = newViewModel()
        val shown = mutableListOf<UUID>()

        vm.terminalOutcomeWasShown { shown += it }

        assertTrue(preferences.restoreOutcomeAcknowledged)
        assertEquals(listOf(requestId), shown)
    }

    private fun newViewModel(): RestoreViewModel = RestoreViewModel(context, BackupManager(context), preferences)

    private fun finishedUnacknowledgedRequest(): UUID = runBlocking {
        val requestId = RestoreWorker.enqueue(context, backup())
        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(requestId)
        withTimeout(10_000) {
            workManager.getWorkInfoByIdFlow(requestId).first { it?.state?.isFinished == true }!!
        }
        assertFalse(preferences.restoreOutcomeAcknowledged)
        assertEquals(requestId.toString(), preferences.restoreRequestId)
        requestId
    }

    private fun backup() = BackupInfo(
        databaseUri = Uri.EMPTY,
        metadataUri = null,
        fileName = "missing.db",
        tier = BackupTier.Manual,
        timestamp = System.currentTimeMillis(),
        reason = null,
        sizeBytes = 1L,
        restoreEligible = true,
    )
}
