package com.v1per.synthx.data.backup

import android.content.Context
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper

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
                .build(),
        )
        initialized = true
    }
}
