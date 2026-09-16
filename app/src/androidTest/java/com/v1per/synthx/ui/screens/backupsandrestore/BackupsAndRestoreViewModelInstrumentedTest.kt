package com.v1per.synthx.ui.screens.backupsandrestore

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.data.backup.BackupManager
import com.v1per.synthx.preferences.PreferencesManager
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Covers R7-F05: a rejected backup-folder pick must leave [BackupsAndRestoreViewModel.backupFolderError]
 * set persistently, matching the existing launch-time/screen-entry health-check convention
 * (direct `.value =` assignment, no timer) instead of the old `showTemporarily` 4-second window.
 */
@RunWith(AndroidJUnit4::class)
class BackupsAndRestoreViewModelInstrumentedTest {
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
    }

    @Test
    fun rejectedFolderPickErrorPersistsPastTheOldFourSecondWindow() {
        val viewModel = BackupsAndRestoreViewModel(context, preferences, BackupManager(context))
        store.put("under-test", viewModel)

        // No real content provider backs this authority, so ensureFolderStructure fails and
        // saveBackupFolder takes the Outcome.UNUSABLE rejection branch.
        val bogus = Uri.parse("content://synthx.test.rejected/tree/primary%3ABogus")

        runBlocking {
            viewModel.saveBackupFolder(bogus)
            await(viewModel.backupFolderError) { it != null }
            val messageWhenSet = viewModel.backupFolderError.value
            assertNotNull(messageWhenSet)

            // StatusMessageDisplayMs (the old showTemporarily window) is 4_000ms; wait well past
            // it and confirm the error is still there instead of having been cleared.
            delay(5_000)
            assertNotNull(viewModel.backupFolderError.value)
        }
    }

    private fun <T> await(flow: StateFlow<T>, predicate: (T) -> Boolean) = runBlocking {
        withTimeout(10_000) { flow.first(predicate) }
    }
}
