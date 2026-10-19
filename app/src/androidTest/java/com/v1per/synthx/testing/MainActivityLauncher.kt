package com.v1per.synthx.testing

import android.content.Context
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.v1per.synthx.MainActivity
import com.v1per.synthx.data.backup.BackupFolderLaunchCheck
import com.v1per.synthx.data.backup.DatabaseStartupController
import com.v1per.synthx.data.backup.DatabaseStartupGate
import com.v1per.synthx.data.backup.LiveDatabaseVersion
import com.v1per.synthx.data.backup.RestoreCoordinator
import com.v1per.synthx.data.backup.RestorePermitLeakCheck
import com.v1per.synthx.data.backup.TestWorkManager
import com.v1per.synthx.data.db.SynthXDatabase
import com.v1per.synthx.data.repository.SongRepositoryImpl
import com.v1per.synthx.perf.PerfTestTags
import com.v1per.synthx.playback.PlaybackController
import com.v1per.synthx.playback.PlaybackViewModel
import com.v1per.synthx.ui.ShellTestTags
import com.v1per.synthx.ui.testing.FakePlaybackEngine
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred

/**
 * Launches the real [MainActivity] and controls the process database gate around it. Pair with
 * [DebugPreferencesSnapshot], since MainActivity reads the real debug preferences. Call [setUp]
 * from `@Before` and [tearDown] from `@After`; [tearDown] closes any open scenario.
 */
internal class MainActivityLauncher(private val compose: ComposeTestRule) {
    val context: Context = ApplicationProvider.getApplicationContext()
    private var scenario: ActivityScenario<MainActivity>? = null

    fun setUp() {
        TestWorkManager.ensureInitialized(context)
        BackupFolderLaunchCheck.resetForTest()
        RestorePermitLeakCheck.assertDrainedThenReset()
    }

    fun tearDown() {
        try {
            // Closing a stopped activity waits out ActivityScenario's 45 s limit before it is
            // destroyed; resuming it first makes the close take about a second.
            scenario?.let { if (it.state == Lifecycle.State.CREATED) it.moveToState(Lifecycle.State.RESUMED) }
            scenario?.close()
            scenario = null
            // Collectors outside the activity (the playback service's) are cancelled through the
            // production quiescence path, then any permit still held fails the test.
            RestorePermitLeakCheck.assertDrainedThenReset {
                if (RestoreCoordinator.currentMode() == RestoreCoordinator.Mode.NORMAL) {
                    RestoreCoordinator.exclusive { }
                }
                if (RestoreCoordinator.currentMode() == RestoreCoordinator.Mode.RESTORE_PREPARING) {
                    RestoreCoordinator.quiesceReactiveOperations()
                }
                RestoreCoordinator.awaitFiniteDrained()
            }
        } finally {
            DatabaseStartupGate.resetForTest(context)
            BackupFolderLaunchCheck.resetForTest()
            SynthXDatabase.closeInstance()
            SongRepositoryImpl.resetForTest()
        }
    }

    fun launch(): ActivityScenario<MainActivity> =
        ActivityScenario.launch(MainActivity::class.java).also { scenario = it }

    fun scenarioOrFail(): ActivityScenario<MainActivity> = checkNotNull(scenario) { "launch() first" }

    /** Launches and waits until the normal shell's nav host is composed. */
    fun launchToShell(): ActivityScenario<MainActivity> = launch().also { awaitShell() }

    fun awaitShell() {
        compose.waitUntil(WAIT_MS) { tagCount(ShellTestTags.NavHost) > 0 }
        compose.waitForIdle()
    }

    fun awaitText(text: String) = compose.waitUntil(WAIT_MS) { textCount(text) > 0 }

    fun tagCount(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size

    fun textCount(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().size

    /** Views under the activity's content root: 0 until MainActivity calls setContent. */
    fun contentViewCount(): Int {
        var count = -1
        scenarioOrFail().onActivity { activity ->
            count = activity.findViewById<android.view.ViewGroup>(android.R.id.content).childCount
        }
        return count
    }

    fun homeShown() = tagCount(PerfTestTags.HomeScreen) > 0

    /**
     * Seeds the activity's PlaybackViewModel with [engine] before MainActivity resolves its own:
     * its lazy ViewModelProvider lookup then returns this instance. Only valid while the gate is
     * held, because MainActivity resolves the ViewModel once the gate opens.
     */
    fun seedPlayback(engine: FakePlaybackEngine) {
        checkNotNull(scenario) { "launch() first" }.onActivity { activity ->
            ViewModelProvider(
                activity,
                object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T =
                        PlaybackViewModel(PlaybackController(engine)) as T
                },
            )[PlaybackViewModel::class.java]
        }
    }

    /** A database gate that stays closed until [release]; counts inspection attempts. */
    class HeldGate {
        private val opened = CompletableDeferred<Unit>()
        val attempts = AtomicInteger()
        fun release() {
            opened.complete(Unit)
        }
        internal val controller = DatabaseStartupController(
            currentVersion = SynthXDatabase.VERSION,
            awaitRestoreNormal = { opened.await() },
            inspectLiveVersion = {
                attempts.incrementAndGet()
                LiveDatabaseVersion.Present(SynthXDatabase.VERSION)
            },
            performMigration = {},
        )
    }

    fun holdGate(): HeldGate = HeldGate().also { DatabaseStartupGate.installForTest(it.controller) }

    /** A gate whose first [failures] inspections throw what [error] builds; later ones succeed. */
    fun failingGate(failures: Int = 1, error: () -> Throwable): AtomicInteger {
        val attempts = AtomicInteger()
        DatabaseStartupGate.installForTest(
            DatabaseStartupController(
                currentVersion = SynthXDatabase.VERSION,
                awaitRestoreNormal = {},
                inspectLiveVersion = {
                    if (attempts.incrementAndGet() <= failures) throw error()
                    LiveDatabaseVersion.Present(SynthXDatabase.VERSION)
                },
                performMigration = {},
            ),
        )
        return attempts
    }

    companion object {
        const val WAIT_MS = 20_000L
    }
}
