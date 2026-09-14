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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
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

    /**
     * P3-T1 / R4-F-D5: IssuesViewModel is scoped to its own NavBackStackEntry — a back-press
     * right after confirming a delete pops the entry and cancels viewModelScope. This proves the
     * delete keeps running to completion anyway, because IssuesViewModel.deleteSong routes
     * through [SongRepository.deleteSongDetached] rather than `viewModelScope.launch { repository
     * .deleteSong(id) }` — see [DeferredDeleteRepository]'s own independent [CoroutineScope].
     *
     * Mutation check (see the P3-T1 report): temporarily reverting IssuesViewModel.deleteSong to
     * `viewModelScope.launch { repository.deleteSong(id) }` makes this test time out, since
     * cancelling `subject.viewModelScope` below would then cancel the delete itself before `gate`
     * is ever released.
     */
    @Test
    fun deleteSurvivesViewModelScopeCancellation() = runBlocking {
        val callCount = AtomicInteger(0)
        val gate = CompletableDeferred<Unit>()
        val completed = CompletableDeferred<SongDeletionOutcome>()
        val detached = DeferredDeleteRepository(context, callCount, gate, fakeRepository())
        val subject = subject(
            object : SongRepository by detached {
                override fun deleteSongDetached(id: String, onResult: (Result<SongDeletionOutcome>) -> Unit) {
                    detached.deleteSongDetached(id) { result ->
                        result.onSuccess { completed.complete(it) }
                        onResult(result)
                    }
                }
            },
        )

        subject.deleteSong("song")
        // Simulate the NavBackStackEntry (and this ViewModel) being torn down mid-delete — the
        // repository call above is still suspended on `gate` at this point.
        subject.viewModelScope.cancel()

        gate.complete(Unit)
        val outcome = withTimeout(2_000) { completed.await() }

        assertEquals(SongDeletionOutcome.Deleted, outcome)
        assertEquals(1, callCount.get())
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
    ) { _, method, args ->
        when (method.name) {
            "getMissingSongs" -> flowOf(emptyList<Any>())
            "deleteSong" -> delete()
            // P3-T1: IssuesViewModel now calls this instead of deleteSong — see
            // SongRepository.deleteSongDetached's kdoc. Invoked synchronously here (the fake has
            // no scope of its own to be independent of); DeferredDeleteRepository below is what
            // actually exercises the "survives the caller's scope" behavior this exists for.
            "deleteSongDetached" -> {
                @Suppress("UNCHECKED_CAST")
                val onResult = args[1] as (Result<SongDeletionOutcome>) -> Unit
                onResult(runCatching { delete() })
                Unit
            }
            "recoverMissingArt" -> recoverMissingArt()
            else -> error("Unexpected repository call: ${method.name}")
        }
    } as SongRepository

    /**
     * Wraps a fake [SongRepository] to make `deleteSongDetached` genuinely suspend on [gate] and
     * count invocations — needed to prove [IssuesViewModel.deleteInProgress] blocks a second,
     * concurrent call rather than merely a call made after the first has already finished. Also
     * records a real [DestructiveActionTracker] entry per invocation, mirroring
     * SongRepositoryImpl.deleteSong's own coupling, so the same test proves both halves of the
     * P1-T3 fix: repository.deleteSongDetached called once, and the destructive-action count
     * bumped once.
     *
     * Launches on its own independent [scope] rather than suspending inline — same shape as
     * [com.v1per.synthx.data.repository.SongRepositoryImpl.repositoryScope] — so
     * [deleteSurvivesViewModelScopeCancellation] below can prove the call keeps running after the
     * ViewModel's own scope is cancelled, the same way the real implementation does.
     */
    private class DeferredDeleteRepository(
        private val context: Context,
        private val callCount: AtomicInteger,
        private val gate: CompletableDeferred<Unit>,
        backing: SongRepository,
    ) : SongRepository by backing {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        override fun deleteSongDetached(id: String, onResult: (Result<SongDeletionOutcome>) -> Unit) {
            scope.launch {
                callCount.incrementAndGet()
                gate.await()
                DestructiveActionTracker.recordDestructiveAction(context)
                onResult(Result.success(SongDeletionOutcome.Deleted))
            }
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
