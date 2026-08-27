package com.v1per.synthx.data.backup

import android.content.Context
import com.v1per.synthx.data.db.SynthXDatabase
import java.util.concurrent.CountDownLatch

/** A one-shot process gate. Room callers block only if startup recovery is still running. */
internal object RestoreStartup {
    private val ready = CountDownLatch(1)
    @Volatile private var result: RestoreRecovery = RestoreRecovery.NORMAL
    fun begin(context: Context) {
        Thread({
            val dir = context.getDatabasePath(SynthXDatabase.DB_NAME).parentFile
            result = if (dir == null) RestoreRecovery.EMERGENCY else RestoreTransaction.recoverIfNeeded(context, dir)
            when (result) {
                RestoreRecovery.NORMAL -> RestoreCoordinator.markNormal()
                RestoreRecovery.RESTART_REQUIRED -> RestoreCoordinator.markRestartRequired(false)
                RestoreRecovery.ROLLED_BACK_RESTART_REQUIRED -> RestoreCoordinator.markRestartRequired(true)
                RestoreRecovery.EMERGENCY -> RestoreCoordinator.markEmergency(
                    RestoreTransaction.diagnosticOperationId(),
                )
            }
            ready.countDown()
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
}

/** Shared worker boundary: recovery resolves before admission and Room construction. */
internal object WorkerDatabaseAccess {
    suspend fun <T> run(
        awaitStartup: suspend () -> Unit = { RestoreStartup.requireNormal() },
        block: suspend () -> T,
    ): T {
        awaitStartup()
        return RestoreCoordinator.withDbOperation(block)
    }
}
