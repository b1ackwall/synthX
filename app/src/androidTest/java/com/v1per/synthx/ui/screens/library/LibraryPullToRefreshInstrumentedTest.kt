package com.v1per.synthx.ui.screens.library

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.v1per.synthx.R
import com.v1per.synthx.data.backup.ScanStub
import com.v1per.synthx.data.backup.ScanStubWorkerFactory
import com.v1per.synthx.data.backup.TestWorkManager
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.data.repository.SongRepository
import com.v1per.synthx.data.scanner.LibraryScanWorker
import com.v1per.synthx.data.scanner.NewArrivalsScanWorker
import com.v1per.synthx.preferences.PreferencesManager
import com.v1per.synthx.ui.testing.TestTimeouts
import java.lang.reflect.Proxy
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P9-T2: LibraryViewModel.onPullToRefresh had no tests. Runs against the shared test WorkManager
 * with [ScanStubWorkerFactory] deciding how the scan workers behave, so each outcome is
 * deterministic and no SAF tree or Room database is touched.
 */
@RunWith(AndroidJUnit4::class)
class LibraryPullToRefreshInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER) val testTimeout = TestTimeouts.device()

    private val appContext: Context = ApplicationProvider.getApplicationContext()
    private val testId = "pull-refresh-test-${UUID.randomUUID()}"
    private val context = object : ContextWrapper(appContext) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            appContext.getSharedPreferences("$testId-$name", mode)
    }
    private val preferences = PreferencesManager(context)
    private val workManager get() = WorkManager.getInstance(context)
    private val scope = CoroutineScope(Dispatchers.Default)
    private lateinit var viewModel: LibraryViewModel

    @Before fun setUp() {
        TestWorkManager.ensureInitialized(context)
        ScanStubWorkerFactory.behavior = null
        workManager.cancelUniqueWork(LibraryScanWorker.UNIQUE_WORK_NAME).result.get()
        workManager.pruneWork().result.get()
        val repository = Proxy.newProxyInstance(
            SongRepository::class.java.classLoader,
            arrayOf(SongRepository::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getRecentSongs" -> flowOf(emptyList<Song>())
                "getStarredSongCount" -> flowOf(0)
                else -> error("Unexpected repository access: ${method.name}")
            }
        } as SongRepository
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            viewModel = LibraryViewModel(repository, context, preferences, workManager)
        }
    }

    @After fun tearDown() {
        workManager.cancelUniqueWork(LibraryScanWorker.UNIQUE_WORK_NAME).result.get()
        ScanStubWorkerFactory.behavior = null
        workManager.pruneWork().result.get()
        scope.cancel()
        appContext.deleteSharedPreferences("$testId-synthx_prefs")
    }

    // ------------------------------------------------------------- arrivals folder configured

    @Test fun arrivalsConfiguredScansArrivalsAndReportsWhatItFound() {
        preferences.newArrivalsFolderUri = ARRIVALS
        ScanStubWorkerFactory.behavior = ScanStub.Succeed(workDataOf(LibraryScanWorker.KEY_ADDED to 3))

        val message = pullAndAwaitMessage()

        assertEquals(context.getString(R.string.scan_status_new_arrivals_found, 3), message)
        assertEquals(listOf(NewArrivalsScanWorker::class.java.name), scanTagsUnderUniqueName())
    }

    @Test fun arrivalsConfiguredReportsNothingNewHonestlyRatherThanSilence() {
        preferences.newArrivalsFolderUri = ARRIVALS
        ScanStubWorkerFactory.behavior = ScanStub.Succeed()

        assertEquals(
            context.getString(R.string.scan_status_new_arrivals_none),
            pullAndAwaitMessage(),
        )
    }

    @Test fun arrivalsConfiguredSurfacesAFailedScanAsAFailure() {
        preferences.newArrivalsFolderUri = ARRIVALS
        ScanStubWorkerFactory.behavior = ScanStub.Fail("grant revoked")

        val message = pullAndAwaitMessage()

        assertEquals(context.getString(R.string.scan_status_failed, "grant revoked"), message)
        // A real failure is not the KEEP-ignored message.
        assertFalse(message == context.getString(R.string.library_refresh_already_scanning))
    }

    // ------------------------------------------------------------- no arrivals folder

    @Test fun noArrivalsFolderFallsBackToTheMainRootScan() {
        assertNull(preferences.newArrivalsFolderUri)
        ScanStubWorkerFactory.behavior = ScanStub.Succeed(
            workDataOf(
                LibraryScanWorker.KEY_ADDED to 2,
                LibraryScanWorker.KEY_UPDATED to 1,
                LibraryScanWorker.KEY_MISSING to 0,
            ),
        )

        val message = pullAndAwaitMessage()

        assertEquals(context.getString(R.string.scan_status_done, 2, 1, 0), message)
        // Exactly one scan, and it is the main-root worker — never an arrivals scan that is
        // guaranteed to fail with no folder to walk.
        assertEquals(listOf(LibraryScanWorker::class.java.name), scanTagsUnderUniqueName())
    }

    @Test fun noArrivalsFolderMainScanFailureIsSurfacedToo() {
        ScanStubWorkerFactory.behavior = ScanStub.Fail("No folder selected")

        assertEquals(
            context.getString(R.string.scan_status_failed, "No folder selected"),
            pullAndAwaitMessage(),
        )
    }

    // ------------------------------------------------------------- KEEP-ignored

    @Test fun keepIgnoredPullIsReportedAsAlreadyScanningNotAsFailure() {
        preferences.newArrivalsFolderUri = ARRIVALS
        ScanStubWorkerFactory.behavior = ScanStub.Hang
        // Something else (a Settings rescan) owns the shared unique name and is running.
        val owner = LibraryScanWorker.enqueue(context, ExistingWorkPolicy.KEEP)
        runBlocking { withTimeout(10_000) { awaitState(owner.id, WorkInfo.State.RUNNING) } }

        val message = pullAndAwaitMessage()

        assertEquals(context.getString(R.string.library_refresh_already_scanning), message)
        // The pull started nothing: the owner is still the only request under the name.
        assertEquals(listOf(owner.id), workManager.getWorkInfosForUniqueWork(LibraryScanWorker.UNIQUE_WORK_NAME).get().map { it.id })
        // Spinner shows the scan that is genuinely running, not a phantom request of the pull's.
        assertTrue(awaitRefreshing(true))
    }

    @Test fun aFinishedEarlierScanDoesNotMakeALaterPullLookKeepIgnored() {
        preferences.newArrivalsFolderUri = ARRIVALS
        ScanStubWorkerFactory.behavior = ScanStub.Succeed(workDataOf(LibraryScanWorker.KEY_ADDED to 1))

        val first = pullAndAwaitMessage()
        val second = pullAndAwaitMessage()

        // KEEP only discards against *unfinished* work. Both pulls ran; neither is "already scanning".
        assertEquals(context.getString(R.string.scan_status_new_arrivals_found, 1), first)
        assertEquals(context.getString(R.string.scan_status_new_arrivals_found, 1), second)
    }

    @Test fun keepIgnoredPullLeavesNoResultToBeMistakenForASuccess() {
        preferences.newArrivalsFolderUri = ARRIVALS
        ScanStubWorkerFactory.behavior = ScanStub.Hang
        val owner = LibraryScanWorker.enqueue(context, ExistingWorkPolicy.KEEP)
        runBlocking { withTimeout(10_000) { awaitState(owner.id, WorkInfo.State.RUNNING) } }

        val messages = collectMessages(count = 2, timeoutMs = 3_000)

        // Exactly one message (the ignored notice); nothing follows it, since no request exists to
        // finish. A second message would mean the ignored request was still being awaited.
        assertEquals(listOf(context.getString(R.string.library_refresh_already_scanning)), messages)
    }

    // ------------------------------------------------------------- spinner

    @Test fun spinnerFollowsTheArrivalsScanItStartedAndClearsWhenItEnds() {
        preferences.newArrivalsFolderUri = ARRIVALS
        ScanStubWorkerFactory.behavior = ScanStub.Hang
        assertFalse(awaitRefreshing(false))

        InstrumentationRegistry.getInstrumentation().runOnMainSync { viewModel.onPullToRefresh() }
        assertTrue(awaitRefreshing(true))

        workManager.cancelUniqueWork(LibraryScanWorker.UNIQUE_WORK_NAME).result.get()
        assertFalse(awaitRefreshing(false))
    }

    @Test fun spinnerFollowsAMainRootScanToo() {
        ScanStubWorkerFactory.behavior = ScanStub.Hang
        LibraryScanWorker.enqueue(context, ExistingWorkPolicy.KEEP)
        assertTrue(awaitRefreshing(true))
    }

    // ------------------------------------------------------------- helpers

    /** Subscribes first (UNDISPATCHED) so the one-shot message cannot be missed, then pulls. */
    private fun pullAndAwaitMessage(): String {
        val pending: Deferred<String> =
            scope.async(start = CoroutineStart.UNDISPATCHED) { viewModel.refreshMessages.first() }
        InstrumentationRegistry.getInstrumentation().runOnMainSync { viewModel.onPullToRefresh() }
        return runBlocking { withTimeout(10_000) { pending.await() } }
    }

    private fun collectMessages(count: Int, timeoutMs: Long): List<String> {
        val collected = mutableListOf<String>()
        val job = scope.async(start = CoroutineStart.UNDISPATCHED) {
            viewModel.refreshMessages.collect { synchronized(collected) { collected += it } }
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync { viewModel.onPullToRefresh() }
        runBlocking {
            withTimeoutOrNull(timeoutMs) {
                while (synchronized(collected) { collected.size } < count) kotlinx.coroutines.delay(50)
            }
        }
        job.cancel()
        return synchronized(collected) { collected.toList() }
    }

    private fun awaitRefreshing(expected: Boolean): Boolean = runBlocking {
        withTimeout(10_000) { viewModel.isRefreshing.first { it == expected } }
    }

    private suspend fun awaitState(id: UUID, state: WorkInfo.State) {
        workManager.getWorkInfoByIdFlow(id).first { it?.state == state }
    }

    private fun scanTagsUnderUniqueName(): List<String> =
        workManager.getWorkInfosForUniqueWork(LibraryScanWorker.UNIQUE_WORK_NAME).get()
            .flatMap { info ->
                info.tags.filter { it == LibraryScanWorker::class.java.name || it == NewArrivalsScanWorker::class.java.name }
            }

    private companion object {
        const val ARRIVALS = "content://synthx.test.arrivals/tree/primary%3AArrivals"
    }
}
