package com.v1per.synthx.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.v1per.synthx.R
import com.v1per.synthx.data.backup.BackupFolderLaunchCheck
import com.v1per.synthx.data.backup.BackupInfo
import com.v1per.synthx.data.backup.BackupTier
import com.v1per.synthx.data.backup.RestoreWorker
import com.v1per.synthx.data.db.AlbumEntity
import com.v1per.synthx.data.db.SongEntity
import com.v1per.synthx.data.db.SynthXDatabase
import com.v1per.synthx.navigation.Screen
import com.v1per.synthx.perf.PerfTestTags
import com.v1per.synthx.playback.PlaybackError
import com.v1per.synthx.preferences.PreferencesManager
import com.v1per.synthx.testing.DebugPreferencesSnapshot
import com.v1per.synthx.ui.screens.restore.RestoreAppStatus
import com.v1per.synthx.ui.screens.restore.RestoreStatusViewModel
import com.v1per.synthx.ui.testing.FakePlaybackEngine
import com.v1per.synthx.ui.testing.FakePlaybackEngine.Companion.song
import com.v1per.synthx.ui.testing.ShellTestHost
import com.v1per.synthx.ui.testing.TestTimeouts
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
 * Things the shell shows or does on its own, outside any one screen: the playback error
 * snackbar, the backup-folder notice, the app-wide restore result dialog (and which routes own
 * that result instead), and "Go to Album" collapsing the expanded player.
 */
// Test-only restricted API use; there is no public equality-based alternative.
@SuppressLint("RestrictedApi")
@RunWith(AndroidJUnit4::class)
class ShellEventsInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.device()

    @get:Rule(order = 1)
    val preferencesSnapshot = DebugPreferencesSnapshot()

    @get:Rule(order = 2)
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val shell = ShellTestHost(composeRule)
    private val base: Context = shell.context
    private val current = song("events-current", albumId = "events-album")

    private val preferenceName = "shell-events-${UUID.randomUUID()}"
    private val isolated = object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            base.getSharedPreferences("$preferenceName-$name", mode)
    }
    private val isolatedPreferences = PreferencesManager(isolated)
    private val workManager get() = WorkManager.getInstance(base)
    private val restoreStores = mutableListOf<androidx.lifecycle.ViewModelStore>()

    @Before fun setUp() {
        shell.setUp()
        BackupFolderLaunchCheck.resetForTest()
        workManager.cancelUniqueWork(RestoreWorker.UNIQUE_WORK_NAME).result.get()
        workManager.pruneWork().result.get()
    }

    @After fun tearDown() {
        try {
            shell.tearDown()
        } finally {
            restoreStores.forEach { it.clear() }
            BackupFolderLaunchCheck.resetForTest()
            workManager.cancelUniqueWork(RestoreWorker.UNIQUE_WORK_NAME).result.get()
            workManager.pruneWork().result.get()
            base.deleteSharedPreferences("$preferenceName-synthx_prefs")
        }
    }

    private fun text(id: Int, vararg args: Any) = base.getString(id, *args)

    // ------------------------------------------------------------ playback error snackbar

    @Test fun playbackFailureShowsASnackbarWithTheSongTitle() {
        val engine = FakePlaybackEngine(current)
        shell.startShell(engine)
        engine.errorEvents.tryEmit(PlaybackError.PlaybackFailed("Events Song"))
        shell.awaitText(text(R.string.playback_error_failed, "Events Song"))
    }

    @Test fun serviceUnavailableShowsItsOwnSnackbar() {
        val engine = FakePlaybackEngine(current)
        shell.startShell(engine)
        engine.errorEvents.tryEmit(PlaybackError.ServiceUnavailable)
        shell.awaitText(text(R.string.playback_error_service_unavailable))
    }

    // ------------------------------------------------------------ backup folder notice

    @Test fun backupFolderNoticeOpensBackupsAndClearsItself() {
        PreferencesManager(base).backupFolderUri = "content://com.v1per.synthx.events/tree/missing"
        BackupFolderLaunchCheck.runOnce(base, CoroutineScope(Dispatchers.Default))
        composeRule.waitUntil(ShellTestHost.WAIT_MS) { BackupFolderLaunchCheck.grantLost.value }
        shell.startShell()

        composeRule.onNodeWithText(text(R.string.backup_folder_needs_attention)).performClick()
        composeRule.waitForIdle()

        assertEquals(Screen.BackupsAndRestore.route, shell.route())
        assertFalse("the notice must clear once tapped", BackupFolderLaunchCheck.grantLost.value)
        composeRule.onNodeWithText(text(R.string.backup_folder_needs_attention)).assertDoesNotExist()
    }

    // ------------------------------------------------------------ restore result dialog

    private fun finishedFailedRestore(): RestoreStatusViewModel {
        val requestId = runBlocking {
            RestoreWorker.enqueue(
                isolated,
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
        WorkManagerTestInitHelper.getTestDriver(base)!!.setAllConstraintsMet(requestId)
        runBlocking {
            withTimeout(ShellTestHost.WAIT_MS) {
                workManager.getWorkInfoByIdFlow(requestId).first { it?.state?.isFinished == true }
            }
        }
        val viewModel = RestoreStatusViewModel(isolated, isolatedPreferences, pendingGraceMillis = 50L)
        restoreStores += androidx.lifecycle.ViewModelStore().also { it.put("restore", viewModel) }
        composeRule.waitUntil(ShellTestHost.WAIT_MS) { viewModel.status.value is RestoreAppStatus.Failed }
        return viewModel
    }

    @Test fun restoreResultShowsOnHomeIsAcknowledgedAndOkDismissesIt() {
        val viewModel = finishedFailedRestore()
        shell.startShell(restoreStatusViewModel = viewModel)

        shell.awaitText(text(R.string.restore_failed_title))
        composeRule.waitUntil(ShellTestHost.WAIT_MS) { isolatedPreferences.restoreOutcomeAcknowledged }

        composeRule.onNodeWithText(text(R.string.action_ok)).performClick()
        composeRule.waitForIdle()

        assertEquals(RestoreAppStatus.Idle, viewModel.status.value)
        composeRule.onNodeWithText(text(R.string.restore_failed_title)).assertDoesNotExist()
    }

    /** The Restore screen owns the result; the shell's dialog must not cover it. */
    @Test fun restoreRouteKeepsTheShellDialogAway() {
        val viewModel = finishedFailedRestore()
        shell.startShell(start = Screen.Restore.route, restoreStatusViewModel = viewModel)
        composeRule.waitForIdle()
        assertEquals(Screen.Restore.route, shell.route())
        assertEquals("shell result dialog shown on Restore", 0, shell.textCount(text(R.string.restore_failed_title)))
        assertTrue("the result must stay pending for the screen", viewModel.status.value is RestoreAppStatus.Failed)
    }

    /** The wizard owns the result on its own steps; the shell's dialog must not cover it. */
    @Test fun onboardingRouteKeepsTheShellDialogAway() {
        val viewModel = finishedFailedRestore()
        shell.startShell(start = Screen.Onboarding.route, restoreStatusViewModel = viewModel)
        composeRule.waitForIdle()
        assertEquals(Screen.Onboarding.route, shell.route())
        assertEquals("shell result dialog shown on the wizard", 0, shell.textCount(text(R.string.restore_failed_title)))
        assertTrue("the result must stay pending for the wizard", viewModel.status.value is RestoreAppStatus.Failed)
    }

    /**
     * PINNED current behaviour (suspected bug, see slice3-implementation.md): on the very first
     * frame no route is known yet, so the shell treats the result as its own and marks it shown,
     * even though the wizard is the start screen and never showed it.
     */
    @Test fun pinnedFirstFrameMarksTheResultShownEvenOnTheWizard() {
        val viewModel = finishedFailedRestore()
        shell.startShell(start = Screen.Onboarding.route, restoreStatusViewModel = viewModel)
        composeRule.waitForIdle()
        assertTrue("acknowledged on the wizard's first frame", isolatedPreferences.restoreOutcomeAcknowledged)
    }

    // ------------------------------------------------------------ Go to Album

    /** The actions sheet resolves its song from the database, so a real song row is seeded. */
    @Test fun goToAlbumFromTheExpandedPlayerOpensTheAlbumAndCollapsesThePlayer() {
        val database = SynthXDatabase.getInstance(base)
        runBlocking {
            database.albumDao().upsertAlbum(
                AlbumEntity("events-album", "Events Album", year = null, albumArtUri = null),
            )
            database.songDao().upsertSong(
                SongEntity(
                    id = current.id,
                    title = current.title,
                    albumId = "events-album",
                    duration = 60_000L,
                    filePath = current.filePath,
                    albumArtUri = null,
                    year = null,
                    dateAdded = 1L,
                ),
            )
        }
        try {
            goToAlbumCollapsesThePlayer()
        } finally {
            runBlocking {
                database.songDao().deleteSongById(current.id)
                database.albumDao().deleteOrphanAlbums()
            }
        }
    }

    private fun goToAlbumCollapsesThePlayer() {
        shell.startShell(FakePlaybackEngine(current))
        composeRule.onNodeWithTag(PerfTestTags.MiniPlayer).performClick()
        composeRule.waitForIdle()
        assertTrue("player did not expand", shell.tagDisplayed(PerfTestTags.QueueButton))

        // Semantics click: the expanded sheet's buttons sit in a gesture-driven layer.
        composeRule.onAllNodes(hasContentDescription(text(R.string.cd_more_options)) and isEnabled())
            .onFirst().performSemanticsAction(SemanticsActions.OnClick)
        shell.awaitText(text(R.string.actions_go_to_album))
        composeRule.onNode(hasText(text(R.string.actions_go_to_album))).performClick()
        composeRule.waitForIdle()

        assertEquals(Screen.AlbumDetail.ROUTE, shell.route())
        composeRule.waitUntil(ShellTestHost.WAIT_MS) { !shell.tagDisplayed(PerfTestTags.QueueButton) }
        assertTrue("collapsed player shows the bar again", shell.tagDisplayed(PerfTestTags.NavHome))
    }
}
