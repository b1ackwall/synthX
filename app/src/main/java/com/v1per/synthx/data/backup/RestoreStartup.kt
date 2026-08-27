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
                RestoreRecovery.EMERGENCY -> RestoreCoordinator.markEmergency()
            }
            ready.countDown()
        }, "synthx-restore-recovery").start()
    }
    fun await() { ready.await() }
    fun result(): RestoreRecovery { await(); return result }
}
