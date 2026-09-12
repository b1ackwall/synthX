package com.v1per.synthx.ui.screens.issues

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.data.backup.RestoreCoordinator
import com.v1per.synthx.data.backup.TestWorkManager
import com.v1per.synthx.data.repository.SongDeletionOutcome
import com.v1per.synthx.data.repository.SongRepository
import com.v1per.synthx.data.scanner.IssuesScanWorker
import com.v1per.synthx.preferences.DestructiveActionTracker
import com.v1per.synthx.preferences.PreferencesManager
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IssuesViewModelInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var viewModel: IssuesViewModel? = null

    @Before
    fun setUp() {
        TestWorkManager.ensureInitialized(context)
        RestoreCoordinator.resetForTest()
        resetDestructiveActionTracker()
    }

    @After
    fun tearDown() {
        viewModel?.viewModelScope?.cancel()
        viewModel = null
        RestoreCoordinator.resetForTest()
        resetDestructiveActionTracker()
        val workManager = androidx.work.WorkManager.getInstance(context)
        workManager.cancelUniqueWork(IssuesScanWorker.UNIQUE_WORK_NAME).result.get()
        workManager.pruneWork().result.get()
    }

    // DestructiveActionTracker.resetForTest() only clears its in-memory StateFlow, not the
    // PreferencesManager-backed count it reads on the next recordDestructiveAction — which
    // persists across test runs on a real device/emulator, unlike an in-memory Room DB. Without
    // this, a count asserted here could include a carry-over from another test's device state.
    private fun resetDestructiveActionTracker() {
        DestructiveActionTracker.resetForTest()
        PreferencesManager(context).destructiveActionsSinceBackup = 0
    }

    @Test
    fun deleteDuringRestoreEmitsActionFailed() = runBlocking {
        val subject = subject(
            delete = {
                val permit = RestoreCoordinator.acquireOperation()
                permit.release()
                SongDeletionOutcome.Deleted
            },
        )
        RestoreCoordinator.exclusive { }

        assertEquals(
            IssuesActionEvent.ActionFailed,
            eventFrom(subject) { subject.deleteSong("song") },
        )
    }

    @Test
    fun fileRemainedEmitsDistinctCleanupWarning() = runBlocking {
        val subject = subject(delete = { SongDeletionOutcome.FileRemained })

        assertEquals(
            IssuesActionEvent.DeletedButFileRemained,
            eventFrom(subject) { subject.deleteSong("song") },
        )
    }

    @Test
    fun cleanupUncertainEmitsDistinctCleanupWarning() = runBlocking {
        val subject = subject(delete = { SongDeletionOutcome.CleanupUncertain })

        assertEquals(
            IssuesActionEvent.DeletedButCleanupUncertain,
            eventFrom(subject) { subject.deleteSong("song") },
        )
    }

    @Test
    fun rapidDoubleTapInvokesRepositoryDeleteExactlyOnce() = runBlocking {
        val callCount = AtomicInteger(0)
        val gate = CompletableDeferred<Unit>()
        val subject = subject(
            DeferredDeleteRepository(context, callCount, gate, fakeRepository()),
        )

        // First tap: starts the delete, which suspends on `gate` before completing —
        // i.e. genuinely still in flight, not merely "launched but not yet dispatched".
        subject.deleteSong("song")
        // Second tap, while the first is still suspended inside repository.deleteSong: must be
        // a no-op — see IssuesViewModel.deleteInProgress.
        subject.deleteSong("song")

        gate.complete(Unit)
        withTimeout(2_000) { subject.deleteInProgress.first { !it } }

        assertEquals(1, callCount.get())
        assertEquals(1, DestructiveActionTracker.count.value)
    }

    @Test
    fun missingArtRecoveryFailureEmitsActionFailed() = runBlocking {
        val subject = subject(recoverMissingArt = { error("art recovery failed") })

        assertEquals(
            IssuesActionEvent.ActionFailed,
            eventFrom(subject) { subject.fixMissingArt(listOf("song")) },
        )
    }

    private fun subject(
        delete: () -> SongDeletionOutcome = { SongDeletionOutcome.Deleted },
        recoverMissingArt: () -> Unit = {},
    ): IssuesViewModel = subject(fakeRepository(delete, recoverMissingArt))

    private fun subject(repository: SongRepository): IssuesViewModel {
        return IssuesViewModel(
            context = context,
            repository = repository,
            preferences = PreferencesManager(context),
            enqueueScan = {},
        ).also { viewModel = it }
    }

    private fun fakeRepository(
        delete: () -> SongDeletionOutcome = { SongDeletionOutcome.Deleted },
        recoverMissingArt: () -> Unit = {},
    ): SongRepository = Proxy.newProxyInstance(
        SongRepository::class.java.classLoader,
        arrayOf(SongRepository::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "getMissingSongs" -> flowOf(emptyList<Any>())
            "deleteSong" -> delete()
            "recoverMissingArt" -> recoverMissingArt()
            else -> error("Unexpected repository call: ${method.name}")
        }
    } as SongRepository

    /**
     * Wraps a fake [SongRepository] to make `deleteSong` genuinely suspend on [gate] and count
     * invocations — needed to prove [IssuesViewModel.deleteInProgress] blocks a second,
     * concurrent call rather than merely a call made after the first has already finished. Also
     * records a real [DestructiveActionTracker] entry per invocation, mirroring
     * SongRepositoryImpl.deleteSong's own coupling, so the same test proves both halves of the
     * P1-T3 fix: repository.deleteSong called once, and the destructive-action count bumped once.
     */
    private class DeferredDeleteRepository(
        private val context: Context,
        private val callCount: AtomicInteger,
        private val gate: CompletableDeferred<Unit>,
        backing: SongRepository,
    ) : SongRepository by backing {
        override suspend fun deleteSong(id: String): SongDeletionOutcome {
            callCount.incrementAndGet()
            gate.await()
            DestructiveActionTracker.recordDestructiveAction(context)
            return SongDeletionOutcome.Deleted
        }
    }

    private suspend fun eventFrom(
        subject: IssuesViewModel,
        action: () -> Unit,
    ): IssuesActionEvent {
        val event = kotlinx.coroutines.coroutineScope {
            val deferred = async(start = CoroutineStart.UNDISPATCHED) { subject.events.first() }
            action()
            withTimeout(2_000) { deferred.await() }
        }
        return event
    }
}
