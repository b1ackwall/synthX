package com.v1per.synthx.ui.screens.onboarding

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.v1per.synthx.data.backup.BackupManager
import com.v1per.synthx.data.backup.ScanStub
import com.v1per.synthx.data.backup.ScanStubWorkerFactory
import com.v1per.synthx.data.backup.TestWorkManager
import com.v1per.synthx.data.repository.SongRepository
import com.v1per.synthx.data.scanner.LibraryScanWorker
import com.v1per.synthx.preferences.PreferencesManager
import com.v1per.synthx.ui.screens.librarymanagement.LibraryManagementViewModel
import com.v1per.synthx.ui.testing.TestTimeouts
import com.v1per.synthx.ui.theme.SynthXTheme
import java.lang.reflect.Proxy
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ScanningStep hands over to Home through the latest `onComplete`, once per entering Succeeded.
 * The scan is a gated stub worker so the test decides when it succeeds. [onComplete] is held in
 * state so a test can swap the lambda the way a recomposition of the parent does.
 */
@RunWith(AndroidJUnit4::class)
class ScanningStepLatestCallbackTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.device()

    @get:Rule val composeRule = createComposeRule()

    private val appContext: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val testId = "scanning-step-test-${UUID.randomUUID()}"
    private val context = object : ContextWrapper(appContext) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            appContext.getSharedPreferences("$testId-$name", mode)
    }
    private val workManager get() = WorkManager.getInstance(context)
    private val calls = mutableListOf<String>()
    private val onComplete = mutableStateOf<() -> Unit>({ calls += "A" })
    private lateinit var library: LibraryManagementViewModel

    @Before fun setUp() {
        TestWorkManager.ensureInitialized(context)
        ScanStubWorkerFactory.behavior = null
        workManager.pruneWork().result.get()
        val repository = Proxy.newProxyInstance(
            SongRepository::class.java.classLoader,
            arrayOf(SongRepository::class.java),
        ) { _, method, _ ->
            check(method.name == "getMissingSongCount") { "Unexpected repository access: ${method.name}" }
            flowOf(0)
        } as SongRepository
        composeRule.runOnUiThread {
            library =
                LibraryManagementViewModel(context, PreferencesManager(context), repository, BackupManager(context))
        }
    }

    @After fun tearDown() {
        workManager.cancelUniqueWork(LibraryScanWorker.UNIQUE_WORK_NAME).result.get()
        ScanStubWorkerFactory.behavior = null
        workManager.pruneWork().result.get()
        appContext.deleteSharedPreferences("$testId-synthx_prefs")
    }

    private val gate = CompletableDeferred<Unit>()

    /** Composes ScanningStep on a scan that is RUNNING until [finishScan]. */
    private fun startScan() {
        ScanStubWorkerFactory.behavior = ScanStub.Gated(gate)
        val id = LibraryScanWorker.enqueue(context, ExistingWorkPolicy.REPLACE).id
        runBlocking {
            withTimeout(AWAIT_MS) { workManager.getWorkInfoByIdFlow(id).first { it?.state == WorkInfo.State.RUNNING } }
        }
        composeRule.runOnUiThread { library._firstScanRequestId.value = id }
        composeRule.setContent {
            SynthXTheme { ScanningStep(library, onChangeFolder = {}, onComplete = onComplete.value) }
        }
        composeRule.waitForIdle()
    }

    private fun finishScan() {
        gate.complete(Unit)
        composeRule.waitUntil(AWAIT_MS) { calls.isNotEmpty() }
        composeRule.waitForIdle()
    }

    private fun swapTo(name: String) {
        composeRule.runOnUiThread { onComplete.value = { calls += name } }
        composeRule.waitForIdle()
    }

    @Test fun enteringSucceededCallsOnCompleteExactlyOnce() {
        startScan()
        composeRule.runOnIdle { assertEquals(emptyList<String>(), calls.toList()) }
        finishScan()
        composeRule.runOnIdle { assertEquals(listOf("A"), calls.toList()) }
    }

    @Test fun aCallbackSwappedWhileScanningIsTheOneCalledOnSuccess() {
        startScan()
        swapTo("B")
        finishScan()
        composeRule.runOnIdle { assertEquals(listOf("B"), calls.toList()) }
    }

    @Test fun aCallbackSwappedAfterSuccessIsNotCalledAgain() {
        startScan()
        finishScan()
        swapTo("B")
        composeRule.runOnIdle { assertEquals(listOf("A"), calls.toList()) }
    }

    private companion object {
        const val AWAIT_MS = 10_000L
    }
}
