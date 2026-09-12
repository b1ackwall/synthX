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
import com.v1per.synthx.preferences.PreferencesManager
import java.lang.reflect.Proxy
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
    }

    @After
    fun tearDown() {
        viewModel?.viewModelScope?.cancel()
        viewModel = null
        RestoreCoordinator.resetForTest()
        val workManager = androidx.work.WorkManager.getInstance(context)
        workManager.cancelUniqueWork(IssuesScanWorker.UNIQUE_WORK_NAME).result.get()
        workManager.pruneWork().result.get()
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
    ): IssuesViewModel {
        val repository = Proxy.newProxyInstance(
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
        return IssuesViewModel(
            context = context,
            repository = repository,
            preferences = PreferencesManager(context),
            enqueueScan = {},
        ).also { viewModel = it }
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
