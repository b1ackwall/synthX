package com.v1per.synthx.data.backup

import android.content.Context
import androidx.work.Configuration
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.v1per.synthx.data.scanner.LibraryScanWorker
import com.v1per.synthx.data.scanner.NewArrivalsScanWorker
import kotlinx.coroutines.awaitCancellation

internal object TestWorkManager {
    private var initialized = false

    @Synchronized
    fun ensureInitialized(context: Context) {
        if (initialized) return
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                .setMinimumLoggingLevel(android.util.Log.DEBUG)
                .setExecutor(SynchronousExecutor())
                .setWorkerFactory(ScanStubWorkerFactory)
                .build(),
        )
        initialized = true
    }
}

/** What a stubbed [LibraryScanWorker]/[NewArrivalsScanWorker] does when WorkManager starts it. */
internal sealed interface ScanStub {
    /** Stays RUNNING until cancelled — for tests that need an in-flight scan. */
    data object Hang : ScanStub
    data class Succeed(val output: androidx.work.Data = androidx.work.Data.EMPTY) : ScanStub
    data class Fail(val error: String) : ScanStub
}

/**
 * Lets a test decide how the scan workers behave without touching SAF or Room. Inactive by
 * default (returns null → WorkManager's normal factory builds the real worker), so every other
 * test in the process is unaffected; a test that sets [behavior] must reset it in @After.
 */
internal object ScanStubWorkerFactory : WorkerFactory() {
    @Volatile var behavior: ScanStub? = null

    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? {
        val stub = behavior ?: return null
        if (workerClassName != LibraryScanWorker::class.java.name &&
            workerClassName != NewArrivalsScanWorker::class.java.name
        ) {
            return null
        }
        return StubScanWorker(appContext, workerParameters, stub)
    }
}

private class StubScanWorker(
    context: Context,
    params: WorkerParameters,
    private val stub: ScanStub,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = when (stub) {
        ScanStub.Hang -> awaitCancellation()
        is ScanStub.Succeed -> Result.success(stub.output)
        is ScanStub.Fail -> Result.failure(
            androidx.work.workDataOf(LibraryScanWorker.KEY_ERROR to stub.error),
        )
    }
}
