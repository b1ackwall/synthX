package com.v1per.synthx.ui.screens.backupsandrestore

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.data.backup.BackupInfo
import com.v1per.synthx.data.backup.BackupListResult
import com.v1per.synthx.data.backup.BackupManager
import com.v1per.synthx.data.backup.BackupOutcomeStore
import com.v1per.synthx.data.backup.BackupTier
import com.v1per.synthx.preferences.PreferencesManager
import com.v1per.synthx.ui.testing.TestTimeouts
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A rejected backup-folder pick must leave [BackupsAndRestoreViewModel.backupFolderError]
 * set persistently, matching the launch-time/screen-entry health-check convention
 * (direct `.value =` assignment, no timer) rather than a temporary status message.
 */
// Test-only restricted API use; there is no public equality-based alternative.
@SuppressLint("RestrictedApi")
@RunWith(AndroidJUnit4::class)
class BackupsAndRestoreViewModelInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER) val testTimeout = TestTimeouts.device()

    private val appContext: Context = ApplicationProvider.getApplicationContext()
    private val testId = "backups-vm-test-${UUID.randomUUID()}"
    private val context = object : ContextWrapper(appContext) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int) =
            appContext.getSharedPreferences("$testId-$name", mode)
    }
    private val preferences = PreferencesManager(context)
    private val store = ViewModelStore()

    @After
    fun tearDown() {
        store.clear()
        appContext.deleteSharedPreferences("$testId-synthx_prefs")
        BackupOutcomeStore.resetForTest()
    }

    /**
     * `listBackups()`/`ensureFolderStructure()` are `open` specifically so tests
     * can substitute canned results without a genuine SAF tree grant — the same problem
     * [BackupListResultInstrumentedTest] solves for `classifyBackupList` by working against a
     * file-backed [androidx.documentfile.provider.DocumentFile] instead; a ViewModel test needs
     * the whole [BackupManager] substituted, not just that one method.
     */
    private class FakeBackupManager(
        private val fakeContext: Context,
        var result: BackupListResult,
    ) : BackupManager(fakeContext) {
        override suspend fun listBackups(): BackupListResult = result
        override fun ensureFolderStructure(treeUri: Uri): Boolean = true

        /** Counts and slows down createBackup calls so a test can prove taps coalesce. */
        val createBackupCallCount = AtomicInteger(0)
        var createBackupDelayMs = 0L

        override suspend fun createBackup(
            tier: BackupTier,
            reason: String?,
            cancelActiveScan: Boolean,
        ): Result<String> {
            createBackupCallCount.incrementAndGet()
            if (createBackupDelayMs > 0) delay(createBackupDelayMs)
            return Result.success("synthx_backup_fake.db").also {
                BackupOutcomeStore.recordBackupOutcome(fakeContext, tier, reason, it)
            }
        }
    }

    private fun eligibleBackup(timestamp: Long, tier: BackupTier = BackupTier.Manual) = BackupInfo(
        databaseUri = "content://synthx.test.fixture/tree/eligible-$timestamp".toUri(),
        metadataUri = "content://synthx.test.fixture/tree/eligible-$timestamp.json".toUri(),
        fileName = "synthx_backup_eligible_$timestamp.db",
        tier = tier,
        timestamp = timestamp,
        reason = null,
        sizeBytes = 128L,
        restoreEligible = true,
    )

    private fun ineligibleBackup(timestamp: Long) = BackupInfo(
        databaseUri = "content://synthx.test.fixture/tree/ineligible-$timestamp".toUri(),
        metadataUri = null,
        fileName = "synthx_backup_ineligible_$timestamp.db",
        tier = BackupTier.Manual,
        timestamp = timestamp,
        reason = null,
        sizeBytes = 128L,
        restoreEligible = false,
    )

    @Test
    fun rejectedFolderPickErrorPersistsPastTheOldFourSecondWindow() {
        val viewModel = BackupsAndRestoreViewModel(context, preferences, BackupManager(context))
        store.put("under-test", viewModel)

        // No real content provider backs this authority, so ensureFolderStructure fails and
        // saveBackupFolder takes the Outcome.UNUSABLE rejection branch.
        val bogus = "content://synthx.test.rejected/tree/primary%3ABogus".toUri()

        runBlocking {
            viewModel.saveBackupFolder(bogus)
            await(viewModel.backupFolderError) { it != null }
            val messageWhenSet = viewModel.backupFolderError.value
            assertNotNull(messageWhenSet)

            // StatusMessageDisplayMs (the temporary-message window) is 4_000ms; wait well past
            // it and confirm the error is still there instead of having been cleared.
            delay(5_000)
            assertNotNull(viewModel.backupFolderError.value)
        }
    }

    @Test
    fun lastSuccessfulBackupExcludesIneligibleEntryEvenWhenMostRecentByTimestamp() {
        val newerIneligible = ineligibleBackup(timestamp = 2_000L)
        val olderEligible = eligibleBackup(timestamp = 1_000L)
        val manager = FakeBackupManager(
            context,
            BackupListResult.Success(listOf(newerIneligible, olderEligible)),
        )
        val viewModel = BackupsAndRestoreViewModel(context, preferences, manager)
        store.put("under-test", viewModel)

        val health = await(viewModel.backupHealth) { it is BackupHealthState.Succeeded }
        val succeeded = health as BackupHealthState.Succeeded
        assertEquals(olderEligible.timestamp, succeeded.timestamp)
    }

    @Test
    fun folderChangeClearsStaleCachedSuccessFromThePreviousFolder() {
        val manager = FakeBackupManager(context, BackupListResult.Success(listOf(eligibleBackup(1_000L))))
        val viewModel = BackupsAndRestoreViewModel(context, preferences, manager)
        store.put("under-test", viewModel)
        await(viewModel.backupHealth) { it is BackupHealthState.Succeeded }

        // Simulates picking a different, currently-empty backup folder.
        manager.result = BackupListResult.Empty
        viewModel.saveBackupFolder("content://synthx.test.fixture/tree/other-folder".toUri())

        val health = await(viewModel.backupHealth) { it !is BackupHealthState.Succeeded }
        assertEquals(BackupHealthState.Unknown, health)
    }

    @Test
    fun laterSuccessfulAutomaticBackupIsReflectedInHealthDisplay() {
        val manager = FakeBackupManager(context, BackupListResult.Empty)
        val viewModel = BackupsAndRestoreViewModel(context, preferences, manager)
        store.put("under-test", viewModel)
        await(viewModel.backupHealth) { it == BackupHealthState.Unknown }

        // Simulates an Auto-tier backup written by BackupManager.createBackup from outside this
        // ViewModel entirely (e.g. the daily WorkManager job) — the newly-written file is now
        // listable, and BackupManager's single outcome-recording choke point fires.
        val automatic = eligibleBackup(timestamp = 5_000L, tier = BackupTier.Auto)
        manager.result = BackupListResult.Success(listOf(automatic))
        BackupOutcomeStore.recordBackupOutcome(
            context,
            BackupTier.Auto,
            reason = null,
            result = Result.success(automatic.fileName),
        )

        val health = await(viewModel.backupHealth) { it is BackupHealthState.Succeeded }
        val succeeded = health as BackupHealthState.Succeeded
        assertEquals(automatic.timestamp, succeeded.timestamp)
        assertEquals(BackupTier.Auto, succeeded.tier)
    }

    // ---------------------------------------------------------------- repeated-tap guard

    @Test
    fun rapidRepeatedBackupNowTapsProduceExactlyOneBackupAttempt() {
        val manager = FakeBackupManager(context, BackupListResult.Empty)
        manager.createBackupDelayMs = 300L
        val viewModel = BackupsAndRestoreViewModel(context, preferences, manager)
        store.put("under-test", viewModel)

        // Simulates a user tapping "Backup Now" five times before the first attempt finishes.
        repeat(5) { viewModel.createManualBackup() }

        await(viewModel.isBackingUp) { it }
        await(viewModel.isBackingUp) { !it }
        assertEquals(1, manager.createBackupCallCount.get())
    }

    @Test
    fun isBackingUpFlagClearsAfterCompletionSoASecondTapCanRunANewBackup() {
        val manager = FakeBackupManager(context, BackupListResult.Empty)
        val viewModel = BackupsAndRestoreViewModel(context, preferences, manager)
        store.put("under-test", viewModel)

        viewModel.createManualBackup()
        await(viewModel.isBackingUp) { !it }
        viewModel.createManualBackup()
        await(viewModel.isBackingUp) { !it }

        assertEquals(2, manager.createBackupCallCount.get())
    }

    @Test
    fun statusTextCannotShowAStaleSuccessWhileANewerRequestIsPending() {
        val manager = FakeBackupManager(context, BackupListResult.Empty)
        manager.createBackupDelayMs = 300L
        val viewModel = BackupsAndRestoreViewModel(context, preferences, manager)
        store.put("under-test", viewModel)

        // With the guard in place there is only ever one request in flight — a second tap while
        // the first is pending must be a no-op, not a second overlapping request whose earlier
        // sibling's stale success could later overwrite this one's in-flight status.
        viewModel.createManualBackup()
        viewModel.createManualBackup()
        await(viewModel.isBackingUp) { it }
        assertFalse("A second tap while backing up must not start a second attempt", manager.createBackupCallCount.get() > 1)

        await(viewModel.isBackingUp) { !it }
        assertEquals(1, manager.createBackupCallCount.get())
    }

    private fun <T> await(flow: StateFlow<T>, predicate: (T) -> Boolean) = runBlocking {
        withTimeout(10_000) { flow.first(predicate) }
    }
}
