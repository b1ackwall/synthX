package com.v1per.synthx.data.backup

import android.content.Context
import android.content.ContextWrapper
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.preferences.PreferencesManager
import com.v1per.synthx.ui.testing.TestTimeouts
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The backup-folder health check for the case a test can reach: a saved folder whose write grant
 * is gone. "Healthy" and "Unwritable" need a persisted SAF write grant, which a test cannot hold
 * (see docs/code-work/Safety-Net/slice2-implementation.md, NEEDS APP CHANGE).
 */
@RunWith(AndroidJUnit4::class)
class BackupFolderHealthInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER) val testTimeout = TestTimeouts.device()

    private val appContext: Context = ApplicationProvider.getApplicationContext()
    private val testId = "folder-health-${UUID.randomUUID()}"
    private val context = object : ContextWrapper(appContext) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int) =
            appContext.getSharedPreferences("$testId-$name", mode)
    }

    @Before fun resetLaunchCheck() = BackupFolderLaunchCheck.resetForTest()

    @After fun cleanUp() {
        BackupFolderLaunchCheck.resetForTest()
        appContext.deleteSharedPreferences("$testId-synthx_prefs")
    }

    @Test fun folderWithoutAWriteGrantIsGrantLost() {
        assertEquals(
            BackupFolderHealth.Result.GrantLost,
            BackupFolderHealth.check(context, BackupManager(context), UNGRANTED_TREE.toUri()),
        )
    }

    @Test fun launchCheckRaisesTheNoticeForASavedFolderWhoseGrantIsGone() = runBlocking {
        PreferencesManager(context).backupFolderUri = UNGRANTED_TREE

        runLaunchCheck()

        assertTrue(BackupFolderLaunchCheck.grantLost.value)
    }

    @Test fun launchCheckStaysQuietWithoutASavedFolder() = runBlocking {
        runLaunchCheck()

        assertFalse(BackupFolderLaunchCheck.grantLost.value)
    }

    @Test fun launchCheckRunsOncePerProcessAndDismissClearsTheNotice() = runBlocking {
        PreferencesManager(context).backupFolderUri = UNGRANTED_TREE
        runLaunchCheck()
        BackupFolderLaunchCheck.dismiss()

        runLaunchCheck()

        assertFalse("a second launch check in one process must not run again", BackupFolderLaunchCheck.grantLost.value)
    }

    private suspend fun runLaunchCheck() {
        val job = Job()
        BackupFolderLaunchCheck.runOnce(context, CoroutineScope(job))
        job.children.toList().forEach { it.join() }
        job.cancel()
    }

    private companion object {
        const val UNGRANTED_TREE = "content://com.android.externalstorage.documents/tree/primary%3ASlice2Backups"
    }
}
