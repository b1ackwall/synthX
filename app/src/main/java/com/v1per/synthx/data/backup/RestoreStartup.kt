package com.v1per.synthx.data.backup

import android.content.Context
import com.v1per.synthx.data.db.SynthXDatabase
import java.util.concurrent.CountDownLatch
import android.util.Log

/** A one-shot process gate. Room callers block only if startup recovery is still running. */
internal object RestoreStartup {
    private val ready = CountDownLatch(1)
    @Volatile private var result: RestoreRecovery = RestoreRecovery.NORMAL
    fun begin(context: Context) {
        Thread({
            result = runProtectedRecovery(context, onReady = ready::countDown)
        }, "synthx-restore-recovery").start()
    }
    fun await() { ready.await() }
    fun result(): RestoreRecovery { await(); return result }

    /** The only startup gate normal Room owners may cross. */
    fun requireNormal() {
        val recovery = result()
        check(recovery == RestoreRecovery.NORMAL) {
            "Database access is disabled until restore recovery reaches a safe normal state"
        }
        RestoreCoordinator.requireNormal()
    }

    private fun recover(context: Context): RestoreRecovery {
        val dir = context.getDatabasePath(SynthXDatabase.DB_NAME).parentFile
        return if (dir == null) {
            RestoreRecovery.EMERGENCY
        } else {
            RestoreTransaction.recoverIfNeeded(context, dir)
        }
    }

    private fun publish(recovery: RestoreRecovery) {
        when (recovery) {
            RestoreRecovery.NORMAL -> RestoreCoordinator.markNormal()
            RestoreRecovery.RESTART_REQUIRED -> RestoreCoordinator.markRestartRequired(false)
            RestoreRecovery.ROLLED_BACK_RESTART_REQUIRED -> RestoreCoordinator.markRestartRequired(true)
            RestoreRecovery.EMERGENCY -> RestoreCoordinator.markEmergency(
                RestoreTransaction.diagnosticOperationId(),
            )
        }
    }

    internal fun runProtectedRecovery(
        context: Context,
        recovery: () -> RestoreRecovery = { recover(context) },
        onReady: () -> Unit = {},
    ): RestoreRecovery {
        var resolved = RestoreRecovery.EMERGENCY
        try {
            resolved = recovery()
            publish(resolved)
        } catch (failure: Throwable) {
            resolved = RestoreRecovery.EMERGENCY
            val incidentId = runCatching {
                RestoreTransaction.publishUnexpectedStartupIncident(context, failure)
            }.getOrElse {
                Log.e("RestoreStartup", "Could not persist unexpected startup incident", it)
                null
            }
            runCatching { RestoreCoordinator.markEmergency(incidentId) }
                .onFailure { Log.e("RestoreStartup", "Could not publish emergency state", it) }
        } finally {
            onReady()
        }
        return resolved
    }

    /** Runs the production startup recovery boundary synchronously for instrumentation. */
    internal fun recoverNowForTest(context: Context): RestoreRecovery {
        val recovery = runProtectedRecovery(context)
        result = recovery
        return recovery
    }

    /** Instrumentation isolation only; a production process never reverses startup result. */
    internal fun resetForTest() {
        await()
        result = RestoreRecovery.NORMAL
    }
}

/** Shared worker boundary: both startup gates resolve before admission and Room construction. */
internal object WorkerDatabaseAccess {
    suspend fun <T> run(
        awaitStartup: suspend () -> Unit = { DatabaseStartupGate.awaitReady() },
        block: suspend () -> T,
    ): T {
        awaitStartup()
        return RestoreCoordinator.withDbOperation(block)
    }
}
